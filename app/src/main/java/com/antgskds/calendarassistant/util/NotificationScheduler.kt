package com.antgskds.calendarassistant.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.antgskds.calendarassistant.model.MyEvent
import com.antgskds.calendarassistant.receiver.AlarmReceiver
import com.antgskds.calendarassistant.service.CapsuleService
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object NotificationScheduler {

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

    const val ACTION_REMINDER = "ACTION_REMINDER"
    const val ACTION_CAPSULE_START = "ACTION_CAPSULE_START"
    const val ACTION_CAPSULE_END = "ACTION_CAPSULE_END"

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

        // 1. 取消定时闹钟
        event.reminders.forEach { minutesBefore ->
            cancelPendingIntent(context, event.id.hashCode() + minutesBefore, alarmManager)
        }
        cancelPendingIntent(context, event.id.hashCode() + OFFSET_CAPSULE_START, alarmManager)
        cancelPendingIntent(context, event.id.hashCode() + OFFSET_CAPSULE_END, alarmManager)

        // 2. 【修复 Crash 的核心逻辑】
        // 只有当 CapsuleService 明确处于运行状态时，才发送 STOP 指令。
        if (CapsuleService.isServiceRunning) {
            try {
                val stopIntent = Intent(context, CapsuleService::class.java).apply {
                    this.action = CapsuleService.ACTION_STOP
                    putExtra("EVENT_ID", event.id)
                }

                // 既然服务在运行（即处于前台），使用普通的 startService 是安全且合规的。
                // 这样避免了 startForegroundService 的 5 秒强制契约。
                context.startService(stopIntent)

                Log.d("Scheduler", "检测到服务运行中，已安全发送 STOP 命令: ${event.title}")
            } catch (e: Exception) {
                Log.e("Scheduler", "停止胶囊服务失败", e)
            }
        } else {
            Log.d("Scheduler", "服务未运行，跳过 STOP 命令: ${event.title}")
        }
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