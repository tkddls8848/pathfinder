package kr.eodiga.wayfinder.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
 *  - 음성은 알람 채널이 아니라 길안내 속성으로 내보내, 통화를 막지 않고 음악만 줄인다.
 *  - 같은 문장을 연속으로 반복하지 않는다 (폴링 때문에 같은 상태가 계속 들어온다).
 *  - 중요한 안내(하차·벨)는 앞의 문장을 잘라내고 즉시 말한다.
 *  - 엔진이 주는 기본 보이스를 그대로 쓰지 않고, 한국어 보이스 중 가장 좋은 것을 고른다.
 *    ([selectBestVoice] 주석 참고)
 */
@Singleton
class VoiceGuide @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L

    private var initializing = false
    private val pendingReadyCallbacks = mutableListOf<(Boolean) -> Unit>()

    /**
     * 실제로 선택된 보이스. 기기마다 목록이 달라 "어떤 목소리로 나가는지" 를
     * 설정 화면이나 문의 대응에서 확인할 수 있어야 한다. 못 고르면 null (엔진 기본값).
     */
    var activeVoice: VoiceCandidate? = null
        private set

    /** 같은 문장을 이 시간 안에는 다시 말하지 않는다. */
    private val repeatSuppressionMs = 20_000L

    /**
     * TTS 엔진의 onInit 은 바인더 스레드로 오기도 하고, 일부 기기에서는 생성자 안에서
     * 곧바로 불리기도 한다. 설정 작업을 메인 스레드로 미뤄야 [tts] 대입이 끝난 뒤에 돈다.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(onReady: (Boolean) -> Unit = {}) {
        if (ready) {
            onReady(true)
            return
        }
        pendingReadyCallbacks += onReady
        if (initializing) return

        initializing = true
        openEngine(enginePackage = null, remainingEngines = null)
    }

    /**
     * @param enginePackage null 이면 사용자가 시스템 설정에서 고른 기본 엔진.
     * @param remainingEngines 대체 엔진 후보. null 이면 아직 목록을 읽지 않았다는 뜻이다.
     */
    private fun openEngine(enginePackage: String?, remainingEngines: List<String>?) {
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post { onEngineInit(status, enginePackage, remainingEngines) }
        }
        val engine = runCatching {
            if (enginePackage == null) {
                TextToSpeech(context, listener)
            } else {
                TextToSpeech(context, listener, enginePackage)
            }
        }.getOrNull()

        if (engine == null) {
            finishInit(false)
            return
        }
        tts = engine
    }

    private fun onEngineInit(
        status: Int,
        enginePackage: String?,
        remainingEngines: List<String>?,
    ) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            if (!tryNextEngine(engine, remainingEngines)) finishInit(false)
            return
        }

        val langStatus = runCatching { engine.setLanguage(Locale.KOREAN) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val koreanUsable = langStatus != TextToSpeech.LANG_MISSING_DATA &&
            langStatus != TextToSpeech.LANG_NOT_SUPPORTED

        if (!koreanUsable) {
            // 사용자가 고른 엔진을 함부로 바꾸지는 않지만, 한국어를 아예 못 하면
            // 안내가 통째로 사라진다. 그때만 설치된 다른 엔진을 시도한다.
            val candidates = remainingEngines ?: alternativeEngines(engine, enginePackage)
            if (!tryNextEngine(engine, candidates)) finishInit(false)
            return
        }

        activeVoice = applyBestVoice(engine)
        engine.setSpeechRate(SPEECH_RATE)
        engine.setPitch(1.0f)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        finishInit(true)
    }

    /**
     * 다음 후보 엔진으로 넘어간다. 더 시도할 후보가 없으면 false.
     * 후보 목록은 [alternativeEngines] 에서 이미 [MAX_ENGINE_FALLBACKS] 개로 잘라 둔다.
     */
    private fun tryNextEngine(current: TextToSpeech?, remainingEngines: List<String>?): Boolean {
        val next = remainingEngines?.firstOrNull() ?: return false
        current?.let { runCatching { it.shutdown() } }
        tts = null
        openEngine(next, remainingEngines.drop(1))
        return true
    }

    /** 지금 엔진을 뺀 설치된 TTS 엔진 목록. */
    private fun alternativeEngines(engine: TextToSpeech, enginePackage: String?): List<String> {
        val current = enginePackage ?: runCatching { engine.defaultEngine }.getOrNull()
        return runCatching { engine.engines }.getOrNull().orEmpty()
            .mapNotNull { it.name }
            .filter { it != current }
            .take(MAX_ENGINE_FALLBACKS)
    }

    /** 한국어 보이스 중 가장 좋은 것을 골라 엔진에 적용한다. 실패하면 엔진 기본값을 쓴다. */
    private fun applyBestVoice(engine: TextToSpeech): VoiceCandidate? {
        // 일부 기기의 getVoices() 는 NPE 를 던진다. 여기서 죽으면 안내가 통째로 없어진다.
        val available = runCatching { engine.voices }.getOrNull().orEmpty().filterNotNull()
        val best = selectBestVoice(available.map { it.toCandidate() }) ?: return null
        val voice = available.firstOrNull { runCatching { it.name }.getOrNull() == best.name }
            ?: return null
        val applied = runCatching { engine.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR)
        return if (applied == TextToSpeech.SUCCESS) best else null
    }

    private fun finishInit(success: Boolean) {
        initializing = false
        ready = success
        if (!success) {
            // 쓸 수 없는 엔진을 붙들고 있으면 바인딩만 남는다.
            tts?.let { runCatching { it.shutdown() } }
            tts = null
            activeVoice = null
        }
        val callbacks = pendingReadyCallbacks.toList()
        pendingReadyCallbacks.clear()
        callbacks.forEach { it(success) }
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
        initializing = false
        activeVoice = null
        pendingReadyCallbacks.clear()
    }

    fun setProgressListener(listener: UtteranceProgressListener?) {
        tts?.setOnUtteranceProgressListener(listener)
    }

    companion object {

        /** 기본값은 어르신에게 빠르다. */
        const val SPEECH_RATE = 0.85f

        /** 한국어를 못 하는 기본 엔진을 만났을 때 시도해 볼 대체 엔진 수. */
        private const val MAX_ENGINE_FALLBACKS = 2

        /**
         * 한국어 보이스 중 가장 자연스러운 것을 고른다.
         *
         * 엔진이 language 만 보고 주는 기본 보이스는 대개 품질이 가장 낮은 embedded
         * 보이스다. 안내가 로봇처럼 들리는 주된 이유가 이것이고, 같은 엔진 안에
         * 더 좋은 보이스가 이미 깔려 있는 경우가 많다.
         *
         * 네트워크 보이스는 품질이 더 높더라도 뒤로 미룬다. 하차 안내가 꼭 나가야 하는
         * 순간은 어르신이 버스 안에 있을 때이고, 그때 데이터가 끊기면 안내가 통째로
         * 사라진다. 로컬 보이스가 하나도 없을 때만 네트워크 보이스를 쓴다.
         *
         * 정렬 기준은 품질 내림차순 → 지연 오름차순 → 이름 순이다. 마지막 이름 비교는
         * 품질·지연이 같은 보이스가 여러 개일 때 실행할 때마다 목소리가 바뀌지 않게 한다.
         * 어르신에게는 목소리가 일정한 것 자체가 안내의 일부다.
         */
        fun selectBestVoice(candidates: List<VoiceCandidate>): VoiceCandidate? {
            val korean = candidates.filter { it.isKorean && !it.notInstalled }
            val usable = korean.filter { !it.requiresNetwork }.ifEmpty { korean }
            return usable.sortedWith(
                compareByDescending<VoiceCandidate> { it.quality }
                    .thenBy { it.latency }
                    .thenBy { it.name },
            ).firstOrNull()
        }

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

/**
 * 보이스 후보.
 *
 * [android.speech.tts.Voice] 는 단위 테스트에서 만들 수 없고 (getter 가 전부 스텁),
 * 기기에 따라 getter 가 예외를 던지기도 한다. 선택 규칙만 값 객체로 떼어내
 * 순수 함수로 검증할 수 있게 한다.
 */
data class VoiceCandidate(
    val name: String,
    val language: String,
    val quality: Int,
    val latency: Int,
    val requiresNetwork: Boolean,
    val notInstalled: Boolean,
) {
    /** 엔진에 따라 locale 언어를 "ko" 로도, ISO3 "kor" 로도 준다. */
    val isKorean: Boolean
        get() = language.equals("ko", ignoreCase = true) ||
            language.equals("kor", ignoreCase = true)
}

private fun Voice.toCandidate(): VoiceCandidate = VoiceCandidate(
    name = runCatching { name }.getOrNull().orEmpty(),
    language = runCatching { locale?.language }.getOrNull().orEmpty(),
    quality = runCatching { quality }.getOrDefault(Voice.QUALITY_NORMAL),
    latency = runCatching { latency }.getOrDefault(Voice.LATENCY_NORMAL),
    requiresNetwork = runCatching { isNetworkConnectionRequired }.getOrDefault(false),
    notInstalled = runCatching {
        features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
    }.getOrDefault(false),
)
