package kr.eodiga.wayfinder.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text as MaterialText

/**
 * 어절 안에서 줄이 끊기지 않게 한다.
 *
 * 안드로이드는 한국어를 CJK 로 보고 아무 음절에서나 줄을 끊는다. 그래서
 * "앱 설정이 끝나지 않았습니 / 다" 처럼 단어 한가운데가 잘린다.
 * 앞줄에 자리가 남았는데도 그렇다.
 *
 * 이 앱은 본문 22sp·제목 34sp 라 한 줄에 들어가는 글자가 일반 앱의 절반도 안 된다.
 * 그만큼 자주 걸리고, 읽는 사람이 어르신이라 손실이 크다.
 *
 * **Compose 의 `LineBreak` 로는 못 고친다.** `WordBreak.Phrase` 를 넣어 봤으나
 * 이것은 일본어 문절 단위 끊기용이라 한글에는 아무 효과가 없다(에뮬레이터에서 확인).
 * CSS 의 `word-break: keep-all` 에 해당하는 것을 안드로이드는 노출하지 않는다.
 *
 * 그래서 글자 사이에 워드 조이너(U+2060)를 끼워 넣는다. 폭이 0인 서식 문자이고
 * 그 자리에서 줄바꿈을 금지한다. 어절 하나가 한 줄보다 길면 안드로이드가
 * 어차피 강제로 끊으므로 글자가 잘려 사라지지는 않는다.
 *
 * 넣는 자리는 **글자·숫자가 양쪽에 있을 때뿐이다.** 이 조건이 세 가지를 한꺼번에 막는다.
 *
 *  - 공백 — 거기가 끊겨야 할 곳이다.
 *  - 이모지 — 🔍 같은 문자는 UTF-16 에서 두 칸(서로게이트 쌍)을 차지한다.
 *    그 사이에 무엇이든 끼우면 글자가 깨져 두부(◆?)로 나온다. 실제로 겪었다.
 *  - 문장부호 — 마침표 앞은 안드로이드가 이미 끊지 않는다. 손댈 필요가 없다.
 */
fun String.keepWordsWhole(): String {
    if (length < 2) return this
    return buildString(length * 2) {
        this@keepWordsWhole.forEachIndexed { i, c ->
            if (i > 0 &&
                this@keepWordsWhole[i - 1].isLetterOrDigit() &&
                c.isLetterOrDigit()
            ) {
                append(WORD_JOINER)
            }
            append(c)
        }
    }
}

private const val WORD_JOINER = '⁠'

/**
 * 앱 전용 [Text].
 *
 * 머티리얼 `Text` 를 그대로 쓰되 [keepWordsWhole] 을 먹인다. 이름을 같게 둔 것은
 * 화면 코드가 임포트 한 줄만 바꾸면 되게 하기 위해서다 — 호출부가 77곳이라
 * 하나씩 고치면 반드시 빠뜨린다.
 *
 * 낭독에는 원래 문자열을 준다. 워드 조이너가 TTS 발음에 어떻게 작용하는지
 * 엔진마다 보장되지 않는데, 이 앱에서 음성은 곁다리가 아니라 주 출력이다.
 * 눈에 보이는 글자와 귀로 듣는 글자를 분리하는 편이 안전하다.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = text.keepWordsWhole(),
        modifier = modifier.semantics { contentDescription = text },
        color = color,
        fontSize = fontSize,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        style = style,
    )
}
