package kr.eodiga.wayfinder

import android.media.AudioManager
import kr.eodiga.wayfinder.service.FocusAction
import kr.eodiga.wayfinder.service.MusicPlayer
import kr.eodiga.wayfinder.service.Priority
import kr.eodiga.wayfinder.service.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 음악이 안내에 어떻게 비켜주는가.
 *
 * 이 앱에서 절대 놓치면 안 되는 소리가 하차 알림인데, 음악이 정확히 그것과
 * 경쟁한다. 어르신이 노래를 듣다 정류장을 지나치면 앱의 존재 이유가 무너진다.
 *
 * [VoicePriorityTest] 가 안내 쪽(포커스를 잡는 쪽)을 고정한다면 여기는
 * 재생기 쪽(비켜주는 쪽)을 고정한다. 두 개가 맞물려야 실제로 동작한다.
 */
class MusicFocusTest {

    @Test
    fun `일시 손실은 멈췄다가 돌아온다`() {
        // 여기서 STOP 을 돌려주면 안내가 끝난 뒤 음악이 영영 돌아오지 않는다.
        assertEquals(
            FocusAction.PAUSE,
            MusicPlayer.focusActionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            FocusAction.RESUME,
            MusicPlayer.focusActionFor(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `영구 손실만 재생을 놓는다`() {
        // 다른 음악 앱이 가져간 경우다. 되찾아오면 두 앱이 소리를 뺏고 뺏긴다.
        assertEquals(
            FocusAction.STOP,
            MusicPlayer.focusActionFor(AudioManager.AUDIOFOCUS_LOSS),
        )

        val stopping = listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_GAIN,
        ).filter { MusicPlayer.focusActionFor(it) == FocusAction.STOP }
        assertEquals(listOf(AudioManager.AUDIOFOCUS_LOSS), stopping)
    }

    @Test
    fun `덕킹 요청에는 볼륨만 낮춘다`() {
        assertEquals(
            FocusAction.DUCK,
            MusicPlayer.focusActionFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        // 0 이면 꺼진 것과 같아 안내가 끝나도 돌아온 줄 모른다. 1 이면 낮춘 의미가 없다.
        assertTrue(MusicPlayer.DUCK_VOLUME > 0f && MusicPlayer.DUCK_VOLUME < 1f)
    }

    @Test
    fun `모르는 포커스 변화에는 손대지 않는다`() {
        assertNull(MusicPlayer.focusActionFor(Int.MIN_VALUE))
    }

    /**
     * 안내 쪽과 재생기 쪽이 실제로 맞물리는지 확인한다.
     * 한쪽만 고쳐도 통과하는 테스트는 이 기능에서 의미가 없다.
     */
    @Test
    fun `일반 안내는 음악을 멈추지 않고 긴급 안내만 멈춘다`() {
        val onNormal = MusicPlayer.focusActionFor(VoiceGuide.gainFor(Priority.NORMAL).asLoss())
        val onInterrupt = MusicPlayer.focusActionFor(VoiceGuide.gainFor(Priority.INTERRUPT).asLoss())
        val onCritical = MusicPlayer.focusActionFor(VoiceGuide.gainFor(Priority.CRITICAL).asLoss())

        assertEquals(FocusAction.DUCK, onNormal)
        assertEquals(FocusAction.DUCK, onInterrupt)
        assertEquals(FocusAction.PAUSE, onCritical)
    }

    /**
     * 안내가 잡는 포커스 종류를, 그때 음악 쪽이 받게 되는 손실 종류로 옮긴다.
     * 시스템이 하는 일을 테스트 안에서 흉내내는 것이다.
     */
    private fun Int.asLoss(): Int = when (this) {
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK ->
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        else -> AudioManager.AUDIOFOCUS_LOSS
    }

    /* ── 곡 넘기기 ───────────────────────────────────────────── */

    @Test
    fun `목록 끝에서 처음으로 돈다`() {
        // "다음 노래" 를 눌렀는데 아무 일도 없으면 버튼이 고장난 것으로 받아들인다.
        assertEquals(0, MusicPlayer.nextIndex(current = 2, size = 3))
        assertEquals(1, MusicPlayer.nextIndex(current = 0, size = 3))
    }

    @Test
    fun `처음에서 앞으로 가면 끝으로 돈다`() {
        assertEquals(2, MusicPlayer.previousIndex(current = 0, size = 3))
        assertEquals(0, MusicPlayer.previousIndex(current = 1, size = 3))
    }

    @Test
    fun `목록이 비어도 인덱스를 벗어나지 않는다`() {
        // 권한을 막 허락한 직후 등 목록이 비어 있는 순간에 눌릴 수 있다.
        assertEquals(0, MusicPlayer.nextIndex(current = 0, size = 0))
        assertEquals(0, MusicPlayer.previousIndex(current = 0, size = 0))
    }

    @Test
    fun `한 곡뿐이면 제자리다`() {
        assertEquals(0, MusicPlayer.nextIndex(current = 0, size = 1))
        assertEquals(0, MusicPlayer.previousIndex(current = 0, size = 1))
    }
}
