package com.antgskds.calendarassistant.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra("EVENT_ID") ?: return
        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "日程提醒"
        val reminderLabel = intent.getStringExtra("REMINDER_LABEL") ?: ""

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 点击通知打开 App
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventId.hashCode(), tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(eventTitle)
            .setContentText(if(reminderLabel.isNotEmpty()) "$reminderLabel: $eventTitle" else "日程即将开始")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // 声音+震动
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // 使用唯一的 ID 显示通知，避免覆盖
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}