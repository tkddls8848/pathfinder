package kr.eodiga.wayfinder.guardian

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kr.eodiga.wayfinder.data.repository.PlaceRepository
import kr.eodiga.wayfinder.domain.model.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Date
import java.util.Locale

/**
 * "길을 잃었어요" 처리.
 *
 * 목업에서는 alert() 한 줄이었지만, 여기가 이 앱의 존재 이유에 가장 가까운 기능이다.
 * 어르신이 당황한 상태에서 누르는 버튼이므로 절대 실패하면 안 되고,
 * 실패하더라도 최소한 전화는 걸려야 한다.
 *
 * 동작 순서 (앞 단계가 실패해도 다음 단계는 진행):
 *  1. 현재 위치를 지도 링크가 포함된 문자로 보호자에게 전송
 *  2. 보호자에게 전화 연결
 *
 * 문자 발송은 SEND_SMS 권한이 필요하다. 권한이 없거나 실패하면
 * 문자 앱을 내용이 채워진 상태로 여는 방식으로 대체한다.
 */
@Singleton
class GuardianNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val places: PlaceRepository,
) {

    enum class SmsStatus {
        /** SmsManager 가 전송 요청을 정상 접수했다. */
        SENT,

        /** 권한이 없어 내용이 채워진 문자 앱만 열었다. 사용자가 전송을 눌러야 한다. */
        COMPOSER_OPENED,

        FAILED,
        NOT_ATTEMPTED,
    }

    data class Result(
        val smsStatus: SmsStatus,
        val callStarted: Boolean,
        val guardianName: String?,
        val locationIncluded: Boolean,
        val guardianLookupFailed: Boolean = false,
    ) {
        val smsSent: Boolean get() = smsStatus == SmsStatus.SENT
        val anyActionStarted: Boolean
            get() = smsStatus == SmsStatus.SENT ||
                smsStatus == SmsStatus.COMPOSER_OPENED ||
                callStarted
    }

    suspend fun notifyLost(location: LatLng?, addressText: String?): Result {
        val guardian = try {
            places.primaryGuardian()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return Result(
                smsStatus = SmsStatus.NOT_ATTEMPTED,
                callStarted = false,
                guardianName = null,
                locationIncluded = location != null,
                guardianLookupFailed = true,
            )
        } ?: return Result(
            smsStatus = SmsStatus.NOT_ATTEMPTED,
            callStarted = false,
            guardianName = null,
            locationIncluded = location != null,
        )

        val message = buildLostMessage(location, addressText)
        val smsStatus = trySendSms(guardian.phone, message)
        val callStarted = tryCall(guardian.phone)

        return Result(
            smsStatus = smsStatus,
            callStarted = callStarted,
            guardianName = guardian.name,
            locationIncluded = location != null,
        )
    }

    private fun trySendSms(phone: String, message: String): SmsStatus {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching {
                val manager = context.getSystemService(SmsManager::class.java)
                // 좌표 링크가 붙으면 70자를 쉽게 넘어가므로 분할 전송한다.
                val parts = manager.divideMessage(message)
                manager.sendMultipartTextMessage(phone, null, parts, null, null)
            }.onSuccess { return SmsStatus.SENT }
        }
        // 권한이 없으면 문자 앱을 대신 연다. 어르신이 전송 버튼만 누르면 된다.
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            SmsStatus.COMPOSER_OPENED
        }.getOrDefault(SmsStatus.FAILED)
    }

    private fun tryCall(phone: String): Boolean {
        val canCallDirectly = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            context.startActivity(
                Intent(action, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}

/** 주소와 좌표를 모두 담아, 주소 변환이 실패해도 지도 링크는 남긴다. */
internal fun buildLostMessage(
    location: LatLng?,
    addressText: String?,
    sentAt: Date = Date(),
): String = buildString {
    append("[어디가요] 도움이 필요합니다.\n")
    if (!addressText.isNullOrBlank()) append("현재 위치: $addressText\n")
    if (location != null) {
        // 어떤 지도 앱에서도 열리는 범용 좌표 링크.
        append("지도: https://maps.google.com/?q=${location.lat},${location.lng}\n")
    }
    append("보낸 시각: ${java.text.SimpleDateFormat("M월 d일 HH시 mm분", Locale.KOREA).format(sentAt)}")
}
