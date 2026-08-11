package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.location.LocationProvider
import kr.eodiga.wayfinder.location.RegionResolver
import kr.eodiga.wayfinder.ui.nav.NavigationStore
import kr.eodiga.wayfinder.ui.components.DestinationButton
import kr.eodiga.wayfinder.ui.components.ErrorState
import kr.eodiga.wayfinder.ui.components.LoadingState
import kr.eodiga.wayfinder.ui.components.PrimaryActionButton
import kr.eodiga.wayfinder.ui.components.ScreenTitle
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val recent: List<Place> = emptyList(),
    val isSearching: Boolean = false,
    val failure: FailureReason? = null,
    val searched: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val places: PlaceRepository,
    private val location: LocationProvider,
    private val regions: RegionResolver,
    private val navigationStore: NavigationStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            places.recentPlaces().collect { recent ->
                _state.value = _state.value.copy(recent = recent)
            }
        }
        // 음성 인식 결과가 들어오면 곧바로 검색한다. 어르신에게 "검색" 버튼을 한 번 더
        // 누르게 하지 않는다.
        viewModelScope.launch {
            navigationStore.lastVoiceQuery.collect { spoken ->
                if (!spoken.isNullOrBlank()) {
                    navigationStore.consumeVoiceQuery()
                    search(spoken)
                }
            }
        }
    }

    /**
     * 목적지를 검색한다.
     *
     * "성모의원" 처럼 상호를 말하는 경우가 대부분이라, 병원 검색과 주소 검색을
     * 동시에 시도하고 결과를 합친다. 어르신에게 "병원을 찾을까요, 주소를 찾을까요"
     * 라고 되묻지 않는다.
     */
    fun search(query: String) {
        if (query.isBlank()) return
        _state.value = _state.value.copy(query = query, isSearching = true, failure = null)

        viewModelScope.launch {
            val region = location.currentLocation()?.let { regions.resolve(it) }

            val hospital = if (region != null) {
                places.searchHospitals(region.sido, region.sigungu, query)
            } else {
                Outcome.Success(emptyList())
            }
            val address = places.searchAddress(query)

            val combined = buildList {
                addAll(hospital.getOrNull().orEmpty())
                addAll(address.getOrNull().orEmpty())
            }.distinctBy { it.name to it.address }

            val failure = when {
                combined.isNotEmpty() -> null
                hospital is Outcome.Failure -> hospital.reason
                address is Outcome.Failure -> address.reason
                else -> FailureReason.NoResult
            }

            _state.value = _state.value.copy(
                results = combined,
                isSearching = false,
                failure = failure,
                searched = true,
            )
        }
    }

    fun clear() {
        _state.value = _state.value.copy(query = "", results = emptyList(), searched = false, failure = null)
    }
}

/**
 * ③ 다른 곳 찾기.
 *
 * 키보드 입력을 1순위로 두지 않는다. 어르신에게 한글 키보드는 가장 큰 장벽이다.
 * 음성 입력을 화면 절반 크기의 버튼으로 두고, 최근 목적지를 바로 아래 둔다.
 */
@Composable
fun SearchScreen(
    onPlaceSelected: (Place) -> Unit,
    onStartVoiceInput: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(EodigaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        item { ScreenTitle("어디에 가시나요?") }

        item {
            PrimaryActionButton(
                text = "🎤  누르고 말씀하세요",
                onClick = onStartVoiceInput,
                spokenLabel = "음성으로 목적지 말하기. 누르고 가고 싶은 곳을 말씀하세요.",
                modifier = Modifier.heightIn(min = 180.dp),
            )
        }

        if (state.query.isNotBlank()) {
            item {
                Text(
                    "\"${state.query}\" 찾은 결과",
                    style = MaterialTheme.typography.titleLarge,
                    color = EodigaColors.Muted,
                )
            }
        }

        when {
            state.isSearching -> item {
                LoadingState(message = "찾고 있습니다")
            }

            state.failure != null && state.results.isEmpty() -> item {
                ErrorState(
                    message = state.failure!!.spokenMessage,
                    onRetry = null,
                )
            }

            state.searched && state.results.isEmpty() -> item {
                ErrorState(message = "그런 곳을 찾지 못했습니다.\n다시 말씀해 주세요.", onRetry = null)
            }
        }

        items(state.results, key = { it.id }) { place ->
            DestinationButton(
                label = place.name,
                caption = place.address,
                emoji = place.kind.emoji(),
                onClick = { onPlaceSelected(place) },
            )
        }

        if (state.recent.isNotEmpty() && !state.searched) {
            item {
                Text(
                    "최근에 간 곳",
                    style = MaterialTheme.typography.titleLarge,
                    color = EodigaColors.Muted,
                )
            }
            items(state.recent, key = { "recent-${it.id}" }) { place ->
                DestinationButton(
                    label = place.name,
                    caption = place.address,
                    onClick = { onPlaceSelected(place) },
                )
            }
        }

        item { SecondaryActionButton(text = "뒤로", onClick = onBack) }
    }
}
