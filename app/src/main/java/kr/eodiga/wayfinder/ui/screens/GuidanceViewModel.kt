package kr.eodiga.wayfinder.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.guardian.GuardianNotifier
import kr.eodiga.wayfinder.location.LocationProvider
import kr.eodiga.wayfinder.location.RegionResolver
import kr.eodiga.wayfinder.service.JourneyController
import kr.eodiga.wayfinder.service.JourneyGuidanceService
import kr.eodiga.wayfinder.service.JourneyState
import javax.inject.Inject

@HiltViewModel
class GuidanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: JourneyController,
    private val guardianNotifier: GuardianNotifier,
    private val places: PlaceRepository,
    private val location: LocationProvider,
    private val regions: RegionResolver,
) : ViewModel() {

    val state: StateFlow<JourneyState> = controller.state

    private val _lostResult = MutableStateFlow<GuardianNotifier.Result?>(null)
    val lostResult: StateFlow<GuardianNotifier.Result?> = _lostResult.asStateFlow()

    private val _lostLocation = MutableStateFlow<LostLocationUiState>(LostLocationUiState.Loading)
    val lostLocation: StateFlow<LostLocationUiState> = _lostLocation.asStateFlow()

    private val _isContactingGuardian = MutableStateFlow(false)
    val isContactingGuardian: StateFlow<Boolean> = _isContactingGuardian.asStateFlow()

    private var locationRefreshInProgress = false

    // 여정 시작은 MainActivity 가 담당한다 (컨트롤러 시작 + 포그라운드 서비스 기동).
    // 여기서 또 시작할 수 있게 두면 두 경로가 생겨 중복 시작 버그를 부른다.

    fun stopJourney() {
        controller.stop()
        JourneyGuidanceService.stop(context)
    }

    fun confirmAtStop() = controller.confirmAtStop()

    fun confirmBoarded() = controller.confirmBoarded()

    fun cancelBoarding() = controller.cancelBoarding()

    fun confirmAlighted() = controller.confirmAlighted()

    fun repeatGuidance() = controller.repeatGuidance()

    fun finishAndRecord() {
        val destination = controller.state.value.journey?.destination
        viewModelScope.launch {
            destination?.let { places.recordVisit(it) }
        }
        stopJourney()
    }

    /** 길 잃음 화면에 들어올 때 안내 스트림의 캐시가 아닌 현재 위치를 새로 받는다. */
    fun refreshLostLocation() {
        if (locationRefreshInProgress) return
        locationRefreshInProgress = true
        _lostLocation.value = LostLocationUiState.Loading
        viewModelScope.launch {
            try {
                _lostLocation.value = acquireLostLocation()
            } finally {
                locationRefreshInProgress = false
            }
        }
    }

    /** "길을 잃었어요" — 버튼을 누른 순간 위치를 다시 취득해 문자 + 전화를 시도한다. */
    fun callForHelp() {
        if (_isContactingGuardian.value) return
        _isContactingGuardian.value = true
        _lostResult.value = null
        viewModelScope.launch {
            try {
                val resolved = acquireLostLocation()
                _lostLocation.value = resolved
                val ready = resolved as? LostLocationUiState.Ready
                _lostResult.value = guardianNotifier.notifyLost(
                    location = ready?.location,
                    addressText = ready?.addressText,
                )
            } finally {
                _isContactingGuardian.value = false
            }
        }
    }

    private suspend fun acquireLostLocation(): LostLocationUiState {
        val here = try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) { location.currentLocation() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return LostLocationUiState.Unavailable
        return LostLocationUiState.Ready(
            location = here,
            addressText = withTimeoutOrNull(ADDRESS_TIMEOUT_MS) {
                regions.resolveAddress(here)
            },
        )
    }

    fun clearLostResult() {
        _lostResult.value = null
    }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 5_000L
        const val ADDRESS_TIMEOUT_MS = 2_000L
    }
}

sealed interface LostLocationUiState {
    data object Loading : LostLocationUiState
    data class Ready(val location: LatLng, val addressText: String?) : LostLocationUiState
    data object Unavailable : LostLocationUiState
}
