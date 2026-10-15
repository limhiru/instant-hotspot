package com.hiru.instanthotspot.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hiru.instanthotspot.MainActivity
import com.hiru.instanthotspot.R

/** The routine listens to this app and keyword; the keyword is not a credential. */
object RequestNotifications {
    const val ROUTINE_KEYWORD = "HIRU_HOTSPOT_ON"
    const val CHANNEL = "requests"
    private const val ID = 102

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "핫스팟 연결 요청", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun enabled(context: Context): Boolean {
        createChannel(context)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    }

    // Production callers must authenticate the BLE request before invoking this method.
    // The only other caller is the explicitly labelled, user-initiated routine test.
    fun post(context: Context, name: String, routine: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!enabled(context)) return false
        val launch = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (routine) ROUTINE_KEYWORD else "핫스팟 연결 요청"
        val content = if (routine) "$name · 삼성 루틴에 핫스팟 켜기를 요청했어요." else "$name: 폰에서 핫스팟을 켜 주세요."
        val note = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_lucide_radio)
            .setContentTitle(title).setContentText(content).setContentIntent(launch).setAutoCancel(true)
            .setOnlyAlertOnce(false).build()
        return try {
            // Re-post, rather than only updating the old request, so each accepted request
            // generates a fresh notification event for the routine (BLE rate limit: 10s).
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(ID)
            manager.notify(ID, note)
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}
