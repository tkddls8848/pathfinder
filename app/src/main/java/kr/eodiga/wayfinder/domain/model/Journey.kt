package kr.eodiga.wayfinder.domain.model

import java.util.UUID

/** 목적지. 즐겨찾기(집/병원/아들집)와 검색 결과를 같은 타입으로 다룬다. */
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String? = null,
    val location: LatLng,
    val kind: PlaceKind = PlaceKind.OTHER,
)

enum class PlaceKind { HOME, HOSPITAL, FAMILY, MART, PHARMACY, OTHER }

/** 여정 한 구간. 도보 또는 버스. */
sealed interface JourneyLeg {
    val durationMinutes: Int

    data class Walk(
        val to: LatLng,
        val distanceMeters: Int,
        val destinationName: String,
        override val durationMinutes: Int,
    ) : JourneyLeg {
        val steps: Int get() = Geo.steps(distanceMeters.toDouble())
    }

    data class Bus(
        val route: BusRoute,
        val boardStop: BusStop,
        val alightStop: BusStop,
        /** 승차 정류소 다음부터 하차 정류소까지의 정거장 수. */
        val stopCount: Int,
        /** 경유 정류소 전체. 하차 알림에서 "다음 정류장" 을 말해주는 데 쓴다. */
        val intermediateStops: List<RouteStop>,
        override val durationMinutes: Int,
    ) : JourneyLeg
}

/** 완성된 여정. */
data class Journey(
    val destination: Place,
    val legs: List<JourneyLeg>,
) {
    val totalMinutes: Int get() = legs.sumOf { it.durationMinutes }
    val transferCount: Int get() = (legs.count { it is JourneyLeg.Bus } - 1).coerceAtLeast(0)
    val busLegs: List<JourneyLeg.Bus> get() = legs.filterIsInstance<JourneyLeg.Bus>()
}

/**
 * 안내 진행 단계. 목업의 화면 순서를 상태 기계로 옮긴 것이다.
 * 화면이 아니라 "지금 어르신이 무엇을 하고 있는가" 를 나타낸다.
 */
sealed interface GuidanceStage {
    val legIndex: Int

    data class Walking(override val legIndex: Int, val remainingMeters: Int) : GuidanceStage
    data class ArrivedAtStop(override val legIndex: Int) : GuidanceStage
    data class WaitingForBus(override val legIndex: Int, val arrival: BusArrival?) : GuidanceStage
    data class Boarding(override val legIndex: Int) : GuidanceStage
    data class Riding(override val legIndex: Int, val stopsRemaining: Int, val nextStopName: String?) : GuidanceStage

    /**
     * 타고는 있으나 어느 차량인지 실시간 위치 데이터로 확정하지 못한 상태.
     *
     * 승차 지점 근처에 같은 노선 차량이 둘 이상이면 추적을 포기한다
     * ([kr.eodiga.wayfinder.data.repository.TransitRepository.stopsRemaining]).
     * 엉뚱한 차량을 따라가 한 정거장 일찍 내리게 하는 것보다 낫기 때문이다.
     *
     * 문제는 그 다음이다. 알림을 못 주게 됐다는 사실을 말하지 않으면
     * 어르신은 "내릴 때가 되면 알려준다"는 앞선 약속을 믿고 앉아 있는다.
     * 그래서 이 단계를 따로 두어, 안내를 줄 수 없다는 것과
     * 내릴 정류장 이름을 화면과 음성으로 분명히 남긴다.
     */
    data class RidingUnknownVehicle(
        override val legIndex: Int,
        val alightStopName: String,
    ) : GuidanceStage

    data class PrepareToAlight(override val legIndex: Int, val stopsRemaining: Int) : GuidanceStage
    data class RingBell(override val legIndex: Int) : GuidanceStage
    data object Arrived : GuidanceStage {
        override val legIndex: Int get() = -1
    }
}
