package kr.eodiga.wayfinder

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.remote.api.TagoBusArrivalApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusLocationApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusRouteApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusStopApi
import kr.eodiga.wayfinder.data.remote.dto.BusLocationDto
import kr.eodiga.wayfinder.data.remote.dto.TagoBody
import kr.eodiga.wayfinder.data.remote.dto.TagoEnvelope
import kr.eodiga.wayfinder.data.remote.dto.TagoHeader
import kr.eodiga.wayfinder.data.remote.dto.TagoItems
import kr.eodiga.wayfinder.data.remote.dto.TagoResponse
import kr.eodiga.wayfinder.data.repository.BusProgress
import kr.eodiga.wayfinder.data.repository.TransitRepository
import kr.eodiga.wayfinder.domain.model.BusRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitRepositoryTest {

    private val locationApi = mockk<TagoBusLocationApi>()
    private val repository = TransitRepository(
        stopApi = mockk<TagoBusStopApi>(),
        arrivalApi = mockk<TagoBusArrivalApi>(),
        routeApi = mockk<TagoBusRouteApi>(),
        locationApi = locationApi,
    )
    private val route = BusRoute(cityCode = 25, routeId = "R1", routeNo = "606")

    @Test
    fun `차량번호가 있으면 더 앞선 다른 차량이 있어도 그 차량만 고른다`() = runTest {
        coEvery { locationApi.busesOnRoute(25, "R1") } returns locations(
            BusLocationDto(vehicleNo = "BOARDED", nodeOrder = 13),
            BusLocationDto(vehicleNo = "OTHER", nodeOrder = 19),
        )

        val progress = repository.stopsRemaining(
            route = route,
            boardOrder = 10,
            alightOrder = 20,
            vehicleNo = "BOARDED",
        ).value()

        assertEquals("BOARDED", progress?.vehicleNo)
        assertEquals(7, progress?.stopsRemaining)
    }

    @Test
    fun `추적 차량이 하차 정류소를 지났으면 다른 차량로 바꾸지 않는다`() = runTest {
        coEvery { locationApi.busesOnRoute(25, "R1") } returns locations(
            BusLocationDto(vehicleNo = "BOARDED", nodeOrder = 21),
            BusLocationDto(vehicleNo = "OTHER", nodeOrder = 18),
        )

        val progress = repository.stopsRemaining(
            route = route,
            boardOrder = 10,
            alightOrder = 20,
            vehicleNo = "BOARDED",
        ).value()

        assertNull(progress)
    }

    @Test
    fun `차량번호가 없을 때 승차 지점과 먼 차량은 선택하지 않는다`() = runTest {
        coEvery { locationApi.busesOnRoute(25, "R1") } returns locations(
            BusLocationDto(vehicleNo = "WRONG", nodeOrder = 18),
        )

        val progress = repository.stopsRemaining(
            route = route,
            boardOrder = 10,
            alightOrder = 20,
            vehicleNo = null,
        ).value()

        assertNull(progress)
    }

    @Test
    fun `승차 지점에 차량이 둘이면 임의로 하나를 추적하지 않는다`() = runTest {
        coEvery { locationApi.busesOnRoute(25, "R1") } returns locations(
            BusLocationDto(vehicleNo = "A", nodeOrder = 10),
            BusLocationDto(vehicleNo = "B", nodeOrder = 11),
        )

        val progress = repository.stopsRemaining(
            route = route,
            boardOrder = 10,
            alightOrder = 20,
            vehicleNo = null,
        ).value()

        assertNull(progress)
    }

    @Test
    fun `첫 폴링에서 고른 차량번호를 다음 폴링에도 고정한다`() = runTest {
        coEvery { locationApi.busesOnRoute(25, "R1") } returnsMany listOf(
            locations(BusLocationDto(vehicleNo = "BOARDED", nodeOrder = 10)),
            locations(
                BusLocationDto(vehicleNo = "BOARDED", nodeOrder = 15),
                BusLocationDto(vehicleNo = "OTHER", nodeOrder = 10),
            ),
        )

        val progress = repository.progressStream(
            route = route,
            boardOrder = 10,
            alightOrder = 20,
            vehicleNo = null,
            intervalMs = 0,
        ).take(2).toList().map { it.value() }

        assertEquals(listOf("BOARDED", "BOARDED"), progress.map { it?.vehicleNo })
        assertEquals(listOf(10, 15), progress.map { it?.currentOrder })
    }

    @Test
    fun `never adopts a later bus after initial match is ambiguous`() = runTest {
        coEvery { locationApi.busesOnRoute(25, "R1") } returnsMany listOf(
            locations(
                BusLocationDto(vehicleNo = "A", nodeOrder = 10),
                BusLocationDto(vehicleNo = "B", nodeOrder = 11),
            ),
            locations(BusLocationDto(vehicleNo = "B", nodeOrder = 11)),
        )

        val progress = repository.progressStream(
            route = route,
            boardOrder = 10,
            alightOrder = 20,
            vehicleNo = null,
            intervalMs = 0,
        ).take(2).toList().map { it.value() }

        assertEquals(listOf(null, null), progress)
        coVerify(exactly = 1) { locationApi.busesOnRoute(25, "R1") }
    }

    private fun locations(vararg buses: BusLocationDto) = TagoEnvelope(
        response = TagoResponse(
            header = TagoHeader(resultCode = "00", resultMsg = "OK"),
            body = TagoBody(items = TagoItems(buses.toList())),
        ),
    )

    private fun Outcome<BusProgress?>.value(): BusProgress? =
        (this as Outcome.Success).value
}
