package kr.eodiga.wayfinder.data.repository

import kr.eodiga.wayfinder.domain.model.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * EPSG:5179 (Korea 2000 / Unified CS, 흔히 "GRS80 UTM-K") → WGS84 역투영.
 *
 * 도로명주소 좌표제공 API(addrCoordApi.do)의 entX/entY 가 이 좌표계로 온다.
 * 지도 SDK 없이 좌표를 쓰려면 앱에서 직접 변환해야 한다.
 *
 * 투영: 횡단 메르카토르(Transverse Mercator)
 *   원점 위도 38°N, 원점 경도 127.5°E, 축척 0.9996,
 *   가산 동거리 1,000,000m, 가산 북거리 2,000,000m, 타원체 GRS80.
 *
 * Korea 2000 은 ITRF 기반이라 WGS84 와의 차이가 센티미터 수준이므로
 * 도보 안내 목적에서는 datum 변환을 생략해도 무방하다.
 *
 * 공식은 Snyder, *Map Projections — A Working Manual* (USGS PP-1395) 의
 * 역 TM 급수식을 따른다.
 */
object Epsg5179 {

    private const val A = 6_378_137.0                    // GRS80 장반경
    private const val INV_F = 298.257222101              // GRS80 편평률의 역수
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 1_000_000.0
    private const val FALSE_NORTHING = 2_000_000.0
    private val LAT_ORIGIN = 38.0.toRadians()
    private val LON_ORIGIN = 127.5.toRadians()

    private const val F = 1.0 / INV_F
    private const val E2 = 2 * F - F * F                 // 제1이심률의 제곱
    private val EP2 = E2 / (1 - E2)                      // 제2이심률의 제곱

    fun toWgs84(x: Double, y: Double): LatLng {
        val m = meridionalArc(LAT_ORIGIN) + (y - FALSE_NORTHING) / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2.pow(2) / 64 - 5 * E2.pow(3) / 256))

        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)
        val sinPhi1 = sin(phi1)

        val c1 = EP2 * cosPhi1 * cosPhi1
        val t1 = tanPhi1 * tanPhi1
        val n1 = A / sqrt(1 - E2 * sinPhi1 * sinPhi1)
        val r1 = A * (1 - E2) / (1 - E2 * sinPhi1 * sinPhi1).pow(1.5)
        val d = (x - FALSE_EASTING) / (n1 * K0)

        val lat = phi1 - (n1 * tanPhi1 / r1) * (
            d.pow(2) / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720
            )

        val lon = LON_ORIGIN + (
            d -
                (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cosPhi1

        return LatLng(lat.toDegrees(), lon.toDegrees())
    }

    /** 적도에서 위도 phi 까지의 자오선 호 길이. */
    private fun meridionalArc(phi: Double): Double = A * (
        (1 - E2 / 4 - 3 * E2.pow(2) / 64 - 5 * E2.pow(3) / 256) * phi -
            (3 * E2 / 8 + 3 * E2.pow(2) / 32 + 45 * E2.pow(3) / 1024) * sin(2 * phi) +
            (15 * E2.pow(2) / 256 + 45 * E2.pow(3) / 1024) * sin(4 * phi) -
            (35 * E2.pow(3) / 3072) * sin(6 * phi)
        )

    private fun Double.toRadians() = this * PI / 180.0
    private fun Double.toDegrees() = this * 180.0 / PI
}
