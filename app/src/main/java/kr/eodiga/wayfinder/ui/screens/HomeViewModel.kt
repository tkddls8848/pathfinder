package kr.eodiga.wayfinder.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.remote.ServiceKeyProvider
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.data.repository.WeatherRepository
import kr.eodiga.wayfinder.data.repository.WeatherWarning
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.planner.RoutePlanner
import kr.eodiga.wayfinder.location.LocationProvider
import javax.inject.Inject

data class HomeUiState(
    val pinned: List<Place> = emptyList(),
    val recent: List<Place> = emptyList(),
    val isConfigured: Boolean = true,
    val hasLocationPermission: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val places: PlaceRepository,
    private val location: LocationProvider,
    keys: ServiceKeyProvider,
) : ViewModel() {

    private val permission = MutableStateFlow(location.hasPermission())

    val uiState: StateFlow<HomeUiState> = combine(
        places.pinnedPlaces(),
        places.recentPlaces(),
        permission,
    ) { pinned, recent, hasPermission ->
        HomeUiState(
            pinned = pinned,
            recent = recent.filter { r -> pinned.none { it.id == r.id } },
            isConfigured = keys.hasServiceKey,
            hasLocationPermission = hasPermission,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isConfigured = keys.hasServiceKey),
    )

}

/* ──────────────────────────────────────────────────────────── */

sealed interface RoutePlanUiState {
    data object Idle : RoutePlanUiState
    data object Loading : RoutePlanUiState
    data class Ready(
        val journeys: List<Journey>,
        val selectedIndex: Int = 0,
        val weather: WeatherWarning? = null,
    ) : RoutePlanUiState {
        val selected: Journey get() = journeys[selectedIndex]
    }
    data class Failed(val reason: FailureReason) : RoutePlanUiState
}

@HiltViewModel
class RoutePlanViewModel @Inject constructor(
    private val planner: RoutePlanner,
    private val location: LocationProvider,
    private val places: PlaceRepository,
    private val weather: WeatherRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RoutePlanUiState>(RoutePlanUiState.Idle)
    val state: StateFlow<RoutePlanUiState> = _state.asStateFlow()

    private var destination: Place? = null

    fun plan(place: Place) {
        destination = place
        _state.value = RoutePlanUiState.Loading
        viewModelScope.launch {
            val origin = location.currentLocation()
            if (origin == null) {
                // 출발지를 못 잡은 것이지 경로가 없는 것이 아니다.
                // NoResult 로 말하면 보호자가 위치 설정을 볼 이유를 못 찾는다.
                _state.value = RoutePlanUiState.Failed(FailureReason.NoLocation)
                return@launch
            }
            when (val result = planner.plan(origin, place)) {
                is Outcome.Failure -> _state.value = RoutePlanUiState.Failed(result.reason)
                is Outcome.Success -> {
                    _state.value = if (result.value.isEmpty()) {
                        RoutePlanUiState.Failed(FailureReason.NoResult)
                    } else {
                        RoutePlanUiState.Ready(result.value)
                    }
                    // 날씨는 경로보다 부수적이다. 실패해도 경로 안내를 막지 않는다.
                    val warning = weather.warningFor(origin).getOrNull()
                    (_state.value as? RoutePlanUiState.Ready)?.let { ready ->
                        _state.value = ready.copy(weather = warning)
                    }
                }
            }
        }
    }

    fun retry() {
        destination?.let { plan(it) }
    }

    fun selectAlternative(index: Int) {
        val current = _state.value as? RoutePlanUiState.Ready ?: return
        if (index in current.journeys.indices) {
            _state.value = current.copy(selectedIndex = index)
        }
    }

    fun rememberDestination(place: Place) {
        viewModelScope.launch { places.recordVisit(place) }
    }
}
