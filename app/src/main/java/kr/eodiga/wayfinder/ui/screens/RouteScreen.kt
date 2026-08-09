package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import kr.eodiga.wayfinder.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.eodiga.wayfinder.data.repository.Severity
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.ui.components.BusNumberTag
import kr.eodiga.wayfinder.ui.components.ErrorState
import kr.eodiga.wayfinder.ui.components.InfoCard
import kr.eodiga.wayfinder.ui.components.LoadingState
import kr.eodiga.wayfinder.ui.components.PrimaryActionButton
import kr.eodiga.wayfinder.ui.components.ScreenTitle
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens

/**
 * 경로 확인 화면.
 *
 * 지도를 기본으로 보여주지 않는다. 어르신에게 지도는 읽는 데 인지 부담이 크고,
 * 축척·방향 개념을 요구한다. 대신 "걸어서 3분 → 7016번 → 걸어서 2분" 처럼
 * 시간 순서의 목록으로 보여주고, 지도는 원할 때만 연다.
 */
@Composable
fun RouteScreen(
    destination: Place,
    onStart: (Journey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutePlanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(destination.id) { viewModel.plan(destination) }

    when (val s = state) {
        RoutePlanUiState.Idle,
        RoutePlanUiState.Loading,
        -> LoadingState(
            message = "${destination.name} 가는 길을\n찾고 있습니다",
            modifier = modifier.fillMaxSize().padding(top = 120.dp),
        )

        is RoutePlanUiState.Failed -> ErrorState(
            message = s.reason.spokenMessage,
            onRetry = { viewModel.retry() },
            modifier = modifier.fillMaxSize().padding(top = 120.dp),
        )

        is RoutePlanUiState.Ready -> RouteReadyContent(
            destination = destination,
            state = s,
            onSelectAlternative = viewModel::selectAlternative,
            onStart = {
                viewModel.rememberDestination(destination)
                onStart(s.selected)
            },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun RouteReadyContent(
    destination: Place,
    state: RoutePlanUiState.Ready,
    onSelectAlternative: (Int) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val journey = state.selected

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(EodigaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        item { ScreenTitle("${destination.name} 까지") }

        // 날씨 경고는 경로보다 먼저 보여준다. 나갈지 말지를 먼저 결정해야 한다.
        state.weather?.let { warning ->
            item {
                InfoCard(
                    background = if (warning.severity == Severity.DANGER) {
                        EodigaColors.Warning
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    borderColor = if (warning.severity == Severity.DANGER) {
                        EodigaColors.Danger
                    } else {
                        EodigaColors.Muted
                    },
                ) {
                    Text(
                        warning.headline,
                        style = MaterialTheme.typography.headlineMedium,
                        color = EodigaColors.WarningText,
                    )
                    Text(
                        warning.advice,
                        style = MaterialTheme.typography.bodyLarge,
                        color = EodigaColors.WarningText,
                    )
                }
            }
        }

        itemsIndexed(journey.legs) { index, leg ->
            LegRow(leg)
            if (index != journey.legs.lastIndex) {
                Box(
                    Modifier
                        .padding(start = 10.dp)
                        .width(4.dp)
                        .height(28.dp)
                        .background(EodigaColors.Muted),
                )
            }
        }

        item {
            InfoCard {
                Text(
                    "모두 약 ${journey.totalMinutes}분",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        contentDescription = "전체 소요 시간 약 ${journey.totalMinutes}분"
                    },
                )
                if (journey.transferCount > 0) {
                    Text(
                        "버스를 ${journey.transferCount}번 갈아탑니다",
                        style = MaterialTheme.typography.bodyLarge,
                        color = EodigaColors.Danger,
                    )
                }
            }
        }

        item {
            PrimaryActionButton(
                text = "출발합니다",
                onClick = onStart,
                spokenLabel = "출발합니다. 누르면 길안내를 시작합니다.",
            )
        }

        // 대안 경로는 기본으로 숨긴다. 선택지가 많으면 결정을 못 한다.
        if (state.journeys.size > 1) {
            item {
                Text(
                    "다른 방법",
                    style = MaterialTheme.typography.titleLarge,
                    color = EodigaColors.Muted,
                )
            }
            itemsIndexed(state.journeys) { index, alt ->
                if (index != state.selectedIndex) {
                    SecondaryActionButton(
                        text = alt.summaryLine(),
                        onClick = { onSelectAlternative(index) },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            SecondaryActionButton(text = "뒤로", onClick = onBack)
        }
    }
}

@Composable
private fun LegRow(leg: JourneyLeg) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(20.dp)
                .background(EodigaColors.Primary, CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        when (leg) {
            is JourneyLeg.Walk -> Column {
                Text(
                    "걸어서 ${leg.durationMinutes}분",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "${leg.destinationName} 까지",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EodigaColors.Muted,
                )
            }

            is JourneyLeg.Bus -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BusNumberTag(leg.route.routeNo)
                    Spacer(Modifier.width(12.dp))
                    Text("버스", style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    "${leg.boardStop.name}에서 타서 ${leg.stopCount}정거장",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EodigaColors.Muted,
                )
                Text(
                    "${leg.alightStop.name}에서 내리세요",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun Journey.summaryLine(): String {
    val buses = busLegs.joinToString(", ") { "${it.route.routeNo}번" }
    return if (buses.isBlank()) {
        "걸어서 ${totalMinutes}분"
    } else {
        "$buses · 약 ${totalMinutes}분"
    }
}
