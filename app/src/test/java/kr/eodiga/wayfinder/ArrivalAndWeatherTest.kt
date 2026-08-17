package kr.eodiga.wayfinder

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.remote.api.TagoBusArrivalApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusLocationApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusRouteApi
import kr.eodiga.wayfinder.data.remote.api.TagoBusStopApi
import kr.eodiga.wayfinder.data.remote.api.WeatherApi
import kr.eodiga.wayfinder.data.remote.dto.BusArrivalDto
import kr.eodiga.wayfinder.data.remote.dto.TagoBody
import kr.eodiga.wayfinder.data.remote.dto.TagoEnvelope
import kr.eodiga.wayfinder.data.remote.dto.TagoHeader
import kr.eodiga.wayfinder.data.remote.dto.TagoItems
import kr.eodiga.wayfinder.data.remote.dto.TagoResponse
import kr.eodiga.wayfinder.data.repository.TransitRepository
import kr.eodiga.wayfinder.data.repository.WeatherRepository
import kr.eodiga.wayfinder.domain.model.BusStop
import kr.eodiga.wayfinder.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ArrivalAndWeatherTest {

    private val arrivalApi = mockk<TagoBusArrivalApi>()
    private val repository = TransitRepository(
        stopApi = mockk<TagoBusStopApi>(),
        arrivalApi = arrivalApi,
        routeApi = mockk<TagoBusRouteApi>(),
        locationApi = mockk<TagoBusLocationApi>(),
    )
    private val stop = BusStop(25, "A", "출발정류장", LatLng(36.33, 127.43))

    /**
     * arrtime 이 없을 때 0 으로 채우면 isImminent 가 곧바로 참이 되어
     * "곧 와요, 손을 드세요" 가 나가버린다. 오지 않는 버스에 손을 들게 된다.
     */
    @Test
    fun `도착 시각이 없는 레코드는 곧 도착으로 보지 않는다`() = runTest {
        coEvery { arrivalApi.arrivalOfRoute(any(), any(), any(), any(), any()) } returns
            arrivals(BusArrivalDto(arrivalSeconds = null, vehicleType = "일반차량"))

        val arrival = (repository.arrivalOf(stop, "R1") as Outcome.Success).value

        assertNull("정보가 없으면 도착 정보 자체가 없어야 한다", arrival)
    }

    @Test
    fun `도착 시각이 있는 레코드만 남는다`() = runTest {
        coEvery { arrivalApi.arrivalOfRoute(any(), any(), any(), any(), any()) } returns
            arrivals(
                BusArrivalDto(arrivalSeconds = null),
                BusArrivalDto(arrivalSeconds = 420),
            )

        val arrival = (repository.arrivalOf(stop, "R1") as Outcome.Success).value

        assertEquals(420, arrival?.arrivalSeconds)
        assertFalse(arrival!!.isImminent)
    }

    /** 지나간 버스를 음수로 주는 지역이 있다. 0 으로 눌러 둔다. */
    @Test
    fun `음수 도착 시각은 0으로 눌러 둔다`() = runTest {
        coEvery { arrivalApi.arrivalOfRoute(any(), any(), any(), any(), any()) } returns
            arrivals(BusArrivalDto(arrivalSeconds = -30))

        val arrival = (repository.arrivalOf(stop, "R1") as Outcome.Success).value

        assertEquals(0, arrival?.arrivalSeconds)
    }

    /**
     * 기상청 발표 시각은 KST 기준이고, 정시 관측이 40분에 배포된다.
     * 40분 전이면 한 시간 전 자료를 물어야 NO_DATA 가 나지 않는다.
     */
    @Test
    fun `40분 전에는 한 시간 전 실황을 요청한다`() {
        val weather = WeatherRepository(mockk<WeatherApi>())

        val (date, time) = weather.latestNowcastBase(LocalDateTime.of(2026, 8, 17, 9, 20))

        assertEquals("20260817", date)
        assertEquals("0800", time)
    }

    @Test
    fun `40분이 지나면 그 시각 실황을 요청한다`() {
        val weather = WeatherRepository(mockk<WeatherApi>())

        val (date, time) = weather.latestNowcastBase(LocalDateTime.of(2026, 8, 17, 9, 40))

        assertEquals("20260817", date)
        assertEquals("0900", time)
    }

    /** 자정 직후는 날짜까지 하루 전으로 넘어가야 한다. */
    @Test
    fun `자정 직후에는 전날 마지막 실황을 요청한다`() {
        val weather = WeatherRepository(mockk<WeatherApi>())

        val (date, time) = weather.latestNowcastBase(LocalDateTime.of(2026, 8, 17, 0, 10))

        assertEquals("20260816", date)
        assertEquals("2300", time)
    }

    private fun arrivals(vararg items: BusArrivalDto) = TagoEnvelope(
        response = TagoResponse(
            header = TagoHeader(resultCode = "00", resultMsg = "OK"),
            body = TagoBody(items = TagoItems(items.toList())),
        ),
    )
}
