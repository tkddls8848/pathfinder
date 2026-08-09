package kr.eodiga.wayfinder

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kr.eodiga.wayfinder.data.remote.dto.LenientStringAdapter
import kr.eodiga.wayfinder.data.remote.dto.NearbyStopDto
import kr.eodiga.wayfinder.data.remote.dto.PublicDataException
import kr.eodiga.wayfinder.data.remote.dto.TagoEnvelope
import kr.eodiga.wayfinder.data.remote.dto.TagoItemsAdapterFactory
import kr.eodiga.wayfinder.data.remote.dto.itemsOrThrow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TAGO 응답의 `items` 필드는 결과 건수에 따라 모양이 바뀐다.
 * 이 세 경우를 모두 통과하지 못하면 실사용에서 앱이 죽는다.
 */
class TagoItemsAdapterTest {

    private val moshi = Moshi.Builder()
        .add(TagoItemsAdapterFactory())
        .add(LenientStringAdapter)
        .build()

    private val adapter = moshi.adapter<TagoEnvelope<NearbyStopDto>>(
        Types.newParameterizedType(TagoEnvelope::class.java, NearbyStopDto::class.java),
    )

    @Test
    fun `결과가 여러 건이면 배열로 파싱된다`() {
        val json = """
        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
         "body":{"items":{"item":[
            {"citycode":25,"nodeid":"DJB8001793","nodenm":"중앙시장","nodeno":42381,"gpslati":36.32,"gpslong":127.42},
            {"citycode":25,"nodeid":"DJB8001794","nodenm":"시청앞","nodeno":"42382","gpslati":36.33,"gpslong":127.43}
         ]},"numOfRows":10,"pageNo":1,"totalCount":2}}}
        """.trimIndent()

        val items = adapter.fromJson(json)!!.itemsOrThrow()

        assertEquals(2, items.size)
        assertEquals("중앙시장", items[0].nodeName)
    }

    @Test
    fun `결과가 한 건이면 item 이 배열이 아니라 객체로 온다`() {
        val json = """
        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
         "body":{"items":{"item":
            {"citycode":25,"nodeid":"DJB8001793","nodenm":"중앙시장","nodeno":42381,"gpslati":36.32,"gpslong":127.42}
         },"numOfRows":10,"pageNo":1,"totalCount":1}}}
        """.trimIndent()

        val items = adapter.fromJson(json)!!.itemsOrThrow()

        assertEquals(1, items.size)
        assertEquals("DJB8001793", items[0].nodeId)
    }

    @Test
    fun `결과가 없으면 items 가 빈 문자열로 온다`() {
        val json = """
        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
         "body":{"items":"","numOfRows":10,"pageNo":1,"totalCount":0}}}
        """.trimIndent()

        val items = adapter.fromJson(json)!!.itemsOrThrow()

        assertTrue(items.isEmpty())
    }

    @Test
    fun `업무 오류는 예외로 올라오고 인증 오류로 분류된다`() {
        val json = """
        {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"},
         "body":{"items":""}}}
        """.trimIndent()

        try {
            adapter.fromJson(json)!!.itemsOrThrow()
            fail("PublicDataException 이 발생해야 한다")
        } catch (e: PublicDataException) {
            assertTrue(e.isAuthError)
            assertEquals("30", e.code)
        }
    }
}
