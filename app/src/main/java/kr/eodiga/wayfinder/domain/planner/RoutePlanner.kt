package kr.eodiga.wayfinder.domain.planner

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.repository.TransitRepository
import kr.eodiga.wayfinder.domain.model.BusRoute
import kr.eodiga.wayfinder.domain.model.BusStop
import kr.eodiga.wayfinder.domain.model.Geo
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.RouteStop
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 대중교통 경로 탐색.
 *
 * ## 왜 직접 구현하는가
 * 공공데이터포털에는 "출발지→목적지 환승 경로" 를 한 번에 주는 오픈API 가 없다.
 * 제공되는 것은 정류소·노선·도착·위치 네 가지 원본 데이터뿐이다.
 * 그래서 이 클래스가 그 원본을 조합해 경로를 만든다.
 *
 * ## 알고리즘
 *  1. 출발지·목적지 반경 [SEARCH_RADIUS_M] 안의 정류소를 각각 구한다.
 *  2. 각 정류소를 경유하는 노선 집합을 구한다.
 *  3. 출발쪽 노선 ∩ 도착쪽 노선 = 직통 후보.
 *  4. 노선별 경유 정류소 순번으로 진행 방향을 검증한다 (승차순번 < 하차순번).
 *     이 검증이 없으면 반대 방향 정류장으로 안내하게 된다. 어르신에게
 *     가장 치명적인 실패라 반드시 순번으로 확인한다.
 *  5. 직통이 없으면 1회 환승까지만 탐색한다.
 *  6. 도보 시간은 교통약자 보행속도 0.75 m/s 로 계산한다.
 *
 * ## 노인 사용자를 위한 가중치
 * 최단 시간이 아니라 **가장 쉬운 경로**를 1순위로 고른다.
 *  - 환승 1회는 도보 8분에 해당하는 페널티를 준다.
 *  - 승·하차 정류소까지의 도보 거리에 시간보다 큰 가중치를 준다.
 *  - 정거장 수가 많아도 환승 없는 쪽을 선호한다.
 */
@Singleton
class RoutePlanner @Inject constructor(
    private val transit: TransitRepository,
) {

    private companion object {
        /** 정류소 탐색 반경. 어르신 기준 이 이상 걷게 하면 안내가 무의미해진다. */
        const val SEARCH_RADIUS_M = 500.0

        /** 이 거리 안이면 버스를 타지 않고 걸어가는 편이 낫다. */
        const val WALK_ONLY_THRESHOLD_M = 700.0

        /** 도심 시내버스 정류장 간 평균 소요(분). 정차·신호 대기 포함. */
        const val MINUTES_PER_STOP = 2.0

        /** 환승 1회의 체감 비용(분). 계단·대기·불안을 시간으로 환산한 값. */
        const val TRANSFER_PENALTY_MIN = 8

        /** 도보 1분당 추가 체감 비용. 버스 안에 앉아 있는 1분보다 힘들다. */
        const val WALK_DISCOMFORT_FACTOR = 1.6

        const val MAX_ORIGIN_STOPS = 5
        const val MAX_DEST_STOPS = 5
        const val MAX_RESULTS = 3
    }

    /**
     * 경로를 찾아 쉬운 순으로 돌려준다.
     * 결과가 비어 있으면 도보만으로도 갈 수 없고 버스 연결도 없다는 뜻이다.
     */
    suspend fun plan(origin: LatLng, destination: Place): Outcome<List<Journey>> {
        val straightLine = Geo.distanceMeters(origin, destination.location)

        // 아주 가까우면 버스를 태우지 않는다.
        if (straightLine <= WALK_ONLY_THRESHOLD_M) {
            return Outcome.Success(listOf(walkOnlyJourney(origin, destination, straightLine)))
        }

        val originStops = transit.nearbyStops(origin).let {
            when (it) {
                is Outcome.Failure -> return it
                is Outcome.Success -> it.value.withinRadius(origin).take(MAX_ORIGIN_STOPS)
            }
        }
        val destStops = transit.nearbyStops(destination.location).let {
            when (it) {
                is Outcome.Failure -> return it
                is Outcome.Success -> it.value.withinRadius(destination.location).take(MAX_DEST_STOPS)
            }
        }

        if (originStops.isEmpty() || destStops.isEmpty()) {
            // 정류소가 없으면 걸어가는 안내라도 준다. 빈 화면보다 낫다.
            return Outcome.Success(listOf(walkOnlyJourney(origin, destination, straightLine)))
        }

        val originRoutes = collectRoutes(originStops)
        val destRoutes = collectRoutes(destStops)

        val direct = findDirect(origin, destination, originRoutes, destRoutes)
        val journeys = direct.ifEmpty {
            findOneTransfer(origin, destination, originRoutes, destRoutes)
        }

        val ranked = journeys
            .sortedBy { difficultyScore(it) }
            .distinctBy { j -> j.busLegs.map { it.route.routeId to it.boardStop.nodeId } }
            .take(MAX_RESULTS)

        return Outcome.Success(
            ranked.ifEmpty { listOf(walkOnlyJourney(origin, destination, straightLine)) },
        )
    }

    private fun List<BusStop>.withinRadius(center: LatLng) =
        filter { Geo.distanceMeters(center, it.location) <= SEARCH_RADIUS_M }

    /** 정류소별 경유 노선을 한꺼번에 모은다. 정류소 수가 적어 병렬 호출해도 안전하다. */
    private suspend fun collectRoutes(stops: List<BusStop>): List<StopWithRoutes> = coroutineScope {
        stops.map { stop ->
            async {
                val routes = transit.routesThroughStop(stop).getOrNull().orEmpty()
                StopWithRoutes(stop, routes)
            }
        }.map { it.await() }.filter { it.routes.isNotEmpty() }
    }

    /* ── 직통 ────────────────────────────────────────────────── */

    private suspend fun findDirect(
        origin: LatLng,
        destination: Place,
        originSide: List<StopWithRoutes>,
        destSide: List<StopWithRoutes>,
    ): List<Journey> {
        val destRouteIndex: Map<String, MutableList<BusStop>> = buildMap {
            destSide.forEach { (stop, routes) ->
                routes.forEach { r -> getOrPut(r.routeId) { mutableListOf() }.add(stop) }
            }
        }

        val results = mutableListOf<Journey>()
        for ((boardStop, routes) in originSide) {
            for (route in routes) {
                val alightCandidates = destRouteIndex[route.routeId] ?: continue
                val routeStops = transit.stopsOnRoute(route).getOrNull() ?: continue
                val orders = routeStops.associateBy { it.nodeId }

                val boardOrder = orders[boardStop.nodeId]?.order ?: continue
                for (alightStop in alightCandidates) {
                    val alightOrder = orders[alightStop.nodeId]?.order ?: continue
                    // 진행 방향 검증: 반대편 정류장이면 버린다.
                    if (alightOrder <= boardOrder) continue

                    results += buildJourney(
                        origin = origin,
                        destination = destination,
                        legsSpec = listOf(
                            BusLegSpec(route, boardStop, alightStop, routeStops, boardOrder, alightOrder),
                        ),
                    )
                }
            }
        }
        return results
    }

    /* ── 1회 환승 ────────────────────────────────────────────── */

    private suspend fun findOneTransfer(
        origin: LatLng,
        destination: Place,
        originSide: List<StopWithRoutes>,
        destSide: List<StopWithRoutes>,
    ): List<Journey> = coroutineScope {
        // 양쪽 노선의 정류소 목록을 먼저 모두 가져온다(캐시가 대부분 흡수한다).
        val firstLegRoutes = originSide.flatMap { s -> s.routes.map { s.stop to it } }.distinctBy { it.second.routeId to it.first.nodeId }
        val secondLegRoutes = destSide.flatMap { s -> s.routes.map { s.stop to it } }.distinctBy { it.second.routeId to it.first.nodeId }

        val stopsByRoute = (firstLegRoutes + secondLegRoutes)
            .map { it.second }
            .distinctBy { it.routeId }
            .map { route -> async { route.routeId to transit.stopsOnRoute(route).getOrNull().orEmpty() } }
            .associate { it.await() }

        val results = mutableListOf<Journey>()

        for ((boardStop, routeA) in firstLegRoutes) {
            val stopsA = stopsByRoute[routeA.routeId].orEmpty()
            if (stopsA.isEmpty()) continue
            val boardOrder = stopsA.firstOrNull { it.nodeId == boardStop.nodeId }?.order ?: continue
            val downstreamA = stopsA.filter { it.order > boardOrder }
            if (downstreamA.isEmpty()) continue
            val downstreamAIds = downstreamA.associateBy { it.nodeId }

            for ((alightStop, routeB) in secondLegRoutes) {
                if (routeB.routeId == routeA.routeId) continue
                val stopsB = stopsByRoute[routeB.routeId].orEmpty()
                if (stopsB.isEmpty()) continue
                val alightOrder = stopsB.firstOrNull { it.nodeId == alightStop.nodeId }?.order ?: continue

                // 환승 가능한 공통 정류소 중 가장 이른 것을 고른다.
                val transferOnB = stopsB
                    .asSequence()
                    .filter { it.order < alightOrder && downstreamAIds.containsKey(it.nodeId) }
                    .minByOrNull { it.order } ?: continue
                val transferOnA = downstreamAIds[transferOnB.nodeId] ?: continue

                val transferStop = BusStop(
                    cityCode = routeA.cityCode,
                    nodeId = transferOnA.nodeId,
                    name = transferOnA.name,
                    location = transferOnA.location,
                )

                results += buildJourney(
                    origin = origin,
                    destination = destination,
                    legsSpec = listOf(
                        BusLegSpec(routeA, boardStop, transferStop, stopsA, boardOrder, transferOnA.order),
                        BusLegSpec(routeB, transferStop, alightStop, stopsB, transferOnB.order, alightOrder),
                    ),
                )
                if (results.size >= 12) return@coroutineScope results
            }
        }
        results
    }

    /* ── 조립 ────────────────────────────────────────────────── */

    private fun buildJourney(
        origin: LatLng,
        destination: Place,
        legsSpec: List<BusLegSpec>,
    ): Journey {
        val legs = mutableListOf<JourneyLeg>()
        var cursor = origin

        legsSpec.forEachIndexed { index, spec ->
            val walkMeters = Geo.distanceMeters(cursor, spec.boardStop.location)
            // 환승 구간에서 같은 정류소면 도보 구간을 만들지 않는다.
            if (index == 0 || walkMeters > 20) {
                legs += JourneyLeg.Walk(
                    to = spec.boardStop.location,
                    distanceMeters = walkMeters.toInt(),
                    destinationName = spec.boardStop.name,
                    durationMinutes = Geo.walkMinutes(walkMeters),
                )
            }
            val stopCount = spec.alightOrder - spec.boardOrder
            legs += JourneyLeg.Bus(
                route = spec.route,
                boardStop = spec.boardStop,
                alightStop = spec.alightStop,
                stopCount = stopCount,
                intermediateStops = spec.routeStops.filter { it.order in spec.boardOrder..spec.alightOrder },
                durationMinutes = (stopCount * MINUTES_PER_STOP).toInt().coerceAtLeast(1),
            )
            cursor = spec.alightStop.location
        }

        val finalWalk = Geo.distanceMeters(cursor, destination.location)
        legs += JourneyLeg.Walk(
            to = destination.location,
            distanceMeters = finalWalk.toInt(),
            destinationName = destination.name,
            durationMinutes = Geo.walkMinutes(finalWalk),
        )

        return Journey(destination = destination, legs = legs)
    }

    private fun walkOnlyJourney(origin: LatLng, destination: Place, meters: Double) = Journey(
        destination = destination,
        legs = listOf(
            JourneyLeg.Walk(
                to = destination.location,
                distanceMeters = meters.toInt(),
                destinationName = destination.name,
                durationMinutes = Geo.walkMinutes(meters),
            ),
        ),
    )

    /**
     * 낮을수록 어르신에게 쉬운 경로.
     * 순수 소요시간이 아니라 도보와 환승에 가중치를 준 "체감 난이도" 다.
     */
    internal fun difficultyScore(journey: Journey): Double {
        val walkMinutes = journey.legs.filterIsInstance<JourneyLeg.Walk>().sumOf { it.durationMinutes }
        val busMinutes = journey.legs.filterIsInstance<JourneyLeg.Bus>().sumOf { it.durationMinutes }
        return walkMinutes * WALK_DISCOMFORT_FACTOR +
            busMinutes +
            journey.transferCount * TRANSFER_PENALTY_MIN
    }

    private data class StopWithRoutes(val stop: BusStop, val routes: List<BusRoute>)

    private data class BusLegSpec(
        val route: BusRoute,
        val boardStop: BusStop,
        val alightStop: BusStop,
        val routeStops: List<RouteStop>,
        val boardOrder: Int,
        val alightOrder: Int,
    )
}
