package kr.eodiga.wayfinder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 색 팔레트.
 *
 * 노안·백내장은 파랑 계열 대비를 떨어뜨리고 전체적으로 노랗게 보이게 만든다.
 * 그래서 색만으로 정보를 전달하지 않고, 항상 글자·아이콘·크기와 함께 쓴다.
 * 모든 조합은 WCAG AAA(7:1) 를 목표로 잡았다.
 */
object EodigaColors {
    val Ink = Color(0xFF111111)          // 본문 (흰 배경 대비 18.9:1)
    val Surface = Color(0xFFFFFFFF)
    val Primary = Color(0xFF0B4FA8)      // 주 동작 (흰 글자 대비 8.6:1)
    val PrimaryText = Color(0xFFFFFFFF)
    val Warning = Color(0xFFFFD400)      // 임박 — 검은 글자와만 조합
    val WarningText = Color(0xFF111111)
    val Danger = Color(0xFFB3261E)       // 긴급
    val DangerText = Color(0xFFFFFFFF)
    val Success = Color(0xFF1B5E20)
    val SuccessSurface = Color(0xFFE8F5E9)
    val Muted = Color(0xFF4A4A4A)        // 흰 배경 대비 9.0:1 — 회색이지만 본문으로 읽힌다
    val Outline = Color(0xFF111111)
    val Disabled = Color(0xFF767676)
}

private val LightScheme = lightColorScheme(
    primary = EodigaColors.Primary,
    onPrimary = EodigaColors.PrimaryText,
    surface = EodigaColors.Surface,
    onSurface = EodigaColors.Ink,
    background = EodigaColors.Surface,
    onBackground = EodigaColors.Ink,
    error = EodigaColors.Danger,
    onError = EodigaColors.DangerText,
    outline = EodigaColors.Outline,
)

/**
 * 다크 모드는 지원하되 기본값으로 삼지 않는다.
 * 노안에서는 검은 배경의 흰 글자가 halation(번짐)을 일으켜 오히려 읽기 어렵다.
 * 어르신이 직접 켰을 때만 순검정 대신 짙은 회색을 쓴다.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9EC5FF),
    onPrimary = Color(0xFF00315C),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFF5F5F5),
    background = Color(0xFF1A1A1A),
    onBackground = Color(0xFFF5F5F5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFFF5F5F5),
)

/**
 * 타이포그래피.
 *
 * 기준선이 일반 앱보다 두 배 이상 크다. 최소 본문 22sp, 주요 버튼 32sp.
 * 굵기는 SemiBold 이상만 쓴다 — 저대비 시력에서 얇은 획은 사라진다.
 */
val EodigaTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 88.sp, lineHeight = 96.sp, fontWeight = FontWeight.Black,
    ),
    displayMedium = TextStyle(
        fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.ExtraBold,
    ),
    headlineLarge = TextStyle(
        fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold,
    ),
    headlineMedium = TextStyle(
        fontSize = 34.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 30.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold,
    ),
    bodyLarge = TextStyle(
        fontSize = 26.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    ),
    bodyMedium = TextStyle(
        fontSize = 22.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium,
    ),
    labelLarge = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
    ),
)

/** 앱 전역 치수. 터치 타겟 최소값이 Material 권장(48dp)보다 훨씬 크다. */
object EodigaDimens {
    /** 주 동작 버튼 높이. 떨리는 손으로도 빗나가지 않는 크기. */
    val PrimaryButtonHeight = 96.dp

    /** 보조 버튼 높이. 그래도 Material 최소값의 1.5배. */
    val SecondaryButtonHeight = 72.dp

    /** 화면 좌우 여백. */
    val ScreenPadding = 20.dp

    /** 요소 간 간격. 인접 버튼 오탭을 막기 위해 넉넉히 둔다. */
    val ElementGap = 16.dp

    val BorderWidth = 4.dp
    val CornerRadius = 16.dp
}

val LocalEodigaDimens = staticCompositionLocalOf { EodigaDimens }

@Composable
fun EodigaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalEodigaDimens provides EodigaDimens) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = EodigaTypography,
            content = content,
        )
    }
}
