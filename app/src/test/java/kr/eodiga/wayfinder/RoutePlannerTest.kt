package kr.eodiga.wayfinder

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.repository.TransitRepository
import kr.eodiga.wayfinder.domain.model.BusRoute
import kr.eodiga.wayfinder.domain.model.BusStop
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.RouteStop
import kr.eodiga.wayfinder.domain.planner.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {

    private val transit = mockk<TransitRepository>(relaxed = true)
    private val planner = RoutePlanner(transit)

    private val origin = LatLng(37.5000, 127.0000)
    private val destination = Place(
        id = "dest",
        name = "성모의원",
        location = LatLng(37.5300, 127.0000), // 약 3.3km — 도보 임계값을 넘긴다
    )

    private fun stop(id: String, name: String, lat: Double, lng: Double) = BusStop(
        cityCode = 25, nodeId = id, name = name, location = LatLng(lat, lng),
    )

    private fun routeStop(order: Int, stop: BusStop) = RouteStop(
        order = order, nodeId = stop.nodeId, name = stop.name, location = stop.location,
    )

    @Test
    fun `가까운 목적지는 버스를 태우지 않고 걸어가게 한다`() = runTest {
        val near = destination.copy(location = LatLng(37.5030, 127.0000)) // 약 330m

        val result = planner.plan(origin, near)

        val journeys = (result as Outcome.Success).value
        assertEquals(1, journeys.size)
        assertTrue(journeys[0].legs.all { it is JourneyLeg.Walk })
    }

    /**
     * 반대 방향 정류장으로 안내하는 것은 이 앱에서 가장 치명적인 실패다.
     * 승차 순번이 하차 순번보다 뒤인 노선은 후보에서 빠져야 한다.
     */
    @Test
    fun `진행 방향이 반대인 노선은 경로로 제안하지 않는다`() = runTest {
        val boardStop = stop("A", "출발정류장", 37.5005, 127.0000)
        val alightStop = stop("B", "도착정류장", 37.5295, 127.0000)
        val route = BusRoute(cityCode = 25, routeId = "R1", routeNo = "7016")

        coEvery { transit.nearbyStops(match { it.lat < 37.51 }, any()) } returns
            Outcome.Success(listOf(boardStop))
        coEvery { transit.nearbyStops(match { it.lat > 37.51 }, any()) } returns
            Outcome.Success(listOf(alightStop))
        coEvery { transit.routesThroughStop(any()) } returns Outcome.Success(listOf(route))

        // 하차 정류장(순번 3)이 승차 정류장(순번 9)보다 앞에 있다 = 반대 방향
        coEvery { transit.stopsOnRoute(route) } returns Outcome.Success(
            listOf(routeStop(3, alightStop), routeStop(9, boardStop)),
        )

        val journeys = (planner.plan(origin, destination) as Outcome.Success).value

        // 유효한 버스 경로가 없으므로 도보 안내로 대체된다.
        assertTrue(
            "반대 방향 노선이 제안되었다",
            journeys.all { j -> j.legs.none { it is JourneyLeg.Bus } },
        )
    }

    @Test
    fun `진행 방향이 맞는 직통 노선은 경로로 제안된다`() = runTest {
        val boardStop = stop("A", "출발정류장", 37.5005, 127.0000)
        val alightStop = stop("B", "도착정류장", 37.5295, 127.0000)
        val route = BusRoute(cityCode = 25, routeId = "R1", routeNo = "7016")

        coEvery { transit.nearbyStops(match { it.lat < 37.51 }, any()) } returns
            Outcome.Success(listOf(boardStop))
        coEvery { transit.nearbyStops(match { it.lat > 37.51 }, any()) } returns
            Outcome.Success(listOf(alightStop))
        coEvery { transit.routesThroughStop(any()) } returns Outcome.Success(listOf(route))
        coEvery { transit.stopsOnRoute(route) } returns Outcome.Success(
            listOf(routeStop(3, boardStop), routeStop(7, alightStop)),
        )

        val journeys = (planner.plan(origin, destination) as Outcome.Success).value
        val bus = journeys.first().busLegs.first()

        assertEquals("7016", bus.route.routeNo)
        assertEquals(4, bus.stopCount)
        assertEquals("도착정류장", bus.alightStop.name)
    }

    /** 어르신에게는 조금 오래 걸려도 환승 없는 쪽이 낫다. */
    @Test
    fun `환승이 있는 경로는 더 어려운 것으로 평가된다`() {
        val direct = journeyOf(walkMinutes = 5, busLegs = listOf(20))
        val withTransfer = journeyOf(walkMinutes = 5, busLegs = listOf(8, 8))

        assertTrue(planner.difficultyScore(direct) < planner.difficultyScore(withTransfer))
    }

    /** 도보 1분은 버스 안 1분보다 힘들다. */
    @Test
    fun `도보가 긴 경로는 같은 총시간이어도 더 어렵게 평가된다`() {
        val moreWalking = journeyOf(walkMinutes = 15, busLegs = listOf(10))
        val lessWalking = journeyOf(walkMinutes = 5, busLegs = listOf(20))

        assertTrue(planner.difficultyScore(moreWalking) > planner.difficultyScore(lessWalking))
    }

    private fun journeyOf(walkMinutes: Int, busLegs: List<Int>): Journey {
        val legs = mutableListOf<JourneyLeg>()
        legs += JourneyLeg.Walk(origin, 100, "정류장", walkMinutes)
        busLegs.forEach { minutes ->
            legs += JourneyLeg.Bus(
                route = BusRoute(25, "R", "1"),
                boardStop = stop("A", "a", 0.0, 0.0),
                alightStop = stop("B", "b", 0.0, 0.0),
                stopCount = minutes / 2,
                intermediateStops = emptyList(),
                durationMinutes = minutes,
            )
        }
        return Journey(destination = destination, legs = legs)
    }
}
