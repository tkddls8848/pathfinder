package kr.eodiga.wayfinder.domain.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 좌표. 공공데이터포털 TAGO 는 gpsLati/gpsLong 을 WGS84 로 제공한다. */
data class LatLng(val lat: Double, val lng: Double) {
    fun isValid(): Boolean = lat in 33.0..39.5 && lng in 124.0..132.0
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 두 좌표 사이 대권거리(m). */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * 어르신 보행 속도 기준 소요 시간(분).
     *
     * 국토교통부 보행자 신호 산정 기준은 1.0 m/s 이고, 교통약자 기준은 0.8 m/s 다.
     * 안내를 짧게 잡아 조급하게 만들지 않도록 보수적으로 0.75 m/s 를 쓴다.
     */
    fun walkMinutes(meters: Double): Int =
        ((meters / 0.75) / 60.0).roundToInt().coerceAtLeast(1)

    /** 보폭 65cm 기준 걸음 수. 화면에 "20 걸음" 처럼 표시하기 위한 값. */
    fun steps(meters: Double): Int = (meters / 0.65).roundToInt().coerceAtLeast(1)
}
