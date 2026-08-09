package kr.eodiga.wayfinder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kr.eodiga.wayfinder.MainActivity
import kr.eodiga.wayfinder.R
import kr.eodiga.wayfinder.domain.model.GuidanceStage
import kr.eodiga.wayfinder.domain.model.JourneyLeg
import javax.inject.Inject

/**
 * 안내가 진행되는 동안 살아 있는 포그라운드 서비스.
 *
 * 이 서비스가 없으면 어르신이 화면을 끄거나 다른 앱을 여는 순간 안내가 죽고,
 * 하차 알림이 울리지 않는다. 목업과 실제 제품을 가르는 가장 큰 차이다.
 *
 * 알림 자체도 안내 화면이다. 잠금화면에서 글씨만 보고도
 * "몇 정거장 남았는지" 를 알 수 있어야 한다.
 */
@AndroidEntryPoint
class JourneyGuidanceService : LifecycleService() {

    @Inject lateinit var controller: JourneyController
    @Inject lateinit var voice: VoiceGuide

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        voice.initialize()
        startForegroundWith(buildNotification("길안내 준비 중", "잠시만 기다려주세요"))

        lifecycleScope.launch {
            // 안내가 한 번이라도 시작된 뒤에만 종료 조건을 본다.
            // 초기 상태도 isActive=false / Arrived 라서, 이 가드가 없으면
            // 서비스가 뜨자마자 스스로 죽는다.
            var sawActiveJourney = false

            controller.state.collectLatest { state ->
                val (title, body) = notificationTextFor(state)
                notificationManager.notify(NOTIFICATION_ID, buildNotification(title, body))

                if (state.isActive) sawActiveJourney = true
                if (sawActiveJourney && !state.isActive && state.stage is GuidanceStage.Arrived) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // 안내 도중 프로세스가 죽으면 시스템이 되살리게 한다.
        return START_STICKY
    }

    override fun onDestroy() {
        voice.stop()
        super.onDestroy()
    }

    private fun startForegroundWith(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notificationTextFor(state: JourneyState): Pair<String, String> {
        val journey = state.journey ?: return "길안내" to "준비 중입니다"
        val destination = journey.destination.name
        val leg = journey.legs.getOrNull(state.stage.legIndex)

        return when (val stage = state.stage) {
            is GuidanceStage.Walking -> {
                val name = (leg as? JourneyLeg.Walk)?.destinationName ?: destination
                "$name 까지 걸어가세요" to "${stage.remainingMeters}m 남았습니다"
            }
            is GuidanceStage.ArrivedAtStop ->
                "정류장에 도착했습니다" to "화면에서 확인해주세요"
            is GuidanceStage.WaitingForBus -> {
                val bus = (leg as? JourneyLeg.Bus)?.route?.routeNo.orEmpty()
                val minutes = stage.arrival?.arrivalMinutes
                "${bus}번 버스 기다리는 중" to
                    (minutes?.let { "${it}분 뒤 도착" } ?: "도착 정보를 불러오는 중")
            }
            is GuidanceStage.Boarding -> {
                val bus = (leg as? JourneyLeg.Bus)?.route?.routeNo.orEmpty()
                "${bus}번 버스가 곧 옵니다" to "손을 드세요"
            }
            is GuidanceStage.Riding ->
                "${stage.stopsRemaining}정거장 남았습니다" to
                    (stage.nextStopName?.let { "다음 정류장: $it" } ?: destination)
            is GuidanceStage.PrepareToAlight ->
                "곧 내립니다" to "일어날 준비하세요"
            is GuidanceStage.RingBell ->
                "벨을 누르세요" to "다음 정류장에서 내립니다"
            GuidanceStage.Arrived ->
                "도착했습니다" to "$destination 앞입니다"
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JourneyGuidanceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // 잠금화면에서도 전문을 보여준다. 어르신이 알림을 펼치지 못하는 경우가 많다.
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$body"))
            .setContentIntent(openApp)
            .addAction(0, "안내 그만하기", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true) // 소리·진동은 Haptics/VoiceGuide 가 의미 있는 순간에만 낸다.
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "길안내",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "버스 도착과 하차 시점을 알려드립니다"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "guidance"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "kr.eodiga.wayfinder.STOP_GUIDANCE"

        fun start(context: Context) {
            val intent = Intent(context, JourneyGuidanceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, JourneyGuidanceService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
