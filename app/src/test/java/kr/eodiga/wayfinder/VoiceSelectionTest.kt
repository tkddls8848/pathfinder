package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.service.VoiceCandidate
import kr.eodiga.wayfinder.service.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 보이스 선택 규칙.
 *
 * android.speech.tts.Voice 의 품질/지연 상수값을 그대로 쓴다.
 * (VERY_LOW 100 · LOW 200 · NORMAL 300 · HIGH 400 · VERY_HIGH 500)
 */
class VoiceSelectionTest {

    private val veryLow = 100
    private val normal = 300
    private val high = 400
    private val veryHigh = 500

    private fun voice(
        name: String,
        language: String = "ko",
        quality: Int = normal,
        latency: Int = normal,
        requiresNetwork: Boolean = false,
        notInstalled: Boolean = false,
    ) = VoiceCandidate(name, language, quality, latency, requiresNetwork, notInstalled)

    @Test
    fun `품질이 가장 높은 한국어 보이스를 고른다`() {
        val best = VoiceGuide.selectBestVoice(
            listOf(
                voice("ko-kr-embedded", quality = veryLow),
                voice("ko-kr-neural", quality = veryHigh),
                voice("ko-kr-basic", quality = normal),
            ),
        )

        assertEquals("ko-kr-neural", best?.name)
    }

    @Test
    fun `한국어가 아닌 보이스는 고르지 않는다`() {
        val best = VoiceGuide.selectBestVoice(
            listOf(
                voice("en-us-neural", language = "en", quality = veryHigh),
                voice("ko-kr-basic", quality = veryLow),
            ),
        )

        assertEquals("ko-kr-basic", best?.name)
    }

    @Test
    fun `언어를 ISO3 kor 로 주는 엔진도 한국어로 인정한다`() {
        val best = VoiceGuide.selectBestVoice(listOf(voice("ko-kr-neural", language = "kor")))

        assertEquals("ko-kr-neural", best?.name)
    }

    @Test
    fun `네트워크 보이스는 품질이 더 높아도 로컬 보이스에 밀린다`() {
        // 버스 안에서 데이터가 끊기면 하차 안내가 통째로 사라진다.
        val best = VoiceGuide.selectBestVoice(
            listOf(
                voice("ko-kr-network", quality = veryHigh, requiresNetwork = true),
                voice("ko-kr-local", quality = veryLow),
            ),
        )

        assertEquals("ko-kr-local", best?.name)
    }

    @Test
    fun `로컬 한국어 보이스가 하나도 없으면 네트워크 보이스라도 쓴다`() {
        val best = VoiceGuide.selectBestVoice(
            listOf(
                voice("ko-kr-network", quality = high, requiresNetwork = true),
                voice("en-us-local", language = "en", quality = veryHigh),
            ),
        )

        assertEquals("ko-kr-network", best?.name)
    }

    @Test
    fun `설치되지 않은 보이스는 후보에서 뺀다`() {
        val best = VoiceGuide.selectBestVoice(
            listOf(
                voice("ko-kr-neural", quality = veryHigh, notInstalled = true),
                voice("ko-kr-basic", quality = normal),
            ),
        )

        assertEquals("ko-kr-basic", best?.name)
    }

    @Test
    fun `품질이 같으면 지연이 낮은 쪽을 고른다`() {
        val best = VoiceGuide.selectBestVoice(
            listOf(
                voice("ko-kr-slow", quality = high, latency = high),
                voice("ko-kr-fast", quality = high, latency = veryLow),
            ),
        )

        assertEquals("ko-kr-fast", best?.name)
    }

    @Test
    fun `품질과 지연이 같으면 항상 같은 보이스를 고른다`() {
        // 실행할 때마다 목소리가 바뀌면 어르신이 다른 앱으로 착각한다.
        val candidates = listOf(voice("ko-kr-b"), voice("ko-kr-a"), voice("ko-kr-c"))

        assertEquals("ko-kr-a", VoiceGuide.selectBestVoice(candidates)?.name)
        assertEquals("ko-kr-a", VoiceGuide.selectBestVoice(candidates.reversed())?.name)
    }

    @Test
    fun `쓸 수 있는 한국어 보이스가 없으면 null 을 준다`() {
        // null 이면 엔진 기본 보이스를 그대로 쓴다. 안내가 없어지는 것보다 낫다.
        assertNull(VoiceGuide.selectBestVoice(emptyList()))
        assertNull(VoiceGuide.selectBestVoice(listOf(voice("en-us", language = "en"))))
        assertNull(VoiceGuide.selectBestVoice(listOf(voice("ko-kr", notInstalled = true))))
    }
}
