package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import kr.eodiga.wayfinder.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.PlaceKind
import kr.eodiga.wayfinder.ui.components.DestinationButton
import kr.eodiga.wayfinder.ui.components.InfoCard
import kr.eodiga.wayfinder.ui.components.ScreenTitle
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens

/**
 * 홈 — "어디로 가세요?"
 *
 * 목업의 고정 버튼(집/병원/아들집)을 저장된 장소로 바꿨다.
 * 등록된 장소가 없으면 빈 화면 대신 무엇을 해야 하는지 알려준다.
 */
@Composable
fun HomeScreen(
    onDestinationSelected: (Place) -> Unit,
    onSearchOther: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMusic: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(EodigaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        item { ScreenTitle("어디로 가세요?") }

        // 어르신이 스스로 해결할 수 없는 문제는 보호자에게 알리도록 안내한다.
        if (!state.isConfigured) {
            item {
                InfoCard(
                    background = EodigaColors.Warning,
                    borderColor = EodigaColors.WarningText,
                ) {
                    Text(
                        "앱 설정이 끝나지 않았습니다",
                        style = MaterialTheme.typography.titleLarge,
                        color = EodigaColors.WarningText,
                    )
                    Text(
                        "가족에게 이 화면을 보여주세요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = EodigaColors.WarningText,
                    )
                }
            }
        }

        if (!state.hasLocationPermission) {
            item {
                InfoCard(background = EodigaColors.Warning, borderColor = EodigaColors.WarningText) {
                    Text(
                        "지금 계신 곳을 알아야 길을 알려드릴 수 있어요",
                        style = MaterialTheme.typography.titleLarge,
                        color = EodigaColors.WarningText,
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryActionButton(
                        text = "위치 사용 허락하기",
                        onClick = onRequestLocationPermission,
                    )
                }
            }
        }

        items(state.pinned, key = { it.id }) { place ->
            DestinationButton(
                label = place.name,
                emoji = place.kind.emoji(),
                onClick = { onDestinationSelected(place) },
            )
        }

        if (state.pinned.isEmpty() && state.isConfigured) {
            item {
                InfoCard {
                    Text("자주 가는 곳을 먼저 등록해 주세요", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "집, 병원처럼 자주 가시는 곳을 등록하면 버튼 한 번으로 갈 수 있어요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EodigaColors.Muted,
                    )
                }
            }
        }

        item {
            DestinationButton(
                label = "다른 곳",
                emoji = "🔍",
                onClick = onSearchOther,
            )
        }

        if (state.recent.isNotEmpty()) {
            item {
                Text(
                    "최근에 간 곳",
                    style = MaterialTheme.typography.titleLarge,
                    color = EodigaColors.Muted,
                )
            }
            items(state.recent, key = { it.id }) { place ->
                DestinationButton(
                    label = place.name,
                    caption = place.address,
                    onClick = { onDestinationSelected(place) },
                )
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
            // 목적지 버튼과 같은 크기로 둔다. 이동 중에 켜는 기능이지만 안내 화면에는
            // 넣지 않았다 — 그 화면은 "지금 무엇을 하면 되는지" 하나만 말해야 한다.
            // 여기서 켜 두면 길안내로 넘어가도 노래는 계속 나온다.
            DestinationButton(
                label = "음악 듣기",
                emoji = "🎵",
                onClick = onOpenMusic,
            )
        }

        item {
            Spacer(Modifier.height(24.dp))
            SecondaryActionButton(text = "설정", onClick = onOpenSettings)
        }
    }
}

/** 종류별 아이콘. 글자를 대체하지 않고 보조만 한다. */
fun PlaceKind.emoji(): String = when (this) {
    PlaceKind.HOME -> "🏠"
    PlaceKind.HOSPITAL -> "🏥"
    PlaceKind.FAMILY -> "👨‍👩‍👧"
    PlaceKind.MART -> "🛒"
    PlaceKind.PHARMACY -> "💊"
    PlaceKind.OTHER -> "📍"
}
