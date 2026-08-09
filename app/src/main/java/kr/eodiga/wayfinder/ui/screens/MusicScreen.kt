package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.data.repository.MusicLibrary
import kr.eodiga.wayfinder.service.MusicPlayer
import kr.eodiga.wayfinder.service.MusicState
import kr.eodiga.wayfinder.ui.components.InfoCard
import kr.eodiga.wayfinder.ui.components.LoadingState
import kr.eodiga.wayfinder.ui.components.PrimaryActionButton
import kr.eodiga.wayfinder.ui.components.ScreenTitle
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.components.Text
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens
import javax.inject.Inject

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val player: MusicPlayer,
    private val library: MusicLibrary,
) : ViewModel() {

    val state: StateFlow<MusicState> = player.state

    private val _hasPermission = MutableStateFlow(library.hasPermission)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    /**
     * 권한 대화상자에서 돌아왔을 때 다시 확인한다.
     *
     * 허락하고 돌아왔는데 여전히 "허락해 주세요" 가 떠 있으면 어르신은 자기가
     * 뭘 잘못 눌렀다고 생각한다. 화면이 다시 보일 때마다 재조회한다.
     */
    fun refresh() {
        val granted = library.hasPermission
        _hasPermission.value = granted
        if (granted) {
            viewModelScope.launch { player.load() }
        }
    }

    fun toggle() = player.toggle()
    fun next() = player.next()
    fun previous() = player.previous()
}

/**
 * 음악.
 *
 * 이동 중에 적적하지 않게 해달라는 요구에서 나왔다. 재생은 [MusicPlayer] 가
 * 싱글턴으로 들고 있으므로, 이 화면을 떠나 길안내로 넘어가도 노래는 계속 나온다.
 * 안내가 나갈 때 비켜주는 것은 오디오 포커스가 알아서 한다.
 *
 * 화면 규칙(`docs/accessibility.md`):
 *  - **주 동작은 하나.** 재생과 멈춤이 같은 자리의 같은 버튼이다.
 *    "재생" 과 "멈춤" 이 따로 있으면 지금 어느 쪽인지 판단해야 한다.
 *  - 진행 막대를 두지 않았다. 작은 손잡이를 끄는 조작은 손 떨림이 있으면 거의 안 된다.
 *  - 곡 목록을 나열하지 않았다. 선택지가 많으면 결정을 못 한다. 앞/뒤로만 넘긴다.
 */
@Composable
fun MusicScreen(
    onRequestMusicPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasPermission by viewModel.hasPermission.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(EodigaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        item { ScreenTitle("음악") }

        when {
            !hasPermission -> item {
                InfoCard(
                    background = EodigaColors.Warning,
                    borderColor = EodigaColors.WarningText,
                ) {
                    Text(
                        "휴대폰에 있는 노래를 찾아도 될까요?",
                        style = MaterialTheme.typography.titleLarge,
                        color = EodigaColors.WarningText,
                    )
                    Text(
                        "노래를 틀려면 허락이 필요해요. 다른 것은 보지 않아요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EodigaColors.WarningText,
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryActionButton(
                        text = "허락하기",
                        onClick = onRequestMusicPermission,
                    )
                }
            }

            !state.loaded -> item {
                LoadingState("노래를 찾고 있어요")
            }

            state.tracks.isEmpty() -> item {
                InfoCard {
                    Text("들을 노래가 없어요", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "휴대폰에 저장된 노래가 없습니다. 가족에게 노래를 넣어달라고 " +
                            "부탁해 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EodigaColors.Muted,
                    )
                }
            }

            else -> {
                item {
                    InfoCard(
                        borderColor = if (state.isPlaying) EodigaColors.Primary else EodigaColors.Muted,
                    ) {
                        Text(
                            if (state.isPlaying) "지금 나오는 노래" else "고른 노래",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EodigaColors.Muted,
                        )
                        Text(
                            state.current?.title.orEmpty(),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        state.current?.artist?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyLarge,
                                color = EodigaColors.Muted,
                            )
                        }
                    }
                }

                item {
                    // 주 동작 하나. 상태에 따라 글자와 아이콘이 같이 바뀐다 —
                    // 색이나 아이콘만 바뀌면 저대비 시력에서 구분되지 않는다.
                    PrimaryActionButton(
                        text = if (state.isPlaying) "잠깐 멈추기" else "노래 듣기",
                        leadingEmoji = if (state.isPlaying) "⏸️" else "▶️",
                        spokenLabel = if (state.isPlaying) {
                            "잠깐 멈추기. 누르면 노래가 멈춥니다."
                        } else {
                            "노래 듣기. 누르면 ${state.current?.label.orEmpty()} 을 틀어드려요."
                        },
                        onClick = viewModel::toggle,
                    )
                }

                item {
                    SecondaryActionButton(
                        text = "다음 노래",
                        spokenLabel = "다음 노래로 넘기기",
                        onClick = viewModel::next,
                    )
                }

                item {
                    SecondaryActionButton(
                        text = "앞 노래",
                        spokenLabel = "앞 노래로 되돌리기",
                        onClick = viewModel::previous,
                    )
                }

                item {
                    Text(
                        "길을 알려드릴 때는 노래가 잠깐 작아져요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EodigaColors.Muted,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            SecondaryActionButton(text = "뒤로", onClick = onBack)
        }
    }
}
