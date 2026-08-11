package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.data.remote.redactSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 디버그 빌드의 HTTP 로그는 BASIC 레벨이라 요청 URL 을 통째로 찍는다.
 * 공공데이터포털은 인증키를 쿼리 파라미터로 받으므로, 마스킹이 깨지면
 * 인증키가 logcat 에 그대로 남는다. 조용히 새는 종류의 사고라 고정해 둔다.
 */
class RedactSecretsTest {

    @Test
    fun `인증키 값이 로그에 남지 않는다`() {
        val key = "abcDEF123%2Bxyz"
        val line = "--> GET https://apis.data.go.kr/1613000/BusSttnInfoInqireService" +
            "/getCrdntPrxmtSttnList?serviceKey=$key&gpsLati=37.5&gpsLong=127.0"

        val masked = redactSecrets(line)

        assertFalse("인증키가 그대로 남았다: $masked", masked.contains(key))
        assertEquals(
            "--> GET https://apis.data.go.kr/1613000/BusSttnInfoInqireService" +
                "/getCrdntPrxmtSttnList?serviceKey=<redacted>&gpsLati=37.5&gpsLong=127.0",
            masked,
        )
    }

    @Test
    fun `주소검색 승인키도 가린다`() {
        val masked = redactSecrets("--> GET https://business.juso.go.kr/addrlink/addrLinkApi.do?confmKey=SECRET&keyword=서울")

        assertFalse(masked.contains("SECRET"))
        // keyword 는 confmKey 와 접두사가 겹치지 않으므로 그대로 남아야 한다.
        assertEquals(
            "--> GET https://business.juso.go.kr/addrlink/addrLinkApi.do?confmKey=<redacted>&keyword=서울",
            masked,
        )
    }

    @Test
    fun `대소문자가 달라도 가린다`() {
        assertEquals("?KEY=<redacted>", redactSecrets("?KEY=abcdef"))
        assertEquals("&Key=<redacted>", redactSecrets("&Key=abcdef"))
    }

    @Test
    fun `키가 마지막 파라미터여도 가린다`() {
        val masked = redactSecrets("<-- 200 OK https://apis.data.go.kr/x?numOfRows=10&serviceKey=TAIL (120ms)")

        assertFalse(masked.contains("TAIL"))
        // 공백 뒤 응답 정보까지 먹어버리면 안 된다.
        assertEquals("<-- 200 OK https://apis.data.go.kr/x?numOfRows=10&serviceKey=<redacted> (120ms)", masked)
    }

    @Test
    fun `가릴 것이 없으면 원문 그대로다`() {
        val line = "--> GET https://apis.data.go.kr/x?numOfRows=10&pageNo=1"

        assertEquals(line, redactSecrets(line))
    }
}
