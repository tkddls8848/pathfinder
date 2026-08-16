package kr.eodiga.wayfinder.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject

/**
 * 공공데이터포털 요청 공통 처리.
 *
 *  1. `serviceKey` 를 정확히 한 번만 인코딩해서 붙인다.
 *  2. `_type=json` 을 강제한다. (기본값이 XML 이라 빼먹으면 파싱이 통째로 실패한다.)
 *
 * 이미 쿼리에 들어 있으면 덮어쓰지 않는다.
 */
class PublicDataInterceptor @Inject constructor(
    private val keys: ServiceKeyProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url

        val builder = url.newBuilder()
        if (url.queryParameter("serviceKey") == null) {
            // addQueryParameter 는 값을 한 번만 퍼센트 인코딩한다.
            builder.addQueryParameter("serviceKey", keys.serviceKey)
        }
        if (url.queryParameter("_type") == null) {
            builder.addQueryParameter("_type", "json")
        }

        return chain.proceed(original.newBuilder().url(builder.build()).build())
    }
}

/** 인증키가 아예 없을 때 네트워크를 태우지 않고 즉시 실패시킨다. */
class MissingKeyGuardInterceptor @Inject constructor(
    private val keys: ServiceKeyProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!keys.hasServiceKey) {
            throw MissingServiceKeyException()
        }
        return chain.proceed(chain.request())
    }
}

class MissingServiceKeyException : IOException(
    "공공데이터포털 인증키가 설정되지 않았습니다. local.properties 의 PUBLIC_DATA_SERVICE_KEY 를 확인하세요.",
)
