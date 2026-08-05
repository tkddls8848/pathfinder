package kr.eodiga.wayfinder

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import kr.eodiga.wayfinder.service.JourneyController
import kr.eodiga.wayfinder.service.JourneyGuidanceService
import kr.eodiga.wayfinder.service.VoiceGuide
import kr.eodiga.wayfinder.ui.nav.EodigaNavHost
import kr.eodiga.wayfinder.ui.nav.NavigationStore
import kr.eodiga.wayfinder.ui.theme.EodigaTheme
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var navigationStore: NavigationStore
    @Inject lateinit var voiceGuide: VoiceGuide
    @Inject lateinit var controller: JourneyController

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 결과는 HomeViewModel 이 재조회한다 */ }

    /**
     * 음성 입력.
     *
     * 온디바이스 인식기를 쓰면 네트워크 없이도 동작하고 응답이 빠르다.
     * 어르신은 발화가 느리고 중간에 쉬는 구간이 길어, 침묵 종료 시간을 넉넉히 잡는다.
     */
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?: return@registerForActivityResult
        navigationStore.lastVoiceQuery.value = spoken
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 안내 중에 화면이 꺼지면 어르신이 당황한다. 안내 화면에서만 켜 둔다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        voiceGuide.initialize()

        setContent {
            EodigaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val store = remember { navigationStore }
                    EodigaNavHost(
                        store = store,
                        onStartVoiceInput = ::launchVoiceInput,
                        onRequestLocationPermission = ::requestCorePermissions,
                        onStartJourney = { journey ->
                            controller.start(journey)
                            JourneyGuidanceService.start(this)
                        },
                    )
                }
            }
        }

        requestCorePermissions()
    }

    override fun onDestroy() {
        // 안내가 진행 중이면 서비스가 TTS 를 계속 쓰므로 여기서 종료하지 않는다.
        if (!controller.state.value.isActive) {
            voiceGuide.shutdown()
        }
        super.onDestroy()
    }

    private fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "어디에 가시나요?")
            // 어르신의 느린 발화를 중간에 끊지 않도록 침묵 허용 시간을 늘린다.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { voiceLauncher.launch(intent) }
    }

    private fun requestCorePermissions() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
