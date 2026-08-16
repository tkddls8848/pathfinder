package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.location.FirstAddressListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionResolverTest {

    @Test
    fun `Android 13 Geocoder 오류 콜백은 null 결과로 완료한다`() {
        val results = mutableListOf<android.location.Address?>()
        val listener = FirstAddressListener(results::add)

        listener.onError("network unavailable")
        listener.onError("duplicate callback")

        assertEquals(1, results.size)
        assertNull(results.single())
    }
}
