package kr.eodiga.wayfinder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.eodiga.wayfinder.service.VoiceGuide
import kr.eodiga.wayfinder.ui.theme.EodigaColors
import kr.eodiga.wayfinder.ui.theme.EodigaDimens

/**
 * 주 동작 버튼.
 *
 * 어르신 UI 규칙:
 *  - 화면당 주 동작은 하나만. 두 개 이상이면 결정을 못 한다.
 *  - 아이콘은 글자를 보조할 뿐 단독으로 의미를 갖지 않는다.
 *  - TalkBack 이 읽을 문장을 [spokenLabel] 로 따로 준다.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingEmoji: String? = null,
    spokenLabel: String = text,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = EodigaDimens.PrimaryButtonHeight)
            .semantics { contentDescription = spokenLabel },
        shape = RoundedCornerShape(EodigaDimens.CornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = EodigaColors.Primary,
            contentColor = EodigaColors.PrimaryText,
            disabledContainerColor = EodigaColors.Disabled,
            disabledContentColor = Color.White,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingEmoji != null) {
                Text(leadingEmoji, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 보조 버튼. 시각적 위계를 확실히 낮춰 주 동작과 헷갈리지 않게 한다. */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    spokenLabel: String = text,
    danger: Boolean = false,
) {
    val color = if (danger) EodigaColors.Danger else EodigaColors.Muted
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = EodigaDimens.SecondaryButtonHeight)
            .semantics { contentDescription = spokenLabel },
        shape = RoundedCornerShape(EodigaDimens.CornerRadius),
        border = BorderStroke(3.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

/** 큰 선택 버튼. 홈 화면의 "집 / 병원 / 아들집" 처럼 목적지를 고를 때. */
@Composable
fun DestinationButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    caption: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = EodigaDimens.PrimaryButtonHeight)
            .semantics {
                contentDescription = listOfNotNull(label, caption).joinToString(", ")
            },
        shape = RoundedCornerShape(EodigaDimens.CornerRadius),
        border = BorderStroke(EodigaDimens.BorderWidth, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp,
            vertical = 12.dp,
        ),
    ) {
        if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.headlineMedium)
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EodigaColors.Muted,
                )
            }
        }
    }
}

/** 정보 카드. 굵은 테두리로 배경과 확실히 분리한다 — 저대비 시력에서 그림자는 안 보인다. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EodigaDimens.CornerRadius),
        color = background,
        border = BorderStroke(EodigaDimens.BorderWidth, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/** 화면 제목. TalkBack 이 제목으로 인식하도록 heading 시맨틱을 붙인다. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

/** 가장 중요한 숫자 하나를 화면 중앙에 크게. 정거장 수, 남은 분. */
@Composable
fun HeroNumber(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = EodigaColors.Primary,
    spokenLabel: String = "$value $unit",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spokenLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.displayLarge, color = color)
        Text(unit, style = MaterialTheme.typography.headlineLarge)
    }
}

/** 버스 번호 태그. 정류장 전광판과 같은 형태로 보여줘 대조하기 쉽게 한다. */
@Composable
fun BusNumberTag(
    routeNo: String,
    modifier: Modifier = Modifier,
    color: Color = EodigaColors.Danger,
) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 10.dp)
            // 화면에는 "7016", 톡백에는 "칠 공 일 육 번 버스". 그냥 두면 "칠천십육" 으로
            // 읽혀 전광판과 대조가 안 된다 — 이 태그가 존재하는 이유가 대조다.
            .semantics {
                contentDescription = "${VoiceGuide.busNumberToSpeech(routeNo)}번 버스"
            },
    ) {
        Text(
            routeNo,
            style = MaterialTheme.typography.displayMedium,
            color = Color.White,
        )
    }
}

/** 하단 고정 긴급 버튼. 모든 안내 화면에 항상 같은 위치에 있다. */
@Composable
fun LostHelpBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
        color = EodigaColors.Danger,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "길을 잃었어요",
                style = MaterialTheme.typography.headlineMedium,
                color = EodigaColors.DangerText,
                modifier = Modifier.semantics {
                    contentDescription = "길을 잃었어요. 누르면 가족에게 위치를 보내고 전화를 겁니다."
                },
            )
        }
    }
}

/** 로딩. 어르신에게 스피너만 보여주면 고장으로 오해한다. 항상 말로 설명한다. */
@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.height(72.dp),
            strokeWidth = 8.dp,
            color = EodigaColors.Primary,
        )
        Text(
            message,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = EodigaColors.Muted,
        )
    }
}

/** 오류. 원인 대신 "지금 무엇을 하면 되는지" 를 말한다. */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(EodigaDimens.ElementGap),
    ) {
        Text("🙏", style = MaterialTheme.typography.displayMedium)
        Text(
            message,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            PrimaryActionButton(text = "다시 해보기", onClick = onRetry)
        }
    }
}
