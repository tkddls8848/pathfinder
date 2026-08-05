package kr.eodiga.wayfinder.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.rawType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * TAGO 계열(1613000) 오픈API 공통 응답 봉투.
 *
 * ```
 * { "response": { "header": { "resultCode": "00", ... },
 *                 "body":   { "items": { "item": [...] }, "totalCount": 3, ... } } }
 * ```
 */
@JsonClass(generateAdapter = true)
data class TagoEnvelope<T>(
    @Json(name = "response") val response: TagoResponse<T>? = null,
)

@JsonClass(generateAdapter = true)
data class TagoResponse<T>(
    @Json(name = "header") val header: TagoHeader? = null,
    @Json(name = "body") val body: TagoBody<T>? = null,
)

@JsonClass(generateAdapter = true)
data class TagoHeader(
    @Json(name = "resultCode") val resultCode: String? = null,
    @Json(name = "resultMsg") val resultMsg: String? = null,
) {
    val isSuccess: Boolean get() = resultCode == "00"
}

@JsonClass(generateAdapter = true)
data class TagoBody<T>(
    @Json(name = "items") val items: TagoItems<T>? = null,
    @Json(name = "numOfRows") val numOfRows: Int? = null,
    @Json(name = "pageNo") val pageNo: Int? = null,
    @Json(name = "totalCount") val totalCount: Int? = null,
)

/**
 * `items` 필드는 세 가지 모양으로 온다. 서버가 XML→JSON 변환을 하며 생기는 문제다.
 *
 *  - 결과 0건  : `"items": ""`            (객체가 아니라 빈 문자열!)
 *  - 결과 1건  : `"items": {"item": {…}}` (배열이 아니라 단일 객체!)
 *  - 결과 N건  : `"items": {"item": [ … ]}`
 *
 * 세 경우를 모두 리스트 하나로 정규화한다. 이 처리가 없으면 실사용에서
 * "정류소가 하나뿐인 지역" 이나 "막차 이후" 같은 상황에 앱이 곧바로 죽는다.
 */
data class TagoItems<T>(val item: List<T> = emptyList())

class TagoItemsAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (annotations.isNotEmpty()) return null
        if (type.rawType != TagoItems::class.java) return null
        val elementType = (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: return null
        val elementAdapter = moshi.adapter<Any>(elementType)
        val listAdapter = moshi.adapter<List<Any>>(Types.newParameterizedType(List::class.java, elementType))
        return TagoItemsAdapter(elementAdapter, listAdapter).nullSafe()
    }
}

private class TagoItemsAdapter(
    private val elementAdapter: JsonAdapter<Any>,
    private val listAdapter: JsonAdapter<List<Any>>,
) : JsonAdapter<TagoItems<Any>>() {

    override fun fromJson(reader: JsonReader): TagoItems<Any> = when (reader.peek()) {
        // 결과 0건: "items": "" 또는 "items": " "
        JsonReader.Token.STRING -> {
            reader.nextString()
            TagoItems(emptyList())
        }

        JsonReader.Token.BEGIN_OBJECT -> {
            var result: List<Any> = emptyList()
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "item") {
                    reader.skipValue()
                    continue
                }
                result = when (reader.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> listAdapter.fromJson(reader).orEmpty()
                    JsonReader.Token.BEGIN_OBJECT -> listOfNotNull(elementAdapter.fromJson(reader))
                    else -> {
                        reader.skipValue()
                        emptyList()
                    }
                }
            }
            reader.endObject()
            TagoItems(result)
        }

        // 방어적 처리: 일부 서비스는 items 를 바로 배열로 준다.
        JsonReader.Token.BEGIN_ARRAY -> TagoItems(listAdapter.fromJson(reader).orEmpty())

        else -> {
            reader.skipValue()
            TagoItems(emptyList())
        }
    }

    override fun toJson(writer: JsonWriter, value: TagoItems<Any>?) {
        writer.beginObject().name("item")
        listAdapter.toJson(writer, value?.item.orEmpty())
        writer.endObject()
    }
}

/** 봉투를 벗겨 리스트만 꺼낸다. 헤더가 실패면 예외를 던진다. */
fun <T> TagoEnvelope<T>.itemsOrThrow(): List<T> {
    val header = response?.header
    if (header != null && !header.isSuccess) {
        throw PublicDataException(header.resultCode.orEmpty(), header.resultMsg.orEmpty())
    }
    return response?.body?.items?.item.orEmpty()
}

/** 공공데이터포털이 정상 HTTP 200 과 함께 돌려주는 업무 오류. */
class PublicDataException(
    val code: String,
    val serverMessage: String,
) : RuntimeException("공공데이터 오류 [$code] $serverMessage") {

    /** 인증키 문제인지. UI 에서 "관리자 설정" 안내로 분기한다. */
    val isAuthError: Boolean
        get() = code in setOf("30", "31", "32", "20", "22") ||
            serverMessage.contains("SERVICE_KEY", ignoreCase = true) ||
            serverMessage.contains("SERVICE ACCESS DENIED", ignoreCase = true)

    /** 일일 트래픽 초과. 재시도해도 소용없다. */
    val isQuotaExceeded: Boolean
        get() = code == "22" || serverMessage.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS", ignoreCase = true)
}
