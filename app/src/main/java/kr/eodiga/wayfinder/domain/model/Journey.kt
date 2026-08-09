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
    data class PrepareToAlight(override val legIndex: Int, val stopsRemaining: Int) : GuidanceStage
    data class RingBell(override val legIndex: Int) : GuidanceStage
    data object Arrived : GuidanceStage {
        override val legIndex: Int get() = -1
    }
}
