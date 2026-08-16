package kr.eodiga.wayfinder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import kr.eodiga.wayfinder.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.eodiga.wayfinder.guardian.GuardianNotifier
import kr.eodiga.wayfinder.ui.components.InfoCard
import kr.eodiga.wayfinder.ui.components.PrimaryActionButton
import kr.eodiga.wayfinder.ui.components.ScreenTitle
import kr.eodiga.wayfinder.ui.components.SecondaryActionButton
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens

/**
 * 🆘 길을 잃었어요.
 *
 * 이 화면에 도달한 어르신은 이미 당황한 상태다. 설계 원칙은 하나다:
 * **읽지 않아도 되게 만든다.**
 *
 *  - 첫 문장은 안심시키는 말("걱정마세요")이다. 정보보다 감정이 먼저다.
 *  - 현재 위치는 좌표가 아니라 눈에 보이는 지형지물로 보여준다.
 *  - 가장 큰 버튼은 "가족에게 전화" 하나뿐이다.
 */
@Composable
fun LostScreen(
    onResumeGuidance: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GuidanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lostResult by viewModel.lostResult.collectAsStateWithLifecycle()
    val lostLocation by viewModel.lostLocation.collectAsStateWithLifecycle()
    val isContacting by viewModel.isContactingGuardian.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshLostLocation()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EodigaDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        ScreenTitle("걱정마세요")

        Text(
            "지금 계신 곳",
            style = MaterialTheme.typography.bodyLarge,
            color = EodigaColors.Muted,
        )

        InfoCard {
            when (val current = lostLocation) {
                LostLocationUiState.Loading -> Text(
                    "현재 위치를 새로 확인하고 있습니다",
                    style = MaterialTheme.typography.titleLarge,
                )

                LostLocationUiState.Unavailable -> Text(
                    "현재 위치를 확인하지 못했습니다.\n전화 연결은 계속할 수 있습니다.",
                    style = MaterialTheme.typography.titleLarge,
                )

                is LostLocationUiState.Ready -> {
                    current.addressText?.let { address ->
                        Text(address, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "위도 %.5f\n경도 %.5f".format(
                            current.location.lat,
                            current.location.lng,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EodigaColors.Muted,
                    )
                    Text(
                        if (current.addressText != null) {
                            "이 주소와 위치를 가족에게 보내드릴 수 있어요"
                        } else {
                            "주소는 찾지 못했지만 지도 위치를 가족에게 보내드릴 수 있어요"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }

        PrimaryActionButton(
            text = if (isContacting) "연락하고 있습니다" else "📞  가족에게 전화",
            onClick = viewModel::callForHelp,
            spokenLabel = "가족에게 전화합니다. 지금 계신 위치도 함께 문자로 보냅니다.",
            enabled = !isContacting,
        )

        lostResult?.let { result ->
            val presentation = presentLostResult(result)
            val borderColor = when (presentation.tone) {
                LostResultTone.SUCCESS -> EodigaColors.Success
                LostResultTone.WARNING -> EodigaColors.Warning
                LostResultTone.ERROR -> EodigaColors.Danger
            }
            InfoCard(borderColor = borderColor) {
                Text(
                    presentation.message,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        if (state.isActive) {
            PrimaryActionButton(text = "다시 안내받기", onClick = onResumeGuidance)
        }

        SecondaryActionButton(
            text = "괜찮아요, 돌아가기",
            onClick = {
                viewModel.clearLostResult()
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal enum class LostResultTone { SUCCESS, WARNING, ERROR }

internal data class LostResultPresentation(
    val message: String,
    val tone: LostResultTone,
)

/** 실행을 시작한 것과 실제 위치 문자를 보낸 것을 구분해 잘못된 안심을 막는다. */
internal fun presentLostResult(result: GuardianNotifier.Result): LostResultPresentation {
    if (result.guardianLookupFailed) {
        return LostResultPresentation(
            "가족 연락처를 불러오지 못했습니다. 다시 시도해 주세요.",
            LostResultTone.ERROR,
        )
    }
    val name = result.guardianName ?: return LostResultPresentation(
        "연락할 가족이 등록되어 있지 않습니다.\n설정에서 등록해 주세요.",
        LostResultTone.ERROR,
    )

    val message = when {
        result.smsSent && result.locationIncluded && result.callStarted ->
            "${name}님께 현재 위치를 보내고 전화 화면을 열었습니다."
        result.smsSent && result.locationIncluded ->
            "${name}님께 현재 위치를 보냈지만 전화 화면은 열지 못했습니다."
        result.smsSent && result.callStarted ->
            "${name}님께 도움 요청 문자를 보내고 전화 화면을 열었습니다.\n현재 위치는 포함하지 못했습니다."
        result.smsSent ->
            "${name}님께 도움 요청 문자를 보냈습니다.\n현재 위치와 전화 연결은 확인하지 못했습니다."
        result.smsStatus == GuardianNotifier.SmsStatus.COMPOSER_OPENED && result.callStarted ->
            "문자와 전화 화면을 열었습니다.\n전송과 통화 버튼을 눌러 주세요."
        result.smsStatus == GuardianNotifier.SmsStatus.COMPOSER_OPENED ->
            "문자 화면을 열었습니다. 전송 버튼을 눌러 주세요.\n전화 화면은 열지 못했습니다."
        result.callStarted ->
            "위치 문자는 보내지 못했습니다.\n전화 화면에서 통화 버튼을 눌러 주세요."
        else ->
            "문자와 전화 연결을 시작하지 못했습니다. 다시 시도해 주세요."
    }
    val tone = when {
        result.smsSent && result.locationIncluded -> LostResultTone.SUCCESS
        result.anyActionStarted -> LostResultTone.WARNING
        else -> LostResultTone.ERROR
    }
    return LostResultPresentation(message, tone)
}
