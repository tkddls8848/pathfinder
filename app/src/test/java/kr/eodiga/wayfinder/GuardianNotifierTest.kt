package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.domain.model.LatLng
import kr.eodiga.wayfinder.guardian.GuardianNotifier
import kr.eodiga.wayfinder.guardian.buildLostMessage
import kr.eodiga.wayfinder.ui.screens.LostResultTone
import kr.eodiga.wayfinder.ui.screens.presentLostResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class GuardianNotifierTest {

    @Test
    fun `긴급 문자는 주소와 지도 좌표를 함께 넣는다`() {
        val message = buildLostMessage(
            location = LatLng(36.3315, 127.4342),
            addressText = "대전 동구 중앙로 215",
            sentAt = Date(0),
        )

        assertTrue(message.contains("현재 위치: 대전 동구 중앙로 215"))
        assertTrue(message.contains("https://maps.google.com/?q=36.3315,127.4342"))
    }

    @Test
    fun `문자와 전화가 모두 실패하면 성공 색이나 연결 중 문구를 쓰지 않는다`() {
        val result = GuardianNotifier.Result(
            smsStatus = GuardianNotifier.SmsStatus.FAILED,
            callStarted = false,
            guardianName = "아들",
            locationIncluded = true,
        )

        val presentation = presentLostResult(result)

        assertEquals(LostResultTone.ERROR, presentation.tone)
        assertTrue(presentation.message.contains("시작하지 못했습니다"))
        assertFalse(presentation.message.contains("연결하고 있습니다"))
    }

    @Test
    fun `문자 앱만 열었으면 전송 완료가 아니라 사용자 동작 필요로 표시한다`() {
        val result = GuardianNotifier.Result(
            smsStatus = GuardianNotifier.SmsStatus.COMPOSER_OPENED,
            callStarted = false,
            guardianName = "아들",
            locationIncluded = true,
        )

        val presentation = presentLostResult(result)

        assertEquals(LostResultTone.WARNING, presentation.tone)
        assertTrue(presentation.message.contains("전송 버튼"))
    }

    @Test
    fun `실제 위치 문자가 접수됐을 때만 성공으로 표시한다`() {
        val result = GuardianNotifier.Result(
            smsStatus = GuardianNotifier.SmsStatus.SENT,
            callStarted = true,
            guardianName = "아들",
            locationIncluded = true,
        )

        val presentation = presentLostResult(result)

        assertEquals(LostResultTone.SUCCESS, presentation.tone)
        assertTrue(presentation.message.contains("현재 위치를 보내고"))
    }
}
