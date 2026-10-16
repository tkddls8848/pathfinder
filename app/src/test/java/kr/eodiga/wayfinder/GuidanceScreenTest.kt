package kr.eodiga.wayfinder

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kr.eodiga.wayfinder.core.FailureReason
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.BusArrival
import kr.eodiga.wayfinder.domain.model.BusKind
import kr.eodiga.wayfinder.domain.model.BusRoute
import kr.eodiga.wayfinder.domain.model.BusStop
import kr.eodiga.wayfinder.domain.model.GuidanceStage
import kr.eodiga.wayfinder.domain.model.Journey
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.domain.model.Place
import kr.eodiga.wayfinder.domain.model.PlaceKind
import kr.eodiga.wayfinder.service.JourneyController
import kr.eodiga.wayfinder.service.JourneyState
import kr.eodiga.wayfinder.ui.screens.GuidanceScreen
import kr.eodiga.wayfinder.ui.screens.GuidanceViewModel
import kr.eodiga.wayfinder.ui.theme.EodigaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 안내 화면이 [JourneyState] 를 그대로 비추는지 본다.
 *
 * 화면의 글자는 [kr.eodiga.wayfinder.ui.components.Text] 가 워드 조이너를 끼워 넣어
 * 텍스트로는 찾을 수 없다. 대신 톡백이 읽는 contentDescription 으로 찾는다 —
 * 어르신이 귀로 듣는 문장이 곧 검증 대상이기도 하다.
 *
 * 앱 클래스는 Hilt 를 띄우므로 평범한 [Application] 으로 바꿔 끼운다.
 * SDK 35 는 Robolectric 에서 JDK 21 을 요구해 34 로 고정한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuidanceScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val boardStop = BusStop(25, "DJB8001793", "시청역", LatLng(36.3504, 127.3845))
    private val alightStop = BusStop(25, "DJB8002110", "한빛병원앞", LatLng(36.3621, 127.3562))
    private val destination = Place(
        name = "한빛병원",
        location = LatLng(36.3630, 127.3555),
        kind = PlaceKind.HOSPITAL,
    )
    private val journey = Journey(
        destination = destination,
        legs = listOf(
            JourneyLeg.Walk(
                to = boardStop.location,
                distanceMeters = 300,
                destinationName = "시청역 정류장",
                durationMinutes = 5,
            ),
            JourneyLeg.Bus(
                route = BusRoute(25, "DJB30300002", "102", BusKind.TRUNK),
                boardStop = boardStop,
                alightStop = alightStop,
                stopCount = 6,
                intermediateStops = emptyList(),
                durationMinutes = 15,
            ),
        ),
    )

    private val state = MutableStateFlow(JourneyState(journey = journey, isActive = true))
    private val controller = mockk<JourneyController>(relaxed = true) {
        every { state } returns this@GuidanceScreenTest.state
    }
    private val places = mockk<PlaceRepository>(relaxed = true)

    private var finished = 0
    private var lost = 0

    private fun newViewModel() = GuidanceViewModel(
        context = rule.activity.applicationContext,
        controller = controller,
        guardianNotifier = mockk(),
        places = places,
        location = mockk(),
        regions = mockk(),
    )

    private fun show(stage: GuidanceStage, configure: (JourneyState) -> JourneyState = { it }) {
        state.value = configure(state.value.copy(stage = stage))
        val viewModel = newViewModel()
        rule.setContent {
            EodigaTheme(darkTheme = false) {
                GuidanceScreen(
                    onFinished = { finished++ },
                    onLost = { lost++ },
                    viewModel = viewModel,
                )
            }
        }
    }

    /** 안내 내용은 스크롤 안에 있어 큰 글씨에서는 화면 밖으로 밀린다. 굴려서 누른다. */
    private fun tap(label: String) {
        rule.onNodeWithContentDescription(label).performScrollTo().performClick()
    }

    private fun exists(label: String, substring: Boolean = false) =
        rule.onNodeWithContentDescription(label, substring = substring).assertExists()

    private fun absent(label: String, substring: Boolean = false) =
        rule.onNodeWithContentDescription(label, substring = substring).assertDoesNotExist()

    @Test
    fun `도보 안내는 남은 거리를 걸음으로 보여주고 다시 들려준다`() {
        show(GuidanceStage.Walking(legIndex = 0, remainingMeters = 150))

        exists("걸어가세요")
        exists("230") // 150m ÷ 보폭 0.65m
        exists("시청역 정류장")

        tap("다시 들려주세요")
        verify(exactly = 1) { controller.repeatGuidance() }
    }

    @Test
    fun `정류장 도착 확인은 낭독용 문장으로 눌러도 컨트롤러에 전달된다`() {
        show(GuidanceStage.ArrivedAtStop(legIndex = 0))

        exists("시청역 정류장")
        tap("네, 여기가 맞아요")
        verify(exactly = 1) { controller.confirmAtStop() }
    }

    @Test
    fun `버스 번호는 전광판과 대조할 수 있게 한 자리씩 읽는다`() {
        show(GuidanceStage.WaitingForBus(legIndex = 1, arrival = null))

        exists("102")
        exists("일 공 이번 버스")
        exists("파란색 간선버스")
    }

    @Test
    fun `도착 정보가 오기 전에는 알아보는 중이라고 말한다`() {
        show(GuidanceStage.WaitingForBus(legIndex = 1, arrival = null))

        exists("도착 시간을 알아보는 중입니다")
    }

    @Test
    fun `도착 정보를 못 받으면 알아보는 중 대신 실패 이유를 말한다`() {
        show(GuidanceStage.WaitingForBus(legIndex = 1, arrival = null)) {
            it.copy(failure = FailureReason.NoNetwork)
        }

        exists(FailureReason.NoNetwork.spokenMessage)
        absent("도착 시간을 알아보는 중입니다")
    }

    @Test
    fun `저상버스가 오면 남은 분과 함께 계단이 없다고 알린다`() {
        show(
            GuidanceStage.WaitingForBus(
                legIndex = 1,
                arrival = BusArrival(arrivalSeconds = 180, vehicleType = "저상버스"),
            ),
        )

        exists("3분 뒤에 와요")
        exists("계단 없는 저상버스입니다")
    }

    @Test
    fun `승차 확인은 탔어요와 못 탔어요를 각각 전달한다`() {
        show(GuidanceStage.Boarding(legIndex = 1))

        tap("네, 탔어요")
        tap("아직 못 탔어요")

        verify(exactly = 1) { controller.confirmBoarded() }
        verify(exactly = 1) { controller.cancelBoarding() }
    }

    @Test
    fun `다음 정류장을 모르면 비워 두지 않고 확인 중이라고 쓴다`() {
        show(GuidanceStage.Riding(legIndex = 1, stopsRemaining = 4, nextStopName = null))

        exists("네 정거장 남았어요")
        exists("확인 중")
    }

    @Test
    fun `차량을 확정하지 못하면 알림을 약속하지 않고 내릴 곳을 기사님께 말하게 한다`() {
        show(GuidanceStage.RidingUnknownVehicle(legIndex = 1, alightStopName = "한빛병원앞"))

        exists("한빛병원앞")
        exists("기사님께 한빛병원앞에서", substring = true)
        absent("크게 알려드릴게요", substring = true)

        tap("내렸어요")
        verify(exactly = 1) { controller.confirmAlighted() }
    }

    @Test
    fun `벨 단계에서 내렸어요를 누르면 하차를 확정한다`() {
        show(GuidanceStage.RingBell(legIndex = 1))

        exists("🔔 벨을\n누르세요")
        tap("내렸어요")
        verify(exactly = 1) { controller.confirmAlighted() }
    }

    @Test
    fun `길을 잃었어요 버튼은 안내 내내 있고 누르면 도움 화면으로 간다`() {
        show(GuidanceStage.Riding(legIndex = 1, stopsRemaining = 4, nextStopName = "중구청"))

        rule.onNodeWithContentDescription("길을 잃었어요", substring = true).performClick()
        assertEquals(1, lost)
    }

    @Test
    fun `아무것도 누르지 않아도 상태가 바뀌면 화면이 따라가고 도착하면 긴급 버튼을 감춘다`() {
        show(GuidanceStage.PrepareToAlight(legIndex = 1, stopsRemaining = 2))
        exists("2정거장 남았습니다")
        exists("길을 잃었어요", substring = true)

        state.value = state.value.copy(stage = GuidanceStage.Arrived)
        rule.waitForIdle()

        absent("2정거장 남았습니다")
        exists("바로 앞입니다")
        absent("길을 잃었어요", substring = true)
    }

    @Test
    fun `도착 화면에서 처음으로를 누르면 방문을 기록하고 안내를 끝낸다`() {
        show(GuidanceStage.Arrived)

        exists("한빛병원")
        tap("처음으로")
        rule.waitForIdle()

        coVerify(exactly = 1) { places.recordVisit(destination) }
        verify(exactly = 1) { controller.stop() }
        assertEquals(1, finished)
    }

    @Test
    fun `안내 화면에 있는 동안만 화면을 켜 둔다`() {
        state.value = state.value.copy(stage = GuidanceStage.Walking(0, 150))
        val viewModel = newViewModel()
        var onGuidance by mutableStateOf(true)
        rule.setContent {
            EodigaTheme(darkTheme = false) {
                if (onGuidance) {
                    GuidanceScreen(onFinished = {}, onLost = {}, viewModel = viewModel)
                }
            }
        }
        val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        fun screenKeptOn() = rule.activity.window.attributes.flags and keepOn != 0

        rule.waitForIdle()
        assertTrue(screenKeptOn())

        onGuidance = false
        rule.waitForIdle()
        assertFalse(screenKeptOn())
    }
}
