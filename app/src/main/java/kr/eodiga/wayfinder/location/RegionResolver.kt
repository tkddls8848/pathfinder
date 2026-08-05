package kr.eodiga.wayfinder.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kr.eodiga.wayfinder.domain.model.LatLng
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 행정구역. 병·의원 찾기 API 는 좌표가 아니라 시/도 + 시/군/구로 조회한다. */
data class Region(val sido: String, val sigungu: String?)

/**
 * 좌표 → 행정구역 변환.
 *
 * 공공데이터포털에는 좌표를 행정구역으로 바꿔주는 범용 API 가 없어서
 * 안드로이드 기본 [Geocoder] 를 쓴다. 네트워크가 필요하지만 별도 인증키가 없고,
 * 실패해도 검색이 병원 없이 주소만으로 계속 진행되도록 설계했다.
 */
@Singleton
class RegionResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.KOREA) else null

    suspend fun resolve(at: LatLng): Region? {
        val coder = geocoder ?: return null
        val address = withContext(Dispatchers.IO) { firstAddress(coder, at) } ?: return null

        // adminArea = 시/도 (예: 서울특별시), subAdminArea 또는 locality = 시/군/구
        val sido = address.adminArea ?: return null
        val sigungu = address.subAdminArea ?: address.locality
        return Region(sido = sido, sigungu = sigungu)
    }

    /** 길을 잃었을 때 어르신에게 읽어줄 "눈에 보이는 주소". */
    suspend fun describe(at: LatLng): String? {
        val coder = geocoder ?: return null
        val address = withContext(Dispatchers.IO) { firstAddress(coder, at) } ?: return null
        return listOfNotNull(
            address.subAdminArea ?: address.locality,
            address.subLocality,
            address.thoroughfare,
        ).distinct().joinToString(" ").ifBlank { address.getAddressLine(0) }
    }

    private suspend fun firstAddress(coder: Geocoder, at: LatLng): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                coder.getFromLocation(at.lat, at.lng, 1) { list ->
                    cont.resume(list.firstOrNull())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { coder.getFromLocation(at.lat, at.lng, 1)?.firstOrNull() }.getOrNull()
        }
}
