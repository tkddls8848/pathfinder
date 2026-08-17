package kr.eodiga.wayfinder

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.remote.ServiceKeyProvider
import kr.eodiga.wayfinder.data.remote.api.HospitalApi
import kr.eodiga.wayfinder.data.remote.api.JusoApi
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.location.LocationProvider
import kr.eodiga.wayfinder.location.Region
import kr.eodiga.wayfinder.location.RegionResolver
import kr.eodiga.wayfinder.ui.nav.NavigationStore
import kr.eodiga.wayfinder.ui.screens.SearchViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 결과가 없을 때 왜 없는지를 구분한다.
 *
 * 예전에는 위치 권한이 꺼진 것도, 주소검색 승인키가 없는 것도, 정말 그런 곳이
 * 없는 것도 전부 NoResult 로 수렴해 보호자가 원인을 찾을 수 없었다.
 * 앞의 둘은 설정으로 고칠 수 있는 문제다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFailureReasonTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val places = mockk<PlaceRepository>()
    private val location = mockk<LocationProvider>()
    private val regions = mockk<RegionResolver>()

    private fun viewModel(): SearchViewModel {
        every { places.recentPlaces() } returns emptyFlow()
        return SearchViewModel(places, location, regions, NavigationStore())
    }

    @Test
    fun `위치를 못 잡으면 위치 문제로 말한다`() = runTest(dispatcher) {
        coEvery { location.currentLocation() } returns null
        coEvery { places.searchAddress(any()) } returns Outcome.Success(emptyList())

        val model = viewModel()
        model.search("성모의원")
        advanceUntilIdle()

        assertEquals(FailureReason.NoLocation, model.state.value.failure)
    }

    @Test
    fun `주소검색 승인키가 없으면 설정 문제로 말한다`() = runTest(dispatcher) {
        val here = LatLng(36.33, 127.43)
        coEvery { location.currentLocation() } returns here
        coEvery { regions.resolve(here) } returns Region("대전광역시", "중구")
        coEvery { places.searchHospitals(any(), any(), any()) } returns Outcome.Success(emptyList())
        coEvery { places.searchAddress(any()) } returns Outcome.Failure(FailureReason.NotConfigured)

        val model = viewModel()
        model.search("없는곳")
        advanceUntilIdle()

        assertEquals(FailureReason.NotConfigured, model.state.value.failure)
    }

    @Test
    fun `위치도 키도 멀쩡한데 결과가 없으면 결과 없음이다`() = runTest(dispatcher) {
        val here = LatLng(36.33, 127.43)
        coEvery { location.currentLocation() } returns here
        coEvery { regions.resolve(here) } returns Region("대전광역시", "중구")
        coEvery { places.searchHospitals(any(), any(), any()) } returns Outcome.Success(emptyList())
        coEvery { places.searchAddress(any()) } returns Outcome.Success(emptyList())

        val model = viewModel()
        model.search("없는곳")
        advanceUntilIdle()

        assertEquals(FailureReason.NoResult, model.state.value.failure)
    }

    /** 승인키가 없을 때 빈 목록을 성공으로 돌려주면 원인이 지워진다. */
    @Test
    fun `승인키가 없으면 주소검색은 성공이 아니라 실패다`() = runTest {
        val keys = mockk<ServiceKeyProvider>()
        every { keys.jusoKey } returns ""
        val repository = PlaceRepository(
            savedPlaceDao = mockk(),
            guardianDao = mockk(),
            hospitalApi = mockk<HospitalApi>(),
            jusoApi = mockk<JusoApi>(),
            keys = keys,
        )

        val result = repository.searchAddress("대전시청")

        assertEquals(FailureReason.NotConfigured, (result as Outcome.Failure).reason)
    }
}
