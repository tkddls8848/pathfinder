package kr.eodiga.wayfinder.data.repository

import kr.eodiga.wayfinder.domain.model.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/** 기상청 격자 좌표. */
data class GridXY(val nx: Int, val ny: Int)

/**
 * WGS84 → 기상청 동네예보 격자(5km) 변환.
 *
 * 기상청 단기예보 API 는 위경도가 아니라 자체 격자 번호(nx, ny)를 받는다.
 * 람베르트 정각원추도법(LCC) 파라미터는 기상청이 공개한 변환 예제와 동일하다.
 */
object KmaGrid {

    private const val RE = 6371.00877   // 지구 반경(km)
    private const val GRID = 5.0        // 격자 간격(km)
    private const val SLAT1 = 30.0      // 표준 위도 1
    private const val SLAT2 = 60.0      // 표준 위도 2
    private const val OLON = 126.0      // 기준점 경도
    private const val OLAT = 38.0       // 기준점 위도
    private const val XO = 43.0         // 기준점 X 격자
    private const val YO = 136.0        // 기준점 Y 격자

    private const val DEGRAD = PI / 180.0

    fun toGrid(at: LatLng): GridXY {
        val re = RE / GRID
        val slat1 = SLAT1 * DEGRAD
        val slat2 = SLAT2 * DEGRAD
        val olon = OLON * DEGRAD
        val olat = OLAT * DEGRAD

        var sn = tan(PI * 0.25 + slat2 * 0.5) / tan(PI * 0.25 + slat1 * 0.5)
        sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
        var sf = tan(PI * 0.25 + slat1 * 0.5)
        sf = sf.pow(sn) * cos(slat1) / sn
        var ro = tan(PI * 0.25 + olat * 0.5)
        ro = re * sf / ro.pow(sn)

        var ra = tan(PI * 0.25 + at.lat * DEGRAD * 0.5)
        ra = re * sf / ra.pow(sn)
        var theta = at.lng * DEGRAD - olon
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn

        return GridXY(
            nx = floor(ra * sin(theta) + XO + 0.5).toInt(),
            ny = floor(ro - ra * cos(theta) + YO + 0.5).toInt(),
        )
    }
}
