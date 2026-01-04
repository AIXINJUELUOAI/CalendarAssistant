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
import java.time.format.DateTimeFormatter

object NotificationScheduler {

    val REMINDER_OPTIONS = listOf(
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

    // 常量定义：区分 Intent 类型
    const val ACTION_REMINDER = "ACTION_REMINDER"
    const val ACTION_CAPSULE_START = "ACTION_CAPSULE_START"
    const val ACTION_CAPSULE_END = "ACTION_CAPSULE_END"

    // ID 偏移量
    private const val OFFSET_CAPSULE_START = 100000
    private const val OFFSET_CAPSULE_END = 200000

    fun scheduleReminders(context: Context, event: MyEvent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val startDateTime = try {
            LocalDateTime.parse("${event.startDate} ${event.startTime}", formatter)
        } catch (e: Exception) { return }

        val endDateTime = try {
            LocalDateTime.parse("${event.endDate} ${event.endTime}", formatter)
        } catch (e: Exception) { startDateTime.plusHours(1) }

        val startMillis = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 1. 普通提醒 (保持原样)
        event.reminders.forEach { minutesBefore ->
            val triggerTime = startMillis - (minutesBefore * 60 * 1000)
            if (triggerTime > System.currentTimeMillis()) {
                val label = REMINDER_OPTIONS.find { it.first == minutesBefore }?.second ?: ""
                scheduleSingleAlarm(
                    context, event, minutesBefore, triggerTime, label,
                    ACTION_REMINDER, alarmManager
                )
            }
        }

        // 2. 强制调度实况胶囊 Start 和 End
        if (startMillis > System.currentTimeMillis()) {
            scheduleCapsuleAlarm(context, event, startMillis, ACTION_CAPSULE_START, alarmManager)
        }

        if (endMillis > System.currentTimeMillis()) {
            scheduleCapsuleAlarm(context, event, endMillis, ACTION_CAPSULE_END, alarmManager)
        }
    }

    private fun scheduleSingleAlarm(
        context: Context, event: MyEvent, minutesBefore: Int, triggerTime: Long, label: String, actionType: String, alarmManager: AlarmManager
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = actionType
            putExtra("EVENT_ID", event.id)
            putExtra("EVENT_TITLE", event.title)
            putExtra("REMINDER_LABEL", label)
        }
        val requestCode = (event.id.hashCode() + minutesBefore).toInt()
        scheduleAlarmExact(context, triggerTime, intent, requestCode, alarmManager)
    }

    private fun scheduleCapsuleAlarm(
        context: Context, event: MyEvent, triggerTime: Long, actionType: String, alarmManager: AlarmManager
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = actionType
            putExtra("EVENT_ID", event.id)
            putExtra("EVENT_TITLE", event.title)
            putExtra("EVENT_LOCATION", event.location)
            putExtra("EVENT_START_TIME", "${event.startTime}")
            putExtra("EVENT_END_TIME", "${event.endTime}")
        }
        val offset = if (actionType == ACTION_CAPSULE_START) OFFSET_CAPSULE_START else OFFSET_CAPSULE_END
        val requestCode = (event.id.hashCode() + offset).toInt()
        scheduleAlarmExact(context, triggerTime, intent, requestCode, alarmManager)
    }

    private fun scheduleAlarmExact(
        context: Context, triggerTime: Long, intent: Intent, requestCode: Int, alarmManager: AlarmManager
    ) {
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerTime, pendingIntent), pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.e("Scheduler", "Permission missing for exact alarm", e)
        }
    }

    fun cancelReminders(context: Context, event: MyEvent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        event.reminders.forEach { minutesBefore ->
            cancelPendingIntent(context, event.id.hashCode() + minutesBefore, alarmManager)
        }
        cancelPendingIntent(context, event.id.hashCode() + OFFSET_CAPSULE_START, alarmManager)
        cancelPendingIntent(context, event.id.hashCode() + OFFSET_CAPSULE_END, alarmManager)
    }

    private fun cancelPendingIntent(context: Context, requestCode: Int, alarmManager: AlarmManager) {
        val intent = Intent(context, AlarmReceiver::class.java)
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