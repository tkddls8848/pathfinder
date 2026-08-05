package kr.eodiga.wayfinder.data.remote.dto

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson

/**
 * 같은 필드가 어떤 지자체에서는 문자열("42381"), 다른 지자체에서는 숫자(42381)로 온다.
 * (정류소번호 nodeno, 차량번호 vehicleno 등에서 실제로 관측된다.)
 * 이 한정자를 붙이면 둘 다 String 으로 받는다.
 */
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class LenientString

object LenientStringAdapter {
    @FromJson
    @LenientString
    fun fromJson(reader: JsonReader): String? = when (reader.peek()) {
        JsonReader.Token.NULL -> reader.nextNull()
        JsonReader.Token.NUMBER -> {
            // 42381.0 처럼 되지 않도록 정수면 정수로 읽는다.
            val d = reader.nextDouble()
            if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        }
        JsonReader.Token.BOOLEAN -> reader.nextBoolean().toString()
        else -> reader.nextString()
    }

    @ToJson
    fun toJson(@LenientString value: String?): String? = value
}
