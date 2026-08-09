package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.service.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 말로 나가는 수를 어떻게 읽는가.
 *
 * 한국어는 단위마다 붙는 수사가 다르고, TTS 는 숫자를 보면 무조건 한자어로 읽는다.
 * 그래서 `3정거장` 은 "삼 정거장" 으로 나간다 — 한국어로는 틀린 말이다.
 * 어르신이 알아들어야 하는 안내라 여기서 틀리면 안 된다.
 */
class SpeechTextTest {

    /* ── 정거장: 순우리말 단위 ───────────────────────────── */

    @Test
    fun `정거장은 순우리말 수관형사로 읽는다`() {
        // "삼 정거장" 이 아니라 "세 정거장" 이다.
        assertEquals("한 정거장", VoiceGuide.stopCountToSpeech(1))
        assertEquals("두 정거장", VoiceGuide.stopCountToSpeech(2))
        assertEquals("세 정거장", VoiceGuide.stopCountToSpeech(3))
        assertEquals("네 정거장", VoiceGuide.stopCountToSpeech(4))
        assertEquals("다섯 정거장", VoiceGuide.stopCountToSpeech(5))
    }

    @Test
    fun `열 단위도 순우리말로 읽는다`() {
        assertEquals("열", VoiceGuide.nativeCountToSpeech(10))
        assertEquals("열한", VoiceGuide.nativeCountToSpeech(11))
        assertEquals("열아홉", VoiceGuide.nativeCountToSpeech(19))
        assertEquals("서른", VoiceGuide.nativeCountToSpeech(30))
        assertEquals("아흔아홉", VoiceGuide.nativeCountToSpeech(99))
    }

    @Test
    fun `스물은 단위수가 붙을 때만 스물이다`() {
        // 단독은 "스무 정거장", 뒤에 수가 붙으면 "스물세 정거장".
        assertEquals("스무", VoiceGuide.nativeCountToSpeech(20))
        assertEquals("스물한", VoiceGuide.nativeCountToSpeech(21))
        assertEquals("스물세", VoiceGuide.nativeCountToSpeech(23))
    }

    @Test
    fun `백부터는 숫자를 그대로 넘겨 한자어로 읽게 둔다`() {
        // "백쉰 걸음" 이라고 말하는 사람은 없다. TTS 가 "백오십" 으로 읽는 게 맞다.
        assertEquals("100", VoiceGuide.nativeCountToSpeech(100))
        assertEquals("150", VoiceGuide.nativeCountToSpeech(150))
    }

    @Test
    fun `범위를 벗어난 수는 그대로 돌려준다`() {
        assertEquals("0", VoiceGuide.nativeCountToSpeech(0))
        assertEquals("-1", VoiceGuide.nativeCountToSpeech(-1))
    }

    /* ── 걸음: 순우리말 단위 + 십 단위 반올림 ─────────────── */

    @Test
    fun `서른 걸음 이하는 세어볼 수 있는 거리라 그대로 말한다`() {
        // 19m ÷ 보폭 0.65m ≒ 29 걸음
        assertEquals("스물아홉 걸음", VoiceGuide.distanceToSpeech(19))
        assertEquals("스무 걸음", VoiceGuide.distanceToSpeech(13))
    }

    @Test
    fun `서른 걸음을 넘으면 십 단위로 뭉갠다`() {
        // GPS 오차를 생각하면 한 걸음 단위는 어차피 거짓말이다.
        assertEquals("마흔 걸음", VoiceGuide.distanceToSpeech(30)) // 46 → 40
        assertEquals("150 걸음", VoiceGuide.distanceToSpeech(100)) // 154 → 150
    }

    @Test
    fun `거리가 0이어도 걸음 수는 최소 하나다`() {
        // "영 걸음 남았어요" 는 안내가 아니다.
        assertEquals("한 걸음", VoiceGuide.distanceToSpeech(0))
    }

    /* ── 버스 번호: 자릿수로 ─────────────────────────────── */

    @Test
    fun `버스 번호는 자릿수로 읽는다`() {
        // "칠천십육" 으로 읽으면 정류장 전광판의 7016 과 대조가 안 된다.
        assertEquals("칠 공 일 육", VoiceGuide.busNumberToSpeech("7016"))
        assertEquals("이", VoiceGuide.busNumberToSpeech("2"))
    }

    @Test
    fun `숫자가 아닌 글자는 그대로 둔다`() {
        // 지선 "6-1", 심야 "N15" 같은 번호가 실제로 있다.
        assertEquals("육 - 일", VoiceGuide.busNumberToSpeech("6-1"))
        assertEquals("N 일 오", VoiceGuide.busNumberToSpeech("N15"))
    }
}
