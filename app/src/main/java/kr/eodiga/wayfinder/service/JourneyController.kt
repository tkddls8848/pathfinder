package kr.eodiga.wayfinder.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.repository.TransitRepository
import kr.eodiga.wayfinder.domain.model.BusArrival
import kr.eodiga.wayfinder.domain.model.GuidanceStage
import kr.eodiga.wayfinder.domain.model.Geo
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.location.LocationProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 진행 중인 여정 하나를 관리하는 상태 기계.
 *
 * 목업에서는 버튼을 눌러야 다음 화면으로 갔지만, 실제 제품에서는 그러면 안 된다.
 * 어르신이 화면을 보고 있지 않아도 상태가 스스로 진행되어야 하고,
 * 특히 **하차 알림은 화면이 꺼져 있어도 반드시 울려야 한다.**
 * 그래서 이 컨트롤러는 UI 가 아니라 [JourneyGuidanceService] 수명에 붙는다.
 *
 * 상태 전이 근거:
 *  - 도보 → 정류장 도착 : 실시간 위치가 정류소 [STOP_ARRIVAL_RADIUS_M] 이내
 *  - 정류장 → 승차 확인 : 도착정보 arrtime 이 60초 이하로 떨어졌다가 목록에서 사라짐
 *  - 승차 → 이동 중    : 어르신이 "네, 탔어요" 를 누름 (오탐이 치명적이라 유일한 수동 확인)
 *  - 이동 중 → 하차 준비 : 실시간 차량 위치 기준 남은 정거장 2 이하
 *  - 하차 준비 → 벨    : 남은 정거장 1 이하
 */
@Singleton
class JourneyController @Inject constructor(
    private val transit: TransitRepository,
    private val location: LocationProvider,
    private val voice: VoiceGuide,
    private val haptics: Haptics,
) {

    private companion object {
        /** 정류소 도착 판정 반경. GPS 오차와 정류장 폭을 고려한 값. */
        const val STOP_ARRIVAL_RADIUS_M = 35.0

        /** 목적지 도착 판정 반경. */
        const val DESTINATION_RADIUS_M = 40.0

        /** 이 정거장 수부터 "일어날 준비" 안내. */
        const val PREPARE_STOPS = 2

        /** 이 정거장 수부터 "벨을 누르세요". */
        const val BELL_STOPS = 1

        /**
         * 탄 차량을 이 횟수만큼 연달아 못 찾으면 "안내를 줄 수 없다" 고 알린다.
         * 한 번은 응답에서 잠깐 빠진 것일 수 있어 곧바로 놀라게 하지 않는다.
         * 폴링 간격이 15초이므로 두 번이면 30초 안에는 알게 된다.
         */
        const val UNKNOWN_VEHICLE_POLLS_BEFORE_NOTICE = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJobs: Job? = null

    private val _state = MutableStateFlow(JourneyState())
    val state: StateFlow<JourneyState> = _state.asStateFlow()

    /** 승차한 차량 번호. 알면 하차 알림 정확도가 크게 올라간다. */
    private var boardedVehicleNo: String? = null

    fun start(journey: Journey) {
        stop()
        boardedVehicleNo = null
        _state.value = JourneyState(
            journey = journey,
            stage = initialStage(journey),
            isActive = true,
        )
        announceCurrentStage(force = true)
        activeJobs = scope.launch {
            launch { trackLocation() }
        }
    }

    fun stop() {
        activeJobs?.cancel()
        activeJobs = null
        scope.coroutineContext[Job]?.cancelChildren()
        _state.update { it.copy(isActive = false) }
        voice.stop()
    }

    /** 사용자가 "다시 안내받기" 를 눌렀을 때. 지금 단계를 다시 읽어준다. */
    fun repeatGuidance() {
        announceCurrentStage(force = true)
    }

    /** "네, 탔어요". 승차만은 오탐이 위험해 사람이 확인한다. */
    fun confirmBoarded() {
        val state = _state.value
        val legIndex = state.stage.legIndex
        val leg = state.journey?.legs?.getOrNull(legIndex) as? JourneyLeg.Bus ?: return
        transitionTo(GuidanceStage.Riding(legIndex, leg.stopCount, leg.intermediateStops.getOrNull(1)?.name))
        activeJobs?.cancel()
        activeJobs = scope.launch { trackRide(legIndex, leg) }
    }

    /** "아직 못 탔어요". 대기 상태로 되돌린다. */
    fun cancelBoarding() {
        val legIndex = _state.value.stage.legIndex
        transitionTo(GuidanceStage.WaitingForBus(legIndex, _state.value.arrival))
        activeJobs?.cancel()
        activeJobs = scope.launch { trackWaiting(legIndex) }
    }

    /** 하차 완료. 다음 구간으로 넘어간다. */
    fun confirmAlighted() {
        val state = _state.value
        val journey = state.journey ?: return
        val nextIndex = state.stage.legIndex + 1
        if (nextIndex >= journey.legs.size) {
            finish()
            return
        }
        activeJobs?.cancel()
        boardedVehicleNo = null
        transitionTo(stageFor(journey, nextIndex))
        activeJobs = scope.launch { trackLocation() }
    }

    private fun finish() {
        activeJobs?.cancel()
        transitionTo(GuidanceStage.Arrived)
        _state.update { it.copy(isActive = false) }
    }

    /* ── 추적 루프 ───────────────────────────────────────────── */

    /** 도보 구간: 위치를 따라가며 남은 거리를 갱신하고, 도착하면 다음 단계로. */
    private suspend fun trackLocation() {
        val journey = _state.value.journey ?: return
        location.locationStream().collect { here ->
            _state.update { it.copy(currentLocation = here) }
            val stage = _state.value.stage
            val legIndex = stage.legIndex
            val leg = journey.legs.getOrNull(legIndex)

            when {
                leg is JourneyLeg.Walk && stage is GuidanceStage.Walking -> {
                    val remaining = Geo.distanceMeters(here, leg.to)
                    _state.update { it.copy(stage = GuidanceStage.Walking(legIndex, remaining.toInt())) }

                    val isLastLeg = legIndex == journey.legs.lastIndex
                    val threshold = if (isLastLeg) DESTINATION_RADIUS_M else STOP_ARRIVAL_RADIUS_M
                    if (remaining <= threshold) {
                        if (isLastLeg) {
                            finish()
                            announceCurrentStage(force = true)
                        } else {
                            transitionTo(GuidanceStage.ArrivedAtStop(legIndex))
                            haptics.tick()
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    /** 어르신이 "네, 여기예요" 를 눌러 정류장을 확인했을 때. */
    fun confirmAtStop() {
        val state = _state.value
        val journey = state.journey ?: return
        val nextIndex = state.stage.legIndex + 1
        val busLeg = journey.legs.getOrNull(nextIndex) as? JourneyLeg.Bus ?: return
        activeJobs?.cancel()
        transitionTo(GuidanceStage.WaitingForBus(nextIndex, null))
        activeJobs = scope.launch { trackWaiting(nextIndex) }
        // 정류장을 확인한 시점의 노선 정보를 미리 읽어준다.
        voice.speak(
            "${VoiceGuide.busNumberToSpeech(busLeg.route.routeNo)}번 버스를 타시면 돼요.",
            priority = Priority.INTERRUPT,
        )
    }

    /** 대기 구간: 도착정보를 폴링하며 임박하면 진동 + 승차 확인 화면으로. */
    private suspend fun trackWaiting(legIndex: Int) = coroutineScope {
        val journey = _state.value.journey ?: return@coroutineScope
        val leg = journey.legs.getOrNull(legIndex) as? JourneyLeg.Bus ?: return@coroutineScope

        transit.arrivalStream(leg.boardStop, leg.route.routeId).collect { outcome ->
            when (outcome) {
                is Outcome.Failure -> _state.update { it.copy(failure = outcome.reason) }
                is Outcome.Success -> {
                    val arrival = outcome.value
                    _state.update {
                        it.copy(
                            arrival = arrival,
                            failure = null,
                            stage = GuidanceStage.WaitingForBus(legIndex, arrival),
                        )
                    }
                    if (arrival != null && arrival.isImminent) {
                        // 알림이 먼저다. 차량 특정은 네트워크 호출이라 재시도까지 겹치면
                        // 수십 초가 걸릴 수 있는데, 버스는 60초 안에 온다. 이 뒤에 세우면
                        // 버스가 지나간 다음 "손을 드세요" 가 나가게 된다.
                        haptics.busApproaching()
                        voice.speak(
                            // "손을 드세요" 는 몇 초 안에 해야 하는 동작이라 명령형을 남긴다.
                            "${VoiceGuide.busNumberToSpeech(leg.route.routeNo)}번 버스가 곧 와요. 손을 드세요.",
                            priority = Priority.CRITICAL,
                        )
                        transitionTo(GuidanceStage.Boarding(legIndex))

                        // 어르신이 "네, 탔어요" 를 누르기까지 최소 몇 초는 걸린다.
                        // 그 사이에 승차 정류장 근처의 유일한 차량을 잡아 둔다.
                        // 이후에는 이 번호만 추적하며, 사라져도 다른 차량으로 바꾸지 않는다.
                        // 여기서 취소되면(먼저 누른 경우) 추적 스트림이 스스로 다시 찾는다.
                        val boardOrder = leg.intermediateStops.firstOrNull()?.order
                        val alightOrder = leg.intermediateStops.lastOrNull()?.order
                        if (boardOrder != null && alightOrder != null) {
                            boardedVehicleNo = transit.stopsRemaining(
                                route = leg.route,
                                boardOrder = boardOrder,
                                alightOrder = alightOrder,
                                vehicleNo = null,
                            ).getOrNull()?.vehicleNo
                        }
                        // 버스가 도착했으므로 폴링을 멈춘다. 계속 두면 8초마다 진동이
                        // 다시 울리고, 도착정보 API 할당량도 낭비된다.
                        cancel()
                    } else {
                        announceWaiting(leg, arrival)
                    }
                }
            }
        }
    }

    /**
     * 승차 구간: 실시간 차량 위치로 남은 정거장을 계산한다.
     *
     * 도착예정시간(arrtime)이 아니라 차량 순번(nodeord)을 쓰는 이유는,
     * 정체 구간에서 예정시간이 크게 흔들려도 정거장 수는 정확하기 때문이다.
     * 하차 알림이 한 정거장이라도 빗나가면 어르신은 낯선 곳에 내리게 된다.
     */
    private suspend fun trackRide(legIndex: Int, leg: JourneyLeg.Bus) {
        val boardOrder = leg.intermediateStops.firstOrNull()?.order ?: return
        val alightOrder = leg.intermediateStops.lastOrNull()?.order ?: return
        var announcedPrepare = false
        var announcedBell = false
        var unknownVehiclePolls = 0

        transit.progressStream(leg.route, boardOrder, alightOrder, boardedVehicleNo).collect { outcome ->
            if (outcome is Outcome.Failure) {
                _state.update { it.copy(failure = outcome.reason) }
                return@collect
            }
            val progress = (outcome as Outcome.Success).value
            if (progress == null) {
                // 조회는 됐는데 탄 차량을 못 찾았다. 한 번쯤은 응답에서 잠깐 빠질 수
                // 있으므로 바로 알리지 않고, 연달아 못 찾을 때만 안내한다.
                // 여기서 아무 말도 하지 않으면 화면은 "내릴 때가 되면 크게 알려드릴게요"
                // 를 띄운 채 멈춰 있고, 어르신은 오지 않을 알림을 믿고 앉아 있게 된다.
                unknownVehiclePolls++
                if (unknownVehiclePolls == UNKNOWN_VEHICLE_POLLS_BEFORE_NOTICE) {
                    _state.update {
                        it.copy(
                            failure = null,
                            stage = GuidanceStage.RidingUnknownVehicle(legIndex, leg.alightStop.name),
                        )
                    }
                    voice.speak(
                        "어느 버스인지 확인하지 못했어요. " +
                            "${leg.alightStop.name}에서 내리시면 돼요. " +
                            "기사님께 여쭤보셔도 좋아요.",
                        priority = Priority.INTERRUPT,
                        allowRepeat = true,
                    )
                }
                return@collect
            }
            // 다시 찾았다. 다음에 또 놓치면 처음부터 센다.
            unknownVehiclePolls = 0
            boardedVehicleNo = boardedVehicleNo ?: progress.vehicleNo

            val remaining = progress.stopsRemaining
            val nextStopName = leg.intermediateStops
                .firstOrNull { it.order == progress.currentOrder + 1 }?.name

            when {
                remaining <= 0 -> {
                    voice.speak(
                        "${leg.alightStop.name}입니다. 내리세요.",
                        priority = Priority.CRITICAL,
                        allowRepeat = true,
                    )
                    haptics.prepareToAlight()
                    confirmAlighted()
                }

                remaining <= BELL_STOPS -> {
                    _state.update { it.copy(stage = GuidanceStage.RingBell(legIndex)) }
                    if (!announcedBell) {
                        announcedBell = true
                        haptics.prepareToAlight()
                        voice.speak(
                            "벨을 누르세요. 다음 정류장에서 내립니다.",
                            priority = Priority.CRITICAL,
                            allowRepeat = true,
                        )
                    }
                }

                remaining <= PREPARE_STOPS -> {
                    _state.update { it.copy(stage = GuidanceStage.PrepareToAlight(legIndex, remaining)) }
                    if (!announcedPrepare) {
                        announcedPrepare = true
                        haptics.prepareToAlight()
                        voice.speak(
                            "곧 내립니다. 일어날 준비하세요.",
                            priority = Priority.CRITICAL,
                            allowRepeat = true,
                        )
                    }
                }

                else -> {
                    _state.update {
                        it.copy(stage = GuidanceStage.Riding(legIndex, remaining, nextStopName))
                    }
                    voice.speak("${VoiceGuide.stopCountToSpeech(remaining)} 남았어요.")
                }
            }
        }
    }

    /* ── 안내 문장 ───────────────────────────────────────────── */

    /*
     * 말투는 상황에 따라 둘로 나뉜다.
     *
     *  긴급 (벨·하차준비·하차) — 명령형 그대로.
     *      몇 초 안에 몸을 움직여야 하는 안내다. 부드럽게 돌려 말하면
     *      "지금 나한테 하라는 건가?" 를 판단하는 데 시간이 든다.
     *  나머지 (도보·대기·이동중·도착) — 해요체.
     *      옆에서 같이 가는 사람이 말하듯. 명령을 계속 받으면 지시받는 기분이 든다.
     *
     * 화면에 보이는 글자는 숫자를 그대로 쓰지만(잠금화면에서 눈으로 읽어야 한다),
     * 말로 나가는 수는 단위에 맞는 수사로 바꾼다. [VoiceGuide.stopCountToSpeech] 참고.
     */

    private fun transitionTo(stage: GuidanceStage) {
        _state.update { it.copy(stage = stage) }
        announceCurrentStage(force = false)
    }

    private fun announceWaiting(leg: JourneyLeg.Bus, arrival: BusArrival?) {
        if (arrival == null) return
        val minutes = arrival.arrivalMinutes
        if (minutes <= 0) return
        val lowFloor = if (arrival.isLowFloor) " 계단 없는 버스라 타기 편하실 거예요." else ""
        voice.speak(
            "${VoiceGuide.busNumberToSpeech(leg.route.routeNo)}번 버스가 ${minutes}분 뒤에 와요. " +
                "조금만 기다리시면 돼요.$lowFloor",
        )
    }

    private fun announceCurrentStage(force: Boolean) {
        val state = _state.value
        val journey = state.journey ?: return
        val text = spokenTextFor(journey, state.stage) ?: return
        voice.speak(
            text,
            priority = if (force) Priority.INTERRUPT else Priority.NORMAL,
            allowRepeat = force,
        )
    }

    private fun spokenTextFor(journey: Journey, stage: GuidanceStage): String? {
        val leg = journey.legs.getOrNull(stage.legIndex)
        return when (stage) {
            is GuidanceStage.Walking -> {
                val walk = leg as? JourneyLeg.Walk ?: return null
                // "쪽으로" 로 붙이는 것은 말투 때문만이 아니다. 이름 뒤에 바로 조사를 붙이면
                // 받침에 따라 으로/로 가 갈리는데("센터으로"), 정류장 이름은 전국에서 온다.
                "이제 ${walk.destinationName} 쪽으로, " +
                    "${VoiceGuide.distanceToSpeech(stage.remainingMeters)}쯤 걸어가시면 돼요."
            }
            is GuidanceStage.ArrivedAtStop ->
                "${(leg as? JourneyLeg.Walk)?.destinationName ?: "정류장"}에 다 왔어요. " +
                    "맞으면 네 여기예요를 눌러 주세요."
            is GuidanceStage.WaitingForBus -> null // announceWaiting 이 담당
            is GuidanceStage.Boarding -> {
                val bus = leg as? JourneyLeg.Bus ?: return null
                "${VoiceGuide.busNumberToSpeech(bus.route.routeNo)}번 버스에 타셨으면, 네 탔어요를 눌러 주세요."
            }
            is GuidanceStage.Riding -> "${VoiceGuide.stopCountToSpeech(stage.stopsRemaining)} 더 가면 내려요."
            // 정거장 수를 말하지 않는다. 셀 수 없게 된 것이 이 단계의 내용이다.
            is GuidanceStage.RidingUnknownVehicle ->
                "어느 버스인지 확인하지 못했어요. ${stage.alightStopName}에서 내리시면 돼요."
            // 아래 셋은 긴급 안내라 명령형 그대로 둔다.
            is GuidanceStage.PrepareToAlight -> "곧 내립니다. 일어날 준비하세요."
            is GuidanceStage.RingBell -> "벨을 누르세요. 다음 정류장에서 내립니다."
            GuidanceStage.Arrived -> "다 왔어요. ${journey.destination.name} 바로 앞이에요."
        }
    }

    private fun initialStage(journey: Journey) = stageFor(journey, 0)

    private fun stageFor(journey: Journey, legIndex: Int): GuidanceStage =
        when (val leg = journey.legs.getOrNull(legIndex)) {
            is JourneyLeg.Walk -> GuidanceStage.Walking(legIndex, leg.distanceMeters)
            is JourneyLeg.Bus -> GuidanceStage.WaitingForBus(legIndex, null)
            null -> GuidanceStage.Arrived
        }
}

/** UI 와 알림이 함께 보는 단일 상태. */
data class JourneyState(
    val journey: Journey? = null,
    val stage: GuidanceStage = GuidanceStage.Arrived,
    val currentLocation: LatLng? = null,
    val arrival: BusArrival? = null,
    val failure: kr.eodiga.wayfinder.core.FailureReason? = null,
    val isActive: Boolean = false,
)
