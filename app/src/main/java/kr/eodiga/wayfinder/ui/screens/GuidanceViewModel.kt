package kr.eodiga.wayfinder.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.guardian.GuardianNotifier
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
) : ViewModel() {

    val state: StateFlow<JourneyState> = controller.state

    private val _lostResult = MutableStateFlow<GuardianNotifier.Result?>(null)
    val lostResult: StateFlow<GuardianNotifier.Result?> = _lostResult.asStateFlow()

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

    /** "길을 잃었어요" — 보호자에게 위치 문자 + 전화. */
    fun callForHelp() {
        viewModelScope.launch {
            val here = controller.state.value.currentLocation
            _lostResult.value = guardianNotifier.notifyLost(here, addressText = null)
        }
    }

    fun clearLostResult() {
        _lostResult.value = null
    }
}
