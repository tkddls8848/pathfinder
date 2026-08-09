package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.ui.components.keepWordsWhole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 어절 안에서 줄이 끊기지 않게 하는 규칙.
 *
 * 안드로이드는 한국어를 CJK 로 보고 아무 음절에서나 끊는다. 큰 글씨를 쓰는 이 앱에서는
 * "앱 설정이 끝나지 않았습니 / 다" 처럼 단어 한가운데가 잘리는 일이 잦다.
 *
 * Compose 의 `LineBreak`(`WordBreak.Phrase`)로는 못 고친다 — 일본어 문절용이라
 * 한글에는 효과가 없다. 에뮬레이터에서 확인했다.
 */
class KoreanTextTest {

    private val WJ = '⁠'

    @Test
    fun `어절 안 글자 사이를 붙인다`() {
        assertEquals("가${WJ}족", "가족".keepWordsWhole())
    }

    @Test
    fun `공백 자리는 그대로 둔다`() {
        // 여기가 끊겨야 할 곳이다. 붙여 버리면 줄바꿈이 아예 불가능해진다.
        assertEquals("가${WJ}족 사${WJ}랑", "가족 사랑".keepWordsWhole())
    }

    @Test
    fun `실제 화면 문구`() {
        val result = "앱 설정이 끝나지 않았습니다".keepWordsWhole()

        // 공백은 셋 그대로 — 줄바꿈 가능 지점이 보존된다.
        assertEquals(3, result.count { it == ' ' })
        // 어절 안쪽 경계마다 하나씩. "앱"1 "설정이"3 "끝나지"3 "않았습니다"5 → 0+2+2+4
        assertEquals(8, result.count { it == WJ })
    }

    @Test
    fun `보이는 글자는 하나도 바뀌지 않는다`() {
        val original = "가족에게 이 화면을 보여주세요."
        assertEquals(original, original.keepWordsWhole().filter { it != WJ })
    }

    @Test
    fun `짧은 문자열은 그대로 둔다`() {
        assertEquals("", "".keepWordsWhole())
        assertEquals("네", "네".keepWordsWhole())
    }

    @Test
    fun `줄바꿈 문자 앞뒤에는 넣지 않는다`() {
        // 개행도 공백이다. 여기를 붙이면 코드가 의도한 줄나눔이 깨진다.
        // "아래" 안쪽에는 정상적으로 들어간다.
        assertEquals("위\n아${WJ}래", "위\n아래".keepWordsWhole())
    }

    @Test
    fun `숫자와 영문에도 똑같이 적용된다`() {
        // "7016번" 이 "7016 / 번" 으로 갈라지면 전광판과 대조가 안 된다.
        val result = "7016번 버스".keepWordsWhole()
        assertTrue(result.startsWith("7${WJ}0${WJ}1${WJ}6${WJ}번"))
    }

    @Test
    fun `이모지를 쪼개지 않는다`() {
        // 🔍 는 UTF-16 에서 두 칸을 차지한다. 그 사이에 끼우면 글자가 깨져
        // 화면에 두부(◆?)가 뜬다. 실제로 겪은 회귀다.
        val magnifier = "🔍"
        assertEquals(magnifier, magnifier.keepWordsWhole())
        assertEquals("🔍 다${WJ}른 곳", "🔍 다른 곳".keepWordsWhole())
    }

    @Test
    fun `조합형 이모지도 그대로 둔다`() {
        // 가족 이모지는 사람 여럿을 ZWJ 로 이어 붙인 것이다.
        val family = "👨‍👩‍👧"
        assertEquals(family, family.keepWordsWhole())
    }

    @Test
    fun `문장부호 옆에는 넣지 않는다`() {
        // 마침표·가운뎃점 앞은 안드로이드가 이미 끊지 않는다.
        assertEquals("가${WJ}족 · 집", "가족 · 집".keepWordsWhole())
    }
}
