package kr.eodiga.wayfinder.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.eodiga.wayfinder.data.repository.MusicLibrary
import kr.eodiga.wayfinder.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 기기 음원 재생.
 *
 * 이동 중에 적적하지 않게 해달라는 요구에서 나왔다. 그런데 이 앱에서 절대
 * 놓치면 안 되는 소리가 하차 알림이고, 음악은 정확히 그것과 경쟁한다.
 * 어르신이 노래를 듣다 정류장을 지나치면 앱의 존재 이유가 무너진다.
 *
 * 그 경쟁은 [VoiceGuide] 쪽에서 이미 정리돼 있다. 안내가 나갈 때마다 포커스를
 * 잡고, 급한 정도에 따라 음악을 덕킹하거나 일시정지시킨다([Priority] 참고).
 * **이 클래스가 할 일은 비켜주는 쪽이다** — 포커스를 잃으면 멈추고, 돌려받으면 돌아온다.
 *
 * 안내와 정반대인 규칙이 하나 있다. **포커스를 못 받으면 음악은 재생하지 않는다.**
 * 안내는 못 받아도 말한다(하차 안내가 사라지는 것이 더 나쁘다). 음악은 반대로,
 * 통화나 다른 안내 위에 노래를 얹는 것이 언제나 더 나쁘다.
 */
@Singleton
class MusicPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: MusicLibrary,
) {

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    /**
     * MediaPlayer 는 상태 기계라 호출 순서가 어긋나면 IllegalState 로 죽는다.
     * 포커스 콜백은 바인더 스레드로 오므로, 조작을 전부 메인 큐로 몰아 순서를 고정한다.
     * [VoiceGuide] 가 포커스를 다루는 방식과 같다.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioManager = runCatching {
        context.getSystemService(AudioManager::class.java)
    }.getOrNull()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 포커스를 잠깐 뺏겨 멈춘 것인지. 사용자가 직접 멈춘 것과 구분해야 한다. */
    private var resumeWhenFocusReturns = false

    /** 깨진 파일을 만났을 때 목록 전체를 무한히 돌지 않도록 세어 둔다. */
    private var consecutiveFailures = 0

    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * 목록을 읽는다.
     *
     * **재생 중에는 읽지 않는다.** 목록이 바뀌면 같은 인덱스가 다른 곡을 가리켜
     * 듣던 노래가 이유 없이 바뀐다. 반대로 멈춰 있을 때는 매번 다시 읽는다 —
     * 보호자가 노래를 넣어주고 앱을 다시 열었는데 "없어요" 가 그대로 떠 있으면
     * 넣은 것이 잘못된 줄 안다. 한 번 읽고 마는 캐시는 이 화면에서 쓸 수 없다.
     */
    suspend fun load() {
        if (_state.value.isPlaying) return
        val tracks = library.tracks()
        _state.update { prev ->
            // 목록이 바뀌어도 고른 곡은 그대로 둔다. 사라졌으면 처음으로 돌아간다.
            val keptId = prev.current?.id
            val index = tracks.indexOfFirst { it.id == keptId }.takeIf { it >= 0 } ?: 0
            prev.copy(tracks = tracks, loaded = true, index = index)
        }
    }

    /** 주 동작 하나. 어르신 화면에서 재생과 멈춤은 같은 자리의 같은 버튼이다. */
    fun toggle() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun play() {
        if (_state.value.current == null) return
        // 포커스를 못 받았다면 통화 중이거나 다른 안내가 나가는 중이다. 얹지 않는다.
        if (!acquireFocus()) return
        consecutiveFailures = 0
        startCurrent()
    }

    /** 사용자가 직접 멈춘 것. 포커스가 돌아와도 저절로 다시 켜지지 않는다. */
    fun pause() {
        resumeWhenFocusReturns = false
        pausePlayback()
        releaseFocus()
    }

    fun next() = moveTo(nextIndex(_state.value.index, _state.value.tracks.size))

    fun previous() = moveTo(previousIndex(_state.value.index, _state.value.tracks.size))

    /** 재생기를 통째로 놓는다. 화면을 떠날 때가 아니라 앱이 음악을 그만둘 때 부른다. */
    fun stop() {
        resumeWhenFocusReturns = false
        releasePlayer()
        releaseFocus()
        _state.update { it.copy(isPlaying = false) }
    }

    /* ── 재생 ───────────────────────────────────────────────── */

    private fun moveTo(index: Int) {
        if (_state.value.tracks.isEmpty()) return
        val wasPlaying = _state.value.isPlaying
        releasePlayer()
        _state.update { it.copy(index = index, isPlaying = false) }
        if (wasPlaying) startCurrent()
    }

    private fun startCurrent() {
        val track = _state.value.current ?: return
        releasePlayer()

        val mp = runCatching { MediaPlayer() }.getOrNull() ?: return
        val started = runCatching {
            mp.setAudioAttributes(musicAttributes)
            // 완료 콜백 안에서 곧바로 next() 를 부르면 MediaPlayer 를 자기
            // 콜백 안에서 reset/release 하게 된다. onError 와 같은 이유로 미룬다.
            mp.setOnCompletionListener { mainHandler.post { next() } }
            mp.setOnErrorListener { _, _, _ ->
                // 이 리스너 안에서 곧바로 다음 곡으로 넘어가면 MediaPlayer 를 자기
                // 콜백 안에서 해제하게 된다. 메인 큐로 미뤄 한 박자 뒤에 처리한다.
                mainHandler.post { skipBroken() }
                true
            }
            mp.setOnPreparedListener {
                it.start()
                consecutiveFailures = 0
                _state.update { s -> s.copy(isPlaying = true) }
            }
            mp.setDataSource(context, track.uri)
            // 로컬 파일이라도 prepare() 는 메인 스레드를 붙잡는다. 비동기로 연다.
            mp.prepareAsync()
        }.isSuccess

        if (started) {
            player = mp
        } else {
            runCatching { mp.release() }
            // 곧바로 부르면 목록이 통째로 깨졌을 때 곡 수만큼 재귀가 쌓인다.
            // 메인 큐로 넘겨 한 번에 한 곡씩 평평하게 처리한다.
            mainHandler.post { skipBroken() }
        }
    }

    /**
     * 열리지 않는 파일은 건너뛴다.
     *
     * 어르신에게 "이 파일이 손상되었습니다" 를 띄워봐야 할 수 있는 일이 없다.
     * 다만 목록이 통째로 깨져 있으면 무한히 돌게 되므로 한 바퀴에서 멈춘다.
     */
    private fun skipBroken() {
        releasePlayer()
        val size = _state.value.tracks.size
        consecutiveFailures++
        if (size == 0 || consecutiveFailures >= size) {
            consecutiveFailures = 0
            _state.update { it.copy(isPlaying = false) }
            releaseFocus()
            return
        }
        _state.update { it.copy(index = nextIndex(it.index, size)) }
        // startCurrent 가 다시 실패하면 mainHandler 로 되돌아온다. 재귀가 아니라
        // 큐를 한 바퀴 도는 형태라 목록이 아무리 길어도 스택이 자라지 않는다.
        startCurrent()
    }

    private fun pausePlayback() {
        player?.let { runCatching { if (it.isPlaying) it.pause() } }
        _state.update { it.copy(isPlaying = false) }
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
    }

    private fun setVolume(level: Float) {
        player?.let { runCatching { it.setVolume(level, level) } }
    }

    /* ── 오디오 포커스 ───────────────────────────────────────── */

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post { applyFocusChange(change) }
    }

    internal fun applyFocusChange(change: Int) {
        when (focusActionFor(change)) {
            FocusAction.STOP -> {
                // 다른 앱이 음악을 가져갔다. 되찾아오지 않는다 — 두 앱이 번갈아
                // 소리를 뺏는 상황이 어르신에게 가장 혼란스럽다.
                resumeWhenFocusReturns = false
                releasePlayer()
                releaseFocus()
                _state.update { it.copy(isPlaying = false) }
            }
            FocusAction.PAUSE -> {
                // 긴급 안내(CRITICAL)나 통화. 안내가 끝나면 돌아온다.
                resumeWhenFocusReturns = _state.value.isPlaying
                pausePlayback()
            }
            // 일반 안내다. 시스템이 자동으로 덕킹해 주므로 이 콜백은 대개 오지 않는다
            // (setWillPauseWhenDucked(false)). 직접 낮추는 기기를 대비해 남겨 둔다.
            FocusAction.DUCK -> setVolume(DUCK_VOLUME)
            FocusAction.RESUME -> {
                setVolume(1f)
                if (resumeWhenFocusReturns) {
                    resumeWhenFocusReturns = false
                    player?.let { runCatching { it.start() } }
                    _state.update { it.copy(isPlaying = player != null) }
                }
            }
            null -> Unit
        }
    }

    private fun acquireFocus(): Boolean {
        if (focusRequest != null) return true
        val am = audioManager ?: return false

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(musicAttributes)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            // false 면 일반 안내에서 시스템이 알아서 볼륨을 낮춰준다.
            // true 로 두면 안내 한 마디마다 음악이 통째로 끊겨 훨씬 거슬린다.
            .setWillPauseWhenDucked(false)
            .build()

        val granted = runCatching { am.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false

        focusRequest = request
        return true
    }

    private fun releaseFocus() {
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    companion object {

        /** 일반 안내가 나가는 동안 음악을 이만큼 낮춘다. */
        const val DUCK_VOLUME = 0.2f

        /**
         * 포커스 변화를 재생기가 할 일로 옮긴다.
         *
         * 순수 함수로 떼어낸 이유는 [AudioManager] 상수 조합이 이 기능에서 가장
         * 틀리기 쉬운 자리이기 때문이다. 특히 [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT] 를
         * 영구 손실로 다루면 안내가 끝난 뒤 음악이 영영 돌아오지 않는다.
         */
        fun focusActionFor(change: Int): FocusAction? = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> FocusAction.STOP
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusAction.PAUSE
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.DUCK
            AudioManager.AUDIOFOCUS_GAIN -> FocusAction.RESUME
            else -> null
        }

        /**
         * 목록 끝에서 처음으로 돌아온다.
         *
         * "다음 노래" 를 눌렀는데 아무 일도 일어나지 않으면 어르신은 버튼이
         * 고장난 것으로 받아들인다. 끝을 알리는 것보다 도는 편이 낫다.
         */
        fun nextIndex(current: Int, size: Int): Int =
            if (size <= 0) 0 else (current + 1).mod(size)

        fun previousIndex(current: Int, size: Int): Int =
            if (size <= 0) 0 else (current - 1).mod(size)
    }
}

/** 포커스가 바뀌었을 때 재생기가 할 일. */
enum class FocusAction {
    /** 영구 손실. 놓고 물러난다. */
    STOP,

    /** 일시 손실. 멈췄다가 돌아온다. */
    PAUSE,

    /** 볼륨만 낮춘다. */
    DUCK,

    /** 되찾았다. */
    RESUME,
}

/**
 * 음악 화면이 보는 상태.
 *
 * 목록·현재 곡·재생 여부뿐이다. 진행 위치(seek bar)를 넣지 않은 것은 의도다 —
 * 작은 손잡이를 끄는 조작은 손 떨림이 있는 손으로 거의 불가능하다.
 */
data class MusicState(
    val tracks: List<Track> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    /** 목록을 한 번이라도 읽었는지. 읽기 전과 "노래가 없음" 은 다른 화면이다. */
    val loaded: Boolean = false,
) {
    val current: Track? get() = tracks.getOrNull(index)
}
