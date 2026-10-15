package com.hiru.instanthotspot.ble

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hiru.instanthotspot.HotspotApplication
import com.hiru.instanthotspot.MainActivity
import com.hiru.instanthotspot.R

class ProviderService : Service() {
    private val engine get() = (application as HotspotApplication).ble
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("waiting", "기기 요청 대기", NotificationManager.IMPORTANCE_LOW))
        RequestNotifications.createChannel(this)
        engine.onIncomingRequest = { name ->
            val routine = (application as HotspotApplication).store.routineEnabled
            if (!RequestNotifications.post(this, name, routine) && routine) {
                engine.message("루틴 연결에 필요한 요청 알림이 차단돼 있어요. 설정에서 알림을 허용하거나 핫스팟을 직접 켜 주세요.")
            }
        }
        engine.onProviderFailure = { stopSelf() }
    }
    private fun notification(channel: String, title: String, content: String): NotificationCompat.Builder {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_lucide_radio)
            .setContentTitle(title).setContentText(content).setContentIntent(launch).setAutoCancel(channel == "requests")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 1, Intent(this, ProviderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = notification("waiting", "Hiru · 요청 대기 중", "등록된 기기의 연결 요청을 기다리고 있어요.")
            .setOngoing(true).addAction(0, "대기 중지", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(101, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(101, note)
            engine.startProvider()
            if (intent?.getBooleanExtra("pairing", false) == true) engine.allowPairing()
        } catch (_: Exception) {
            engine.message("대기를 시작하지 못했어요. 앱을 열고 권한을 확인해 주세요."); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        engine.onIncomingRequest = null; engine.onProviderFailure = null
        engine.stopProvider()
        RequestNotifications.cancel(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val ACTION_STOP = "com.hiru.instanthotspot.STOP" }
}
