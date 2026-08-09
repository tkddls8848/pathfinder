package kr.eodiga.wayfinder

import android.media.AudioManager
import kr.eodiga.wayfinder.service.Priority
import kr.eodiga.wayfinder.service.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 안내가 다른 앱 소리를 얼마나 비키게 하는가.
 *
 * 이 앱에서 절대 놓치면 안 되는 소리가 하차 안내다. 어르신이 라디오를 듣다
 * 정류장을 지나치면 앱의 존재 이유가 없어진다. 그렇다고 안내마다 음악을 멈추면
 * 이동 내내 라디오가 끊긴다. 그 선을 여기서 고정한다.
 */
class VoicePriorityTest {

    @Test
    fun `일반 안내는 음악을 멈추지 않는다`() {
        // 도보·대기·이동중. 볼륨만 낮추고 지나간다.
        assertFalse(Priority.NORMAL.pausesOtherAudio)
        assertFalse(Priority.NORMAL.interruptsOwnSpeech)
    }

    @Test
    fun `상태가 바뀐 안내는 앞 문장만 자른다`() {
        // 지금 알려야 하지만, 라디오를 멈출 만큼은 아니다.
        assertTrue(Priority.INTERRUPT.interruptsOwnSpeech)
        assertFalse(Priority.INTERRUPT.pausesOtherAudio)
    }

    @Test
    fun `긴급 안내만 음악을 멈춘다`() {
        // 벨·하차준비·하차·승차신호. 몇 초 안에 몸을 움직여야 한다.
        assertTrue(Priority.CRITICAL.pausesOtherAudio)
        assertTrue(Priority.CRITICAL.interruptsOwnSpeech)

        val pausing = Priority.entries.filter { it.pausesOtherAudio }
        assertEquals(listOf(Priority.CRITICAL), pausing)
    }

    @Test
    fun `음악을 멈추는 안내는 앞 문장도 반드시 자른다`() {
        // 음악을 멈춰 놓고 앞 문장이 끝나기를 기다리는 조합은 말이 안 된다.
        Priority.entries
            .filter { it.pausesOtherAudio }
            .forEach { assertTrue("$it", it.interruptsOwnSpeech) }
    }

    @Test
    fun `긴급은 일시정지 포커스를 잡는다`() {
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            VoiceGuide.gainFor(Priority.CRITICAL),
        )
    }

    @Test
    fun `나머지는 덕킹 포커스를 잡는다`() {
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            VoiceGuide.gainFor(Priority.NORMAL),
        )
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            VoiceGuide.gainFor(Priority.INTERRUPT),
        )
    }

    @Test
    fun `포커스는 항상 일시적이다`() {
        // 영구 포커스(AUDIOFOCUS_GAIN)를 잡으면 안내가 끝나도 음악이 돌아오지 않는다.
        Priority.entries.forEach {
            val gain = VoiceGuide.gainFor(it)
            assertTrue(
                "$it 가 영구 포커스를 잡는다",
                gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT ||
                    gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }
}
