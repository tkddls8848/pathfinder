package kr.eodiga.wayfinder.data.remote

import kr.eodiga.wayfinder.BuildConfig
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 공공데이터포털 인증키를 다루는 유일한 지점.
 *
 * data.go.kr 은 같은 키를 Encoding / Decoding 두 형태로 보여준다.
 * 대부분의 "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" 는 키가 틀려서가 아니라
 * 이미 퍼센트 인코딩된 Encoding 키를 HTTP 클라이언트가 한 번 더 인코딩해
 * `%2B` 가 `%252B` 로 나가기 때문에 발생한다.
 *
 * 그래서 여기서는 어떤 형태를 붙여넣든 항상 raw(디코딩) 형태로 정규화한 뒤,
 * OkHttp 의 `addQueryParameter` 가 정확히 한 번만 인코딩하게 한다.
 */
@Singleton
class ServiceKeyProvider @Inject constructor() {

    private val encodedMarkers = Regex("%[0-9A-Fa-f]{2}")

    /** 항상 디코딩된(raw) 인증키. 비어 있으면 키 미설정 상태. */
    val serviceKey: String = normalize(BuildConfig.PUBLIC_DATA_SERVICE_KEY)

    val jusoKey: String = BuildConfig.JUSO_CONFIRM_KEY.trim()

    val seoulKey: String = BuildConfig.SEOUL_OPEN_API_KEY.trim()

    val hasServiceKey: Boolean get() = serviceKey.isNotBlank()

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // 퍼센트 인코딩 흔적이 있으면 디코딩해서 raw 형태로 되돌린다.
        return if (encodedMarkers.containsMatchIn(trimmed)) {
            runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        } else {
            trimmed
        }
    }
}
