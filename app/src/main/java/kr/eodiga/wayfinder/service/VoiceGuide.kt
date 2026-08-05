package kr.eodiga.wayfinder.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 음성 안내.
 *
 * 화면을 읽기 어려운 어르신에게는 음성이 주 출력이다. 그래서 TTS 를
 * "있으면 좋은 기능" 이 아니라 안내 파이프라인의 1급 출력으로 다룬다.
 *
 * 설계 규칙:
 *  - 속도 0.85. 기본값은 어르신에게 빠르다.
 *  - 음성은 알람 채널이 아니라 통화/미디어와 겹치지 않는 ASSISTANT 속성으로 낸다.
 *  - 같은 문장을 연속으로 반복하지 않는다 (폴링 때문에 같은 상태가 계속 들어온다).
 *  - 중요한 안내(하차·벨)는 앞의 문장을 잘라내고 즉시 말한다.
 */
@Singleton
class VoiceGuide @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L

    /** 같은 문장을 이 시간 안에는 다시 말하지 않는다. */
    private val repeatSuppressionMs = 20_000L

    fun initialize(onReady: (Boolean) -> Unit = {}) {
        if (tts != null) {
            onReady(ready)
            return
        }
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.apply {
                    language = Locale.KOREAN
                    setSpeechRate(0.85f)
                    setPitch(1.0f)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
            }
            onReady(ready)
        }
    }

    /**
     * @param urgent true 면 진행 중인 안내를 끊고 즉시 말한다. 하차·벨 안내용.
     * @param allowRepeat true 면 반복 억제를 무시한다.
     */
    fun speak(text: String, urgent: Boolean = false, allowRepeat: Boolean = false) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return

        val now = System.currentTimeMillis()
        if (!allowRepeat && text == lastSpoken && now - lastSpokenAt < repeatSuppressionMs) return

        lastSpoken = text
        lastSpokenAt = now
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, "eodiga-${now}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun setProgressListener(listener: UtteranceProgressListener?) {
        tts?.setOnUtteranceProgressListener(listener)
    }

    companion object {
        /**
         * 숫자를 어르신이 알아듣기 쉬운 한국어로 바꾼다.
         *
         * TTS 엔진은 "7016" 을 "칠천십육" 으로 읽는다. 버스 번호는 자릿수로
         * 읽어야("칠공일육") 정류장 전광판과 맞춰 들을 수 있다.
         */
        fun busNumberToSpeech(routeNo: String): String {
            val digits = mapOf(
                '0' to "공", '1' to "일", '2' to "이", '3' to "삼", '4' to "사",
                '5' to "오", '6' to "육", '7' to "칠", '8' to "팔", '9' to "구",
            )
            return routeNo.map { ch -> digits[ch] ?: ch.toString() }.joinToString(" ")
        }

        /** 거리를 걸음 수로 바꿔 말한다. "백 미터" 보다 "백오십 걸음" 이 직관적이다. */
        fun distanceToSpeech(meters: Int): String {
            val steps = (meters / 0.65).roundToInt()
            return when {
                steps <= 30 -> "${steps}걸음"
                else -> "약 ${(steps / 10) * 10}걸음"
            }
        }
    }
}
