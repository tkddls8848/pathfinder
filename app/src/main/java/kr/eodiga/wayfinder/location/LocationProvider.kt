package kr.eodiga.wayfinder.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.eodiga.wayfinder.domain.model.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 마지막으로 알려진 위치. 없으면 현재 위치를 한 번 요청한다. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LatLng? {
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    cont.resume(loc?.let { LatLng(it.latitude, it.longitude) })
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    /**
     * 도보 안내용 위치 스트림.
     *
     * 어르신의 보행 속도가 느려 1초 간격은 배터리만 먹고 화면은 거의 안 바뀐다.
     * 3초 / 최소 5m 이동을 기준으로 잡는다.
     */
    @SuppressLint("MissingPermission")
    fun locationStream(intervalMs: Long = 3_000): Flow<LatLng> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(5f)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(LatLng(it.latitude, it.longitude)) }
            }
        }
        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
