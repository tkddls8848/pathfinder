package kr.eodiga.wayfinder.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 진동 알림.
 *
 * 난청이 있거나 버스 안이 시끄러우면 음성이 닿지 않는다.
 * 진동 패턴 자체가 의미를 갖도록 세 가지만 쓴다. 많이 만들면 구분이 안 된다.
 */
@Singleton
class Haptics @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** 가벼운 확인. 화면이 바뀌었음을 알린다. */
    fun tick() = vibrate(longArrayOf(0, 40))

    /** 버스가 곧 온다 — 짧게 두 번. */
    fun busApproaching() = vibrate(longArrayOf(0, 300, 150, 300))

    /** 하차 준비 — 길게 세 번. 가장 강한 신호. */
    fun prepareToAlight() = vibrate(longArrayOf(0, 500, 200, 500, 200, 500))

    private fun vibrate(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
