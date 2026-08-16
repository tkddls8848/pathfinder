package kr.eodiga.wayfinder

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.repository.BusProgress
import kr.eodiga.wayfinder.data.repository.TransitRepository
import kr.eodiga.wayfinder.domain.model.BusArrival
import kr.eodiga.wayfinder.domain.model.BusRoute
import kr.eodiga.wayfinder.domain.model.BusStop
import kr.eodiga.wayfinder.domain.model.GuidanceStage
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.RouteStop
import kr.eodiga.wayfinder.location.LocationProvider
import kr.eodiga.wayfinder.service.Haptics
import kr.eodiga.wayfinder.service.JourneyController
import kr.eodiga.wayfinder.service.JourneyState
import kr.eodiga.wayfinder.service.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내 상태 기계의 회귀 테스트.
 *
 * 두 가지를 고정한다.
 *  1. 도착 임박 안내는 차량 특정 네트워크 호출을 기다리지 않는다.
 *     버스는 60초 안에 오는데 그 호출은 재시도까지 겹치면 수십 초가 걸릴 수 있다.
 *  2. 탄 차량을 못 찾으면 그 사실을 말한다. 조용히 멈춰 있으면 어르신은
 *     오지 않을 하차 알림을 믿고 앉아 있게 된다.
 *
 * [JourneyController] 는 자체 스코프를 Dispatchers.Default 로 들고 있어
 * 가상 시간으로 몰 수 없다. 그래서 runTest 가 아니라 실제 시간으로 돌리고,
 * 잠들어 기다리는 대신 상태가 바뀌기를 기다린다.
 */
class JourneyControllerTest {

    private val transit = mockk<TransitRepository>()
    private val location = mockk<LocationProvider>()
    private val voice = mockk<VoiceGuide>(relaxed = true)
    private val haptics = mockk<Haptics>(relaxed = true)

    private val route = BusRoute(cityCode = 25, routeId = "R1", routeNo = "606")
    private val boardStop = BusStop(25, "A", "출발정류장", LatLng(36.33, 127.43))
    private val alightStop = BusStop(25, "B", "충남대병원앞", LatLng(36.35, 127.41))

    private fun journey(): Journey {
        val busLeg = JourneyLeg.Bus(
            route = route,
            boardStop = boardStop,
            alightStop = alightStop,
            stopCount = 10,
            // 승차 10번, 하차 20번 순번.
            intermediateStops = (10..20).map {
                RouteStop(order = it, nodeId = "n$it", name = "정류장$it", location = LatLng(36.34, 127.42))
            },
            durationMinutes = 20,
        )
        return Journey(
            destination = Place(id = "dest", name = "충남대학교병원", location = alightStop.location),
            legs = listOf(
                JourneyLeg.Walk(boardStop.location, 100, "출발정류장", 2),
                busLeg,
                JourneyLeg.Walk(alightStop.location, 80, "충남대학교병원", 2),
            ),
        )
    }

    /** 도보 구간은 이 테스트의 관심사가 아니므로 위치는 흐르지 않게 둔다. */
    private fun stubIdleLocation() {
        every { location.locationStream(any()) } returns emptyFlow()
    }

    private fun awaitStage(
        controller: JourneyController,
        predicate: (GuidanceStage) -> Boolean,
    ): JourneyState = runBlocking {
        withTimeout(AWAIT_MS) { controller.state.first { predicate(it.stage) } }
    }

    @Test
    fun `도착 임박 안내는 차량 특정이 끝나기 전에 나간다`() {
        stubIdleLocation()
        every { transit.arrivalStream(any(), any()) } returns
            flowOf(Outcome.Success(BusArrival(arrivalSeconds = 30)))

        // 위치 API 가 응답하지 않는 상황. 예전 순서에서는 여기서 안내가 통째로 막혔다.
        val neverAnswers = CompletableDeferred<Outcome<BusProgress?>>()
        coEvery { transit.stopsRemaining(any(), any(), any(), any()) } coAnswers { neverAnswers.await() }

        val controller = JourneyController(transit, location, voice, haptics)
        controller.start(journey())
        controller.confirmAtStop()

        val state = awaitStage(controller) { it is GuidanceStage.Boarding }

        assertEquals(1, state.stage.legIndex)
        verify(timeout = AWAIT_MS) { haptics.busApproaching() }
        verify(timeout = AWAIT_MS) {
            voice.speak(match { it.contains("손을 드세요") }, any(), any())
        }
        assertTrue("차량 특정은 아직 끝나지 않아야 한다", neverAnswers.isActive)

        neverAnswers.complete(Outcome.Success(null))
        controller.stop()
    }

    @Test
    fun `탄 차량을 연달아 못 찾으면 안내를 줄 수 없다고 알린다`() {
        val controller = ridingController(
            flow {
                emit(Outcome.Success(null))
                emit(Outcome.Success(null))
            },
        )

        val stage = awaitStage(controller) { it is GuidanceStage.RidingUnknownVehicle }
            .stage as GuidanceStage.RidingUnknownVehicle

        assertEquals("충남대병원앞", stage.alightStopName)
        assertEquals(1, stage.legIndex)
        verify(timeout = AWAIT_MS) {
            voice.speak(
                match { it.contains("확인하지 못했어요") && it.contains("충남대병원앞") },
                any(),
                any(),
            )
        }
        controller.stop()
    }

    @Test
    fun `한 번 놓친 것만으로는 알리지 않는다`() {
        // 응답에서 한 번 빠졌다가 곧바로 다시 잡히는 경우. 놀라게 할 이유가 없다.
        val controller = ridingController(
            flow {
                emit(Outcome.Success(null))
                emit(Outcome.Success(BusProgress(vehicleNo = "V1", currentOrder = 14, stopsRemaining = 6)))
            },
        )

        val state = awaitStage(controller) { it is GuidanceStage.Riding && it.stopsRemaining == 6 }

        assertTrue(state.stage is GuidanceStage.Riding)
        verify(exactly = 0) {
            voice.speak(match { it.contains("확인하지 못했어요") }, any(), any())
        }
        controller.stop()
    }

    @Test
    fun `차량을 다시 찾으면 정상 안내로 돌아온다`() {
        // StateFlow 는 값을 합쳐버리므로, 지연 없이 연달아 내보내면 중간 상태를
        // 관찰하기 전에 지나간다. 확인이 끝날 때까지 마지막 방출을 붙잡아 둔다.
        val recovery = CompletableDeferred<Unit>()
        val controller = ridingController(
            flow {
                emit(Outcome.Success(null))
                emit(Outcome.Success(null))
                recovery.await()
                emit(Outcome.Success(BusProgress(vehicleNo = "V1", currentOrder = 13, stopsRemaining = 7)))
            },
        )

        awaitStage(controller) { it is GuidanceStage.RidingUnknownVehicle }
        recovery.complete(Unit)

        // 승차 직후의 Riding(10) 과 구분되도록 남은 정거장까지 확인한다.
        val recovered = awaitStage(controller) {
            it is GuidanceStage.Riding && it.stopsRemaining == 7
        }

        assertTrue(recovered.stage is GuidanceStage.Riding)
        controller.stop()
    }

    /** 승차 확인까지 마쳐 하차 추적이 돌고 있는 컨트롤러. */
    private fun ridingController(progress: Flow<Outcome<BusProgress?>>): JourneyController {
        stubIdleLocation()
        every { transit.arrivalStream(any(), any()) } returns emptyFlow()
        every { transit.progressStream(any(), any(), any(), any(), any()) } returns progress

        return JourneyController(transit, location, voice, haptics).apply {
            start(journey())
            confirmAtStop()
            confirmBoarded()
        }
    }

    private companion object {
        /** 스레드 전환만 기다리면 되므로 넉넉하되 짧게. */
        const val AWAIT_MS = 5_000L
    }
}
