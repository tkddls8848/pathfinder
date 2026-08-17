package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import kr.eodiga.wayfinder.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.eodiga.wayfinder.BuildConfig
import kr.eodiga.wayfinder.ui.components.InfoCard
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.core.Outcome
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.PlaceKind
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
    /** 앞선 검색. 새 검색이 들어오면 취소한다. */
    private var searchJob: Job? = null

    fun search(query: String) {
        if (query.isBlank()) return
        _state.value = _state.value.copy(query = query, isSearching = true, failure = null)

        // 연달아 검색하면 늦게 시작한 것이 먼저 끝나 오래된 결과가 최신 결과를
        // 덮어쓸 수 있다. 앞선 검색은 버린다.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val here = location.currentLocation()
            val region = here?.let { regions.resolve(it) }

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

            /*
             * 결과가 없을 때 왜 없는지를 구분한다.
             *
             * 예전에는 전부 NoResult("지금은 알 수 있는 정보가 없습니다")로
             * 수렴해, 위치 권한이 꺼진 것인지·주소검색 키가 없는 것인지·정말
             * 그런 곳이 없는 것인지 보호자도 알 수 없었다. 앞의 둘은 설정으로
             * 고칠 수 있는 문제이므로 그렇게 말해준다.
             */
            val failure = when {
                combined.isNotEmpty() -> null
                hospital is Outcome.Failure -> hospital.reason
                address is Outcome.Failure -> address.reason
                here == null -> FailureReason.NoLocation
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

        // 에뮬레이터에는 마이크가 없어 음성 입력만으로는 이 화면을 넘어갈 수 없다.
        // 디버그 빌드에만 나오고, 릴리스 APK 에는 아예 컴파일되지 않는다.
        if (BuildConfig.DEBUG) {
            item {
                DeveloperSearchField(
                    onSearch = viewModel::search,
                    onPickSample = onPlaceSelected,
                )
            }
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

/**
 * 개발용 목적지 입력.
 *
 * 어르신에게 한글 키보드는 가장 큰 장벽이라 이 앱은 키보드 입력을 쓰지 않는다.
 * 그러나 에뮬레이터에는 마이크가 없어, 개발 중에는 음성 화면을 통과할 방법이 아예 없다.
 *
 * "넘기기" 버튼이 아니라 **입력칸**인 이유는, 건너뛰면 정작 확인해야 할 것이
 * 확인되지 않기 때문이다. 이 칸은 음성 결과와 똑같이 [SearchViewModel.search] 로
 * 들어가므로 공공데이터 조회·경로 탐색까지 실제 경로를 그대로 탄다.
 *
 * `BuildConfig.DEBUG` 가 상수라 릴리스 빌드에서는 호출부째로 제거된다.
 */
@Composable
private fun DeveloperSearchField(
    onSearch: (String) -> Unit,
    onPickSample: (Place) -> Unit,
) {
    var typed by remember { mutableStateOf("") }

    InfoCard(borderColor = EodigaColors.Muted) {
        Text(
            "개발용 입력 — 디버그 빌드에만 나옵니다",
            style = MaterialTheme.typography.bodyMedium,
            color = EodigaColors.Muted,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("예: 국립중앙의료원") },
            singleLine = true,
            textStyle = TextStyle(fontSize = 22.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(typed) }),
            modifier = Modifier.fillMaxWidth(),
        )
        SecondaryActionButton(text = "찾기", onClick = { onSearch(typed) })

        // 에뮬레이터는 기본 IME 가 영문뿐이라 한글을 치려면 언어를 따로 추가해야 한다.
        // 한 번에 누를 수 있는 표본을 둔다.
        Text(
            "검색을 건너뛰고 바로 목적지로",
            style = MaterialTheme.typography.bodyMedium,
            color = EodigaColors.Muted,
        )
        DEVELOPER_DESTINATIONS.forEach { place ->
            SecondaryActionButton(
                text = "${place.name} · ${place.address}",
                onClick = { onPickSample(place) },
            )
        }
    }
}

/**
 * 검색을 건너뛰고 바로 쓸 목적지.
 *
 * 검색이 막혀 있어서 둔 것이다. 병·의원 API 는 한글 필터를 넣으면 0건을 내고
 * (인코딩을 넷으로 바꿔 봐도 같다), 주소검색은 `JUSO_CONFIRM_KEY` 가 있어야 한다.
 * 그 둘이 풀리기 전까지 이 앱의 본체 — 정류소 탐색·경로 계산·하차 알림 — 을
 * 시험할 방법이 없다. 목적지만 넣어 주면 그 뒤는 전부 실제 공공데이터로 돈다.
 *
 * **좌표가 대전인 이유가 있다.** TAGO 정류소정보에 서울 데이터가 없다.
 * 같은 API 로 대전·부산·수원은 정류소가 나오는데 서울만 0건이다.
 * 서울은 자체 API 를 쓰는데 이 앱에는 붙어 있지 않다 — 4절 #15.
 */
private val DEVELOPER_DESTINATIONS = listOf(
    Place(
        id = "dev-daejeon-cityhall",
        name = "대전시청",
        address = "대전 서구 둔산로 100",
        location = LatLng(36.3504, 127.3845),
    ),
    Place(
        id = "dev-daejeon-station",
        name = "대전역",
        address = "대전 동구 중앙로 215",
        location = LatLng(36.3315, 127.4342),
    ),
    Place(
        id = "dev-chungnam-hospital",
        name = "충남대학교병원",
        address = "대전 중구 문화로 282",
        location = LatLng(36.3170, 127.4145),
        kind = PlaceKind.HOSPITAL,
    ),
)
