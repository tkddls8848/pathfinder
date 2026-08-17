package kr.eodiga.wayfinder.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.eodiga.wayfinder.domain.model.LatLng
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
        val address = addressAt(at) ?: return null

        // adminArea = 시/도 (예: 서울특별시), subAdminArea 또는 locality = 시/군/구
        val sido = address.adminArea ?: return null
        val sigungu = address.subAdminArea ?: address.locality
        return Region(sido = sido, sigungu = sigungu)
    }

    /**
     * 긴급 연락 화면에 표시하고 문자에 넣을 사람이 읽을 수 있는 주소.
     * 역지오코딩이 실패해도 호출자는 좌표만으로 연락을 계속할 수 있다.
     */
    suspend fun resolveAddress(at: LatLng): String? {
        val address = addressAt(at) ?: return null
        return runCatching { address.getAddressLine(0) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                address.adminArea,
                address.subAdminArea ?: address.locality,
                address.thoroughfare,
                address.featureName,
            ).distinct().joinToString(" ").takeIf { it.isNotBlank() }
    }

    private suspend fun addressAt(at: LatLng): Address? {
        val coder = geocoder ?: return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(GEOCODER_TIMEOUT_MS) { firstAddress(coder, at) }
        }
    }

    private suspend fun firstAddress(coder: Geocoder, at: LatLng): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    coder.getFromLocation(
                        at.lat,
                        at.lng,
                        1,
                        FirstAddressListener { address ->
                            if (cont.isActive) cont.resume(address)
                        },
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { coder.getFromLocation(at.lat, at.lng, 1)?.firstOrNull() }.getOrNull()
        }

    private companion object {
        const val GEOCODER_TIMEOUT_MS = 5_000L
    }
}

/**
 * Android 13+ Geocoder 는 성공과 실패를 별도 콜백으로 보낸다. 둘 중 하나만 완료한다.
 *
 * [Geocoder.GeocodeListener] 자체가 API 33 에 생긴 타입이라, minSdk 26 기기에서는
 * 이 클래스가 아예 로드되지 않아야 한다. 호출부가 버전 분기 안에 있어 실제로
 * 그렇지만, 의도를 코드로도 남겨 둔다.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class FirstAddressListener(
    private val complete: (Address?) -> Unit,
) : Geocoder.GeocodeListener {
    private val completed = AtomicBoolean(false)

    override fun onGeocode(addresses: MutableList<Address>) {
        completeOnce(addresses.firstOrNull())
    }

    override fun onError(errorMessage: String?) {
        completeOnce(null)
    }

    private fun completeOnce(address: Address?) {
        if (completed.compareAndSet(false, true)) complete(address)
    }
}
