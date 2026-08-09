package kr.eodiga.wayfinder.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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
 *  - 말하는 동안 오디오 포커스를 잡아 다른 앱 소리를 비킨다. 얼마나 세게 비키게 할지는
 *    [Priority] 가 정한다.
 */
@Singleton
class VoiceGuide @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var lastSpoken: String? = null
    private var lastSpokenAt: Long = 0L

    private val audioManager = runCatching {
        context.getSystemService(AudioManager::class.java)
    }.getOrNull()

    /** 지금 잡고 있는 포커스. 없으면 null. */
    private var focusRequest: AudioFocusRequest? = null
    private var heldGain: Int? = null

    /** 아직 끝나지 않은 발화. 비면 포커스를 놓는다. */
    private val pending = mutableSetOf<String>()

    /** 발화 id 는 겹치면 안 된다. 같은 밀리초에 두 번 불릴 수 있다. */
    private val utteranceSeq = AtomicLong()


    private var initializing = false
    private val pendingReadyCallbacks = mutableListOf<(Boolean) -> Unit>()

    private val _activeVoice = MutableStateFlow<VoiceCandidate?>(null)

    /**
     * 실제로 선택된 보이스. 기기마다 목록이 달라 "어떤 목소리로 나가는지" 를
     * 설정 화면이나 문의 대응에서 확인할 수 있어야 한다. 못 고르면 null (엔진 기본값).
     *
     * 초기화가 비동기라 화면이 먼저 그려질 수 있다. 그래서 값이 아니라 흐름으로 낸다.
     */
    val activeVoice: StateFlow<VoiceCandidate?> = _activeVoice.asStateFlow()

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

        _activeVoice.value = applyBestVoice(engine)
        engine.setSpeechRate(SPEECH_RATE)
        engine.setPitch(1.0f)
        engine.setAudioAttributes(guidanceAttributes)
        engine.setOnUtteranceProgressListener(progressListener)
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
            _activeVoice.value = null
        }
        val callbacks = pendingReadyCallbacks.toList()
        pendingReadyCallbacks.clear()
        callbacks.forEach { it(success) }
    }

    /**
     * @param priority 다른 앱 소리를 얼마나 비키게 할지, 앞 문장을 자를지를 정한다.
     * @param allowRepeat true 면 반복 억제를 무시한다.
     */
    fun speak(
        text: String,
        priority: Priority = Priority.NORMAL,
        allowRepeat: Boolean = false,
    ) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return

        val now = System.currentTimeMillis()
        if (!allowRepeat && text == lastSpoken && now - lastSpokenAt < repeatSuppressionMs) return

        lastSpoken = text
        lastSpokenAt = now

        val id = "eodiga-${utteranceSeq.incrementAndGet()}"
        val flush = priority.interruptsOwnSpeech

        synchronized(pending) {
            // 앞 문장을 자르면 그 문장의 onDone 은 오지 않는다. 비워주지 않으면
            // pending 에 유령이 남아 포커스를 영원히 붙들게 된다.
            if (flush) pending.clear()
            pending += id
        }

        // speak() 는 안내 루프(백그라운드 코루틴)에서 불리고 포커스 해제는 메인에서 돈다.
        // 둘이 같은 필드를 만지므로 포커스 조작은 전부 메인 큐로 몰아 순서를 고정한다.
        mainHandler.post { acquireFocus(priority) }
        armWatchdog()

        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val spoken = runCatching { engine.speak(text, mode, null, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (spoken != TextToSpeech.SUCCESS) utteranceFinished(id)
    }

    fun stop() {
        tts?.stop()
        clearPending()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        initializing = false
        _activeVoice.value = null
        pendingReadyCallbacks.clear()
        clearPending()
    }

    /* ── 오디오 포커스 ───────────────────────────────────────
     *
     * 이 앱에서 절대 놓치면 안 되는 소리가 하차 안내다. 어르신이 라디오를 듣다
     * 정류장을 지나치면 앱의 존재 이유가 없어진다. 그래서 안내를 낼 때마다
     * 포커스를 잡되, 얼마나 세게 잡을지는 안내의 성격에 따라 나눈다.
     *
     *   일반 (도보·대기·이동중) → 덕킹. 음악 볼륨만 내려간다.
     *   긴급 (벨·하차·하차준비) → 일시정지. 안내가 끝나면 음악이 돌아온다.
     *
     * 포커스를 못 받아도 말은 한다. 다른 앱이 배타 포커스를 쥐고 있다는 이유로
     * 하차 안내가 통째로 사라지는 것이 훨씬 나쁘다.
     */

    private val guidanceAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            // 전화가 왔거나 다른 앱이 가져갔다. 안내를 얹어 봐야 둘 다 안 들린다.
            mainHandler.post { stop() }
        }
    }

    private fun acquireFocus(priority: Priority) {
        val am = audioManager ?: return
        val gain = gainFor(priority)
        if (heldGain == gain) return
        // 안내가 이어지는 중에 약한 포커스로 내려가지 않는다.
        // 긴급 안내 뒤에 일반 안내가 붙는다고 음악이 도로 커지면 안 된다.
        if (heldGain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT &&
            gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        ) {
            return
        }

        releaseFocus()
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(guidanceAttributes)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .setWillPauseWhenDucked(false)
            .build()

        val granted = runCatching { am.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            heldGain = gain
        }
    }

    private fun releaseFocus() {
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        heldGain = null
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceFinished(utteranceId)
        }

        @Deprecated("엔진이 오래된 콜백만 부르는 경우가 있어 남겨 둔다.")
        override fun onError(utteranceId: String?) {
            utteranceFinished(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceFinished(utteranceId)
        }
    }

    /** TTS 콜백은 바인더 스레드로 온다. 포커스 조작은 메인으로 넘긴다. */
    private fun utteranceFinished(utteranceId: String?) {
        val empty = synchronized(pending) {
            if (utteranceId != null) pending -= utteranceId
            pending.isEmpty()
        }
        if (empty) mainHandler.post { if (isIdle()) releaseFocus() }
    }

    private fun isIdle(): Boolean = synchronized(pending) { pending.isEmpty() }

    private fun clearPending() {
        synchronized(pending) { pending.clear() }
        mainHandler.removeCallbacks(watchdog)
        mainHandler.post { releaseFocus() }
    }

    /**
     * 엔진이 [onDone] 을 끝내 부르지 않는 경우가 있다. 그대로 두면 음악이
     * 영원히 멈춰 있게 된다 — 어르신은 왜 라디오가 안 나오는지 알 방법이 없다.
     * 안내 한 마디가 이 시간을 넘길 리 없으므로, 넘으면 엔진이 죽은 것으로 본다.
     */
    private val watchdog = Runnable {
        synchronized(pending) { pending.clear() }
        releaseFocus()
    }

    private fun armWatchdog() {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, FOCUS_WATCHDOG_MS)
    }

    companion object {

        /** 기본값은 어르신에게 빠르다. */
        const val SPEECH_RATE = 0.85f

        /** 한국어를 못 하는 기본 엔진을 만났을 때 시도해 볼 대체 엔진 수. */
        private const val MAX_ENGINE_FALLBACKS = 2

        /**
         * 발화 완료 콜백을 이 시간까지 기다린다. 넘으면 포커스를 강제로 놓는다.
         * 가장 긴 안내(대기 안내 + 저상버스 문장)가 12초 남짓이다. 넉넉히 잡았다.
         */
        private const val FOCUS_WATCHDOG_MS = 30_000L

        /**
         * 고른 보이스를 사람이 읽을 한 줄로. 설정 화면과 문의 대응용이다.
         *
         * 이 값이 필요한 이유는 하나다 — **기기마다 다른 목소리가 잡힌다.**
         * "소리가 이상하다" 는 문의가 왔을 때 어떤 보이스가 물렸는지 모르면
         * 재현할 방법이 없다. 보호자가 화면을 읽어 전해줄 수 있어야 한다.
         */
        fun describeVoice(voice: VoiceCandidate?): String {
            if (voice == null) return "엔진 기본 목소리"
            val quality = when {
                voice.quality >= Voice.QUALITY_VERY_HIGH -> "아주 좋음"
                voice.quality >= Voice.QUALITY_HIGH -> "좋음"
                voice.quality >= Voice.QUALITY_NORMAL -> "보통"
                voice.quality >= Voice.QUALITY_LOW -> "낮음"
                else -> "아주 낮음"
            }
            val where = if (voice.requiresNetwork) "인터넷 필요" else "기기에 저장됨"
            return "$quality · $where"
        }

        /** [Priority] 를 안드로이드 오디오 포커스 종류로 옮긴다. */
        fun gainFor(priority: Priority): Int =
            if (priority.pausesOtherAudio) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }

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

        /**
         * 수를 순우리말 수관형사로 바꾼다.
         *
         * 한국어 TTS 는 `3정거장` 을 "삼 정거장" 으로 읽는다. 그러나 정거장·걸음은
         * 순우리말 수관형사를 쓰는 단위라 **"세 정거장"** 이어야 한다.
         * ("분" 은 한자어라 "삼 분" 이 맞으므로 이 함수를 쓰지 않는다.)
         *
         * 백부터는 순우리말 수사가 현대어에서 사실상 쓰이지 않는다 — "백쉰 걸음" 이라고
         * 말하는 사람은 없다. 그 위로는 숫자를 그대로 넘겨 TTS 가 한자어로 읽게 둔다.
         */
        fun nativeCountToSpeech(count: Int): String {
            if (count !in 1..99) return count.toString()
            val tens = when (count / 10) {
                0 -> ""
                1 -> "열"
                // 단독은 "스무 정거장", 뒤에 단위수가 붙으면 "스물세 정거장".
                2 -> if (count % 10 == 0) "스무" else "스물"
                3 -> "서른"
                4 -> "마흔"
                5 -> "쉰"
                6 -> "예순"
                7 -> "일흔"
                8 -> "여든"
                else -> "아흔"
            }
            val ones = when (count % 10) {
                0 -> ""
                1 -> "한"
                2 -> "두"
                3 -> "세"
                4 -> "네"
                5 -> "다섯"
                6 -> "여섯"
                7 -> "일곱"
                8 -> "여덟"
                else -> "아홉"
            }
            return tens + ones
        }

        /** "3정거장" → "세 정거장". 단위까지 붙여 돌려준다. */
        fun stopCountToSpeech(stops: Int): String = "${nativeCountToSpeech(stops)} 정거장"

        /**
         * 거리를 걸음 수로 바꿔 말한다. "백 미터" 보다 "백오십 걸음" 이 직관적이다.
         *
         * 서른 걸음 이하는 실제로 세어볼 수 있는 거리라 그대로 말하고,
         * 그 위는 십 단위로 뭉갠다. GPS 오차를 생각하면 한 걸음 단위는 어차피 거짓말이다.
         */
        fun distanceToSpeech(meters: Int): String {
            val steps = (meters / 0.65).roundToInt().coerceAtLeast(1)
            val rounded = if (steps <= 30) steps else (steps / 10) * 10
            return "${nativeCountToSpeech(rounded)} 걸음"
        }
    }
}

/**
 * 안내의 급함.
 *
 * 두 가지를 한꺼번에 정한다 — 앞 문장을 자를지, 다른 앱 소리를 얼마나 비키게 할지.
 * 이 둘은 실제로 같이 움직인다. 지금 당장 들려야 하는 말일수록 앞 문장도 자르고
 * 음악도 더 세게 비켜야 한다.
 *
 * 등급을 셋으로 끊은 이유는 [CRITICAL] 을 아껴 쓰기 위해서다. 음악을 멈추는 것은
 * 어르신 입장에서 꽤 큰 개입이라, 몇 초 안에 몸을 움직여야 하는 안내에만 쓴다.
 * 그 외에 "지금 알려야 하는" 정도는 [INTERRUPT] 로 충분하다.
 */
enum class Priority(
    /** 앞서 말하던 문장을 끊고 즉시 말한다. */
    val interruptsOwnSpeech: Boolean,
    /** 음악·라디오를 멈춘다. false 면 볼륨만 낮춘다(덕킹). */
    val pausesOtherAudio: Boolean,
) {
    /** 도보·대기·이동중. 앞 문장 뒤에 붙는다. */
    NORMAL(interruptsOwnSpeech = false, pausesOtherAudio = false),

    /** 상태가 막 바뀌어 지금 알려야 하는 것. 음악은 볼륨만 낮춘다. */
    INTERRUPT(interruptsOwnSpeech = true, pausesOtherAudio = false),

    /** 벨·하차준비·하차·승차신호. 몇 초 안에 움직여야 한다. */
    CRITICAL(interruptsOwnSpeech = true, pausesOtherAudio = true),
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
