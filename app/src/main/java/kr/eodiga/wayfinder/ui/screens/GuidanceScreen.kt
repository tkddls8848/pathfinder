package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.eodiga.wayfinder.domain.model.GuidanceStage
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.service.JourneyState
import kr.eodiga.wayfinder.ui.components.BusNumberTag
import kr.eodiga.wayfinder.ui.components.HeroNumber
import kr.eodiga.wayfinder.ui.components.InfoCard
import kr.eodiga.wayfinder.ui.components.LostHelpBar
import kr.eodiga.wayfinder.ui.components.PrimaryActionButton
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens

/**
 * 안내 화면 하나로 목업의 ⑤~⑪을 모두 처리한다.
 *
 * 목업에서는 화면 11개를 사람이 버튼으로 넘겼지만, 실제로는
 * [kr.eodiga.wayfinder.service.JourneyController] 의 상태가 화면을 결정한다.
 * 어르신이 아무것도 누르지 않아도 상태가 진행되고, 화면은 그 결과를 비출 뿐이다.
 *
 * 화면 전체가 하나의 메시지를 전달한다. 한 화면에 한 가지 동작만 요구한다.
 */
@Composable
fun GuidanceScreen(
    onFinished: () -> Unit,
    onLost: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuidanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 상태에 따라 화면 배경색 자체가 바뀐다. 글씨를 못 읽어도 색으로 긴급도를 안다.
    val background = when (state.stage) {
        is GuidanceStage.PrepareToAlight, is GuidanceStage.RingBell -> EodigaColors.Warning
        GuidanceStage.Arrived -> EodigaColors.SuccessSurface
        else -> MaterialTheme.colorScheme.background
    }
    val onBackground = when (state.stage) {
        is GuidanceStage.PrepareToAlight, is GuidanceStage.RingBell -> EodigaColors.WarningText
        else -> MaterialTheme.colorScheme.onBackground
    }

    Column(modifier = modifier.fillMaxSize().background(background)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(EodigaDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
        ) {
            StageContent(
                state = state,
                textColor = onBackground,
                onConfirmAtStop = viewModel::confirmAtStop,
                onConfirmBoarded = viewModel::confirmBoarded,
                onCancelBoarding = viewModel::cancelBoarding,
                onConfirmAlighted = viewModel::confirmAlighted,
                onRepeat = viewModel::repeatGuidance,
                onFinish = {
                    viewModel.finishAndRecord()
                    onFinished()
                },
            )
        }

        // 도착 화면에서는 긴급 버튼을 감춘다. 여정이 끝났는데 남아 있으면 불안을 준다.
        if (state.stage != GuidanceStage.Arrived) {
            LostHelpBar(onClick = onLost)
        }
    }
}

@Composable
private fun StageContent(
    state: JourneyState,
    textColor: Color,
    onConfirmAtStop: () -> Unit,
    onConfirmBoarded: () -> Unit,
    onCancelBoarding: () -> Unit,
    onConfirmAlighted: () -> Unit,
    onRepeat: () -> Unit,
    onFinish: () -> Unit,
) {
    val journey: Journey = state.journey ?: return
    val leg = journey.legs.getOrNull(state.stage.legIndex)

    when (val stage = state.stage) {
        /* ⑤ 도보 안내 */
        is GuidanceStage.Walking -> {
            val walk = leg as? JourneyLeg.Walk
            Text(
                "걸어가세요",
                style = MaterialTheme.typography.displayMedium,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            HeroNumber(
                value = "${stage.remainingMeters.toSteps()}",
                unit = "걸음",
                spokenLabel = "약 ${stage.remainingMeters.toSteps()}걸음 남았습니다",
            )
            InfoCard {
                Text(
                    walk?.destinationName ?: journey.destination.name,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "쪽으로 가세요",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = EodigaColors.Muted,
                )
            }
            Spacer(Modifier.padding(8.dp))
            SecondaryActionButton(text = "다시 들려주세요", onClick = onRepeat)
        }

        /* ⑥ 정류장 도착 확인 */
        is GuidanceStage.ArrivedAtStop -> {
            val walk = leg as? JourneyLeg.Walk
            Text(
                "도착했습니다",
                style = MaterialTheme.typography.headlineLarge,
                color = textColor,
            )
            Text(
                walk?.destinationName ?: "정류장",
                style = MaterialTheme.typography.displayMedium,
                color = textColor,
            )
            InfoCard {
                Text(
                    "이 정류장이 맞으면\n아래를 눌러주세요",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            PrimaryActionButton(
                text = "네, 여기예요",
                onClick = onConfirmAtStop,
                spokenLabel = "네, 여기가 맞습니다",
            )
        }

        /* ⑦ 버스 기다리기 */
        is GuidanceStage.WaitingForBus -> {
            val bus = leg as? JourneyLeg.Bus
            if (bus != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    BusNumberTag(bus.route.routeNo)
                    Spacer(Modifier.padding(6.dp))
                    Text(
                        "${bus.route.kind.colorName.ifBlank { "" }} ${bus.route.kind.label}".trim(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = textColor,
                    )
                }
            }

            val arrival = stage.arrival
            val failure = state.failure
            when {
                arrival == null && failure != null -> InfoCard {
                    Text(failure.spokenMessage, style = MaterialTheme.typography.titleLarge)
                }

                arrival == null -> InfoCard {
                    Text(
                        "도착 시간을 알아보는 중입니다",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> {
                    HeroNumber(
                        value = "${arrival.arrivalMinutes}",
                        unit = "분 뒤 도착",
                        spokenLabel = "${arrival.arrivalMinutes}분 뒤에 도착합니다",
                    )
                    if (arrival.isLowFloor) {
                        InfoCard(borderColor = EodigaColors.Success) {
                            Text(
                                "계단 없는 저상버스입니다",
                                style = MaterialTheme.typography.titleLarge,
                                color = EodigaColors.Success,
                            )
                        }
                    }
                }
            }

            InfoCard {
                Text(
                    "버스가 오기 1분 전에\n진동으로 알려드려요",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EodigaColors.Muted,
                )
            }
        }

        /* ⑧ 승차 확인 */
        is GuidanceStage.Boarding -> {
            val bus = leg as? JourneyLeg.Bus
            Text(
                "버스에 타셨나요?",
                style = MaterialTheme.typography.headlineLarge,
                color = textColor,
            )
            if (bus != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) { BusNumberTag(bus.route.routeNo) }
            }
            InfoCard {
                Text(
                    "카드를 대고\n자리에 앉으세요",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryActionButton(text = "네, 탔어요", onClick = onConfirmBoarded)
            SecondaryActionButton(text = "아직 못 탔어요", onClick = onCancelBoarding)
        }

        /* ⑨ 이동 중 */
        is GuidanceStage.Riding -> {
            Text(
                "내리기까지",
                style = MaterialTheme.typography.headlineLarge,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            HeroNumber(
                value = "${stage.stopsRemaining}",
                unit = "정거장",
                spokenLabel = "${stage.stopsRemaining}정거장 남았습니다",
            )
            InfoCard {
                Text("다음 정류장", style = MaterialTheme.typography.bodyMedium, color = EodigaColors.Muted)
                Text(
                    stage.nextStopName ?: "확인 중",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            InfoCard(borderColor = EodigaColors.Muted) {
                Text(
                    "내릴 때가 되면\n크게 알려드릴게요.\n편히 앉아 계세요.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EodigaColors.Muted,
                )
            }
        }

        /* ⑩ 하차 준비 */
        is GuidanceStage.PrepareToAlight -> {
            Text(
                "곧 내립니다\n일어날 준비하세요",
                style = MaterialTheme.typography.displayMedium,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "🧍",
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            InfoCard {
                Text(
                    "${stage.stopsRemaining}정거장 남았습니다",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        /* ⑩-b 벨 누르기 */
        is GuidanceStage.RingBell -> {
            Text(
                "🔔 벨을\n누르세요",
                style = MaterialTheme.typography.displayMedium,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            InfoCard {
                Text(
                    "다음에 내립니다",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryActionButton(text = "내렸어요", onClick = onConfirmAlighted)
        }

        /* ⑪ 도착 */
        GuidanceStage.Arrived -> {
            Text(
                "🎉",
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "도착했습니다",
                style = MaterialTheme.typography.displayMedium,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            InfoCard {
                Text(
                    journey.destination.name,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "바로 앞입니다",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryActionButton(text = "처음으로", onClick = onFinish)
        }
    }
}

private fun Int.toSteps(): Int = (this / 0.65).toInt().coerceAtLeast(1)
