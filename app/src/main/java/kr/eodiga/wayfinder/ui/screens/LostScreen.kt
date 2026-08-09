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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            val here = state.currentLocation
            if (here != null) {
                Text(
                    "위도 %.5f\n경도 %.5f".format(here.lat, here.lng),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EodigaColors.Muted,
                )
                Text(
                    "이 위치를 가족에게 보내드릴 수 있어요",
                    style = MaterialTheme.typography.titleLarge,
                )
            } else {
                Text(
                    "위치를 확인하는 중입니다",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        PrimaryActionButton(
            text = "📞  가족에게 전화",
            onClick = viewModel::callForHelp,
            spokenLabel = "가족에게 전화합니다. 지금 계신 위치도 함께 문자로 보냅니다.",
        )

        lostResult?.let { result ->
            InfoCard(borderColor = EodigaColors.Success) {
                Text(
                    when {
                        result.guardianName == null ->
                            "연락할 가족이 등록되어 있지 않습니다.\n설정에서 등록해 주세요."
                        result.smsSent ->
                            "${result.guardianName}님께 위치를 보냈습니다."
                        else ->
                            "${result.guardianName}님께 연결하고 있습니다."
                    },
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
