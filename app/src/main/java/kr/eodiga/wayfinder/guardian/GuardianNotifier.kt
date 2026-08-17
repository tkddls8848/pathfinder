package kr.eodiga.wayfinder.guardian

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
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

    /** 발송마다 다른 액션을 써야 이전 요청의 결과와 섞이지 않는다. */
    private val sendSequence = AtomicLong()

    private companion object {
        const val ACTION_SMS_SENT = "kr.eodiga.wayfinder.SMS_SENT"

        /**
         * 발송 결과를 이만큼만 기다린다. 어르신은 당황한 상태로 화면을 보고 있다.
         * 결과를 더 기다리느니 "보내는 중" 이라고 말하고 전화 쪽으로 넘긴다.
         */
        const val SEND_RESULT_TIMEOUT_MS = 8_000L
    }

    enum class SmsStatus {
        /** 시스템이 발송 완료를 확인해 주었다. */
        SENT,

        /**
         * 발송을 요청했고 거절당하지도 않았지만, 결과 확인이 제때 오지 않았다.
         * 갔을 수도 안 갔을 수도 있다. 실패로 단정하지 않는다.
         */
        REQUESTED,

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
                smsStatus == SmsStatus.REQUESTED ||
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
        // 문자를 먼저 띄워 보내고, 결과를 기다리기 전에 전화부터 건다.
        // 발송 결과 확인은 몇 초가 걸릴 수 있는데 그만큼 전화가 늦어지면 안 된다.
        val awaitSmsResult = beginSendSms(guardian.phone, message)
        val callStarted = tryCall(guardian.phone)
        val smsStatus = awaitSmsResult()

        return Result(
            smsStatus = smsStatus,
            callStarted = callStarted,
            guardianName = guardian.name,
            locationIncluded = location != null,
        )
    }

    /**
     * 문자를 보내고, 시스템이 결과를 알려주면 그때까지만 기다린다.
     *
     * `sendMultipartTextMessage` 는 void 라 예외만 없으면 성공처럼 보인다.
     * 실제로는 안테나가 없거나 요금제가 막혀 있으면 조용히 실패한다.
     * 그래서 발송 결과 PendingIntent 를 붙여 시스템의 응답을 받는다.
     *
     * 응답이 [SEND_RESULT_TIMEOUT_MS] 안에 오지 않으면 [SmsStatus.REQUESTED] 로
     * 둔다 — 실패로 단정하지 않는다. 실제로 갔는데 "못 보냈다" 고 말하면
     * 어르신이 다른 방법을 찾아 헤매게 된다. 모르면 모른다고 한다.
     */
    private fun beginSendSms(phone: String, message: String): suspend () -> SmsStatus {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            // 결과 수신 등록은 실패할 수 있다. 그때는 결과를 모른 채 보내기만 한다.
            // 발송 시도는 어떤 경우에도 **한 번만** 한다. 같은 문자가 두 번 가면
            // 보호자는 상황이 두 번 벌어진 것으로 읽는다.
            val awaiting = registerSendResult()
            val requested = runCatching {
                val manager = context.getSystemService(SmsManager::class.java)
                // 좌표 링크가 붙으면 70자를 쉽게 넘어가므로 분할 전송한다.
                val parts = manager.divideMessage(message)
                manager.sendMultipartTextMessage(
                    phone,
                    null,
                    parts,
                    awaiting?.let { pending -> ArrayList(List(parts.size) { pending.intent }) },
                    null,
                )
            }.isSuccess

            if (requested) {
                return awaiting?.let { { it.await() } } ?: { SmsStatus.REQUESTED }
            }
            awaiting?.cancel()
        }
        // 권한이 없으면 문자 앱을 대신 연다. 어르신이 전송 버튼만 누르면 된다.
        val status = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            SmsStatus.COMPOSER_OPENED
        }.getOrDefault(SmsStatus.FAILED)
        return { status }
    }

    /**
     * 발송 결과를 한 번 받아오는 일회용 수신기.
     *
     * 등록에 실패하면 null 을 돌려주고, 호출부는 결과를 모른 채 진행한다.
     * 여기서 예외가 나 "길을 잃었어요" 전체가 무너지는 일은 없어야 한다.
     */
    private fun registerSendResult(): AwaitingSendResult? = runCatching {
        val action = "$ACTION_SMS_SENT.${sendSequence.incrementAndGet()}"
        val result = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                result.complete(resultCode)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        AwaitingSendResult(
            intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            result = result,
            unregister = { runCatching { context.unregisterReceiver(receiver) } },
        )
    }.getOrNull()

    private inner class AwaitingSendResult(
        val intent: PendingIntent,
        private val result: CompletableDeferred<Int>,
        private val unregister: () -> Unit,
    ) {
        suspend fun await(): SmsStatus {
            val code = withTimeoutOrNull(SEND_RESULT_TIMEOUT_MS) { result.await() }
            unregister()
            return when (code) {
                null -> SmsStatus.REQUESTED // 아직 모른다. 실패로 단정하지 않는다.
                Activity.RESULT_OK -> SmsStatus.SENT
                else -> SmsStatus.FAILED
            }
        }

        fun cancel() {
            unregister()
            runCatching { intent.cancel() }
        }
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
