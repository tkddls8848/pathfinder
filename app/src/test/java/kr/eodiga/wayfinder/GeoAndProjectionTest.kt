package kr.eodiga.wayfinder

import kr.eodiga.wayfinder.data.repository.Epsg5179
import kr.eodiga.wayfinder.domain.model.Geo
import kr.eodiga.wayfinder.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `서울시청에서 광화문까지 거리는 약 1킬로미터다`() {
        val cityHall = LatLng(37.5663, 126.9779)
        val gwanghwamun = LatLng(37.5759, 126.9769)

        val d = Geo.distanceMeters(cityHall, gwanghwamun)

        assertEquals(1070.0, d, 60.0)
    }

    @Test
    fun `도보 시간은 교통약자 보행속도로 계산된다`() {
        // 0.75 m/s 기준 300m => 400초 => 약 7분
        assertEquals(7, Geo.walkMinutes(300.0))
        // 아주 짧아도 최소 1분으로 올린다. "0분" 은 안내가 되지 않는다.
        assertEquals(1, Geo.walkMinutes(5.0))
    }

    @Test
    fun `걸음 수는 보폭 65센티 기준이다`() {
        assertEquals(100, Geo.steps(65.0))
    }

}

class Epsg5179Test {

    /**
     * EPSG:5179 는 원점(38N, 127.5E)에서 가산값 그대로가 나와야 한다.
     * 이 성질이 깨지면 투영 상수가 잘못된 것이다.
     */
    @Test
    fun `투영 원점은 가산 좌표로 역변환된다`() {
        val result = Epsg5179.toWgs84(1_000_000.0, 2_000_000.0)

        assertEquals(38.0, result.lat, 1e-6)
        assertEquals(127.5, result.lng, 1e-6)
    }

    @Test
    fun `서울시청 좌표가 한국 범위 안으로 변환된다`() {
        // 서울시청 부근의 EPSG:5179 좌표
        val result = Epsg5179.toWgs84(953_700.0, 1_952_500.0)

        assertTrue("한국 범위를 벗어났다: $result", result.isValid())
        assertEquals(37.56, result.lat, 0.1)
        assertEquals(126.98, result.lng, 0.1)
    }
}
