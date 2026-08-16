package kr.eodiga.wayfinder

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.guardian.GuardianNotifier
import kr.eodiga.wayfinder.location.LocationProvider
import kr.eodiga.wayfinder.location.RegionResolver
import kr.eodiga.wayfinder.service.JourneyController
import kr.eodiga.wayfinder.service.JourneyState
import kr.eodiga.wayfinder.ui.screens.GuidanceViewModel
import kr.eodiga.wayfinder.ui.screens.LostLocationUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `도움 요청은 안내 상태의 낡은 위치 대신 버튼 시점의 새 위치와 주소를 쓴다`() =
        runTest(dispatcher) {
            val stale = LatLng(35.0, 126.0)
            val fresh = LatLng(36.3315, 127.4342)
            val controller = mockk<JourneyController>()
            every { controller.state } returns MutableStateFlow(
                JourneyState(currentLocation = stale),
            )
            val location = mockk<LocationProvider>()
            coEvery { location.currentLocation() } returns fresh
            val regions = mockk<RegionResolver>()
            coEvery { regions.resolveAddress(fresh) } returns "대전 동구 중앙로 215"
            val notifier = mockk<GuardianNotifier>()
            coEvery { notifier.notifyLost(any(), any()) } returns GuardianNotifier.Result(
                smsStatus = GuardianNotifier.SmsStatus.SENT,
                callStarted = true,
                guardianName = "아들",
                locationIncluded = true,
            )
            val viewModel = GuidanceViewModel(
                context = mockk<Context>(relaxed = true),
                controller = controller,
                guardianNotifier = notifier,
                places = mockk<PlaceRepository>(),
                location = location,
                regions = regions,
            )

            viewModel.callForHelp()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                notifier.notifyLost(fresh, "대전 동구 중앙로 215")
            }
            assertEquals(
                LostLocationUiState.Ready(fresh, "대전 동구 중앙로 215"),
                viewModel.lostLocation.value,
            )
        }

    @Test
    fun `location failure still attempts guardian contact without claiming a location`() =
        runTest(dispatcher) {
            val controller = mockk<JourneyController>()
            every { controller.state } returns MutableStateFlow(JourneyState())
            val location = mockk<LocationProvider>()
            coEvery { location.currentLocation() } throws IllegalStateException("location failed")
            val regions = mockk<RegionResolver>()
            val notifier = mockk<GuardianNotifier>()
            coEvery { notifier.notifyLost(null, null) } returns GuardianNotifier.Result(
                smsStatus = GuardianNotifier.SmsStatus.FAILED,
                callStarted = true,
                guardianName = "보호자",
                locationIncluded = false,
            )
            val viewModel = GuidanceViewModel(
                context = mockk<Context>(relaxed = true),
                controller = controller,
                guardianNotifier = notifier,
                places = mockk<PlaceRepository>(),
                location = location,
                regions = regions,
            )

            viewModel.callForHelp()
            advanceUntilIdle()

            assertEquals(LostLocationUiState.Unavailable, viewModel.lostLocation.value)
            coVerify(exactly = 1) { notifier.notifyLost(null, null) }
            coVerify(exactly = 0) { regions.resolveAddress(any()) }
        }
}
