package com.antgskds.calendarassistant.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.antgskds.calendarassistant.MyEvent
import com.antgskds.calendarassistant.receiver.AlarmReceiver
import java.time.LocalDateTime
import java.time.ZoneId

object NotificationScheduler {

    // 提醒时间选项 (分钟数 -> 显示文本)
    val REMINDER_OPTIONS = listOf(
        0 to "日程开始时",
        5 to "5分钟前",
        10 to "10分钟前",
        15 to "15分钟前",
        30 to "30分钟前",
        60 to "1小时前",
        120 to "2小时前",
        360 to "6小时前",
        1440 to "1天前",
        2880 to "2天前"
    )

    fun scheduleReminders(context: Context, event: MyEvent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 只有当有 SCHEDULE_EXACT_ALARM 权限时才设置精确闹钟，否则可能崩溃（Android 12+）
        // 这里简化处理，假设已有权限或捕获异常

        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val eventDateTime = try {
            LocalDateTime.parse("${event.startDate} ${event.startTime}", formatter)
        } catch (e: Exception) { return }

        val eventMillis = eventDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        event.reminders.forEach { minutesBefore ->
            val triggerTime = eventMillis - (minutesBefore * 60 * 1000)

            // 如果触发时间已经过去了，就不设置了
            if (triggerTime > System.currentTimeMillis()) {
                val label = REMINDER_OPTIONS.find { it.first == minutesBefore }?.second ?: ""
                scheduleSingleAlarm(context, event, minutesBefore, triggerTime, label, alarmManager)
            }
        }
    }

    private fun scheduleSingleAlarm(
        context: Context,
        event: MyEvent,
        minutesBefore: Int,
        triggerTime: Long,
        label: String,
        alarmManager: AlarmManager
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EVENT_ID", event.id)
            putExtra("EVENT_TITLE", event.title)
            putExtra("REMINDER_LABEL", label)
        }

        // RequestCode 需要唯一：EventID Hash + 分钟数
        val requestCode = (event.id.hashCode() + minutesBefore).toInt()

        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            // 【Flyme 专属适配】
            // 使用 setAlarmClock 而不是 setExact。
            // 优势1：在 Flyme 等国产 ROM 上优先级最高，极难被杀后台。
            // 优势2：通常不需要 REQUEST_SCHEDULE_EXACT_ALARM 权限（因为在状态栏会有小闹钟图标）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
                    pendingIntent
                )
            } else {
                // 低版本兼容
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d("Scheduler", "Scheduled alarm for ${event.title} at $label")
        } catch (e: SecurityException) {
            Log.e("Scheduler", "Permission missing for exact alarm", e)
        }
    }

    fun cancelReminders(context: Context, event: MyEvent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        event.reminders.forEach { minutesBefore ->
            val intent = Intent(context, AlarmReceiver::class.java)
            val requestCode = (event.id.hashCode() + minutesBefore).toInt()
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }
}