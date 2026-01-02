// --- FILE: com/antgskds/calendarassistant/receiver/DailySummaryReceiver.kt ---

package com.antgskds.calendarassistant.receiver

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antgskds.calendarassistant.EventJsonStore
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 负责接收每日定时的广播，并发送通知
 */
class DailySummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 1. 判断是早报还是晚报
        val isMorning = action == ACTION_MORNING_SUMMARY
        val summaryType = if (isMorning) "早报(今日)" else "晚报(明日)"

        Log.i(TAG, ">>> 收到定时广播: [$summaryType]")

        // 早报看今天，晚报看明天
        val targetDate = if (isMorning) LocalDate.now() else LocalDate.now().plusDays(1)
        val title = if (isMorning) "今日日程早报" else "明日日程预告"
        val notifId = if (isMorning) ID_MORNING else ID_NIGHT

        // 2. 读取本地数据
        val eventStore = EventJsonStore(context)
        val allEvents = eventStore.loadEvents()

        // 3. 筛选并排序 (只选目标日期的普通日程，忽略过期的或临时的)
        val targetEvents = allEvents.filter { event ->
            event.startDate == targetDate && event.eventType == "event"
        }.sortedBy { it.startTime }

        // ---【新增日志：输出找到的日程详情】---
        Log.i(TAG, "日期: $targetDate, 找到符合条件的日程数: ${targetEvents.size}")
        targetEvents.forEachIndexed { index, event ->
            Log.i(TAG, "  [$index] ${event.startTime} - ${event.title}")
        }
        // ------------------------------------

        // 4. 构建通知样式 (列表样式)
        val inboxStyle = NotificationCompat.InboxStyle()
        val contentText: String

        if (targetEvents.isEmpty()) {
            contentText = "暂无安排"
            inboxStyle.addLine("当前没有任何日程记录")
        } else {
            contentText = "共有 ${targetEvents.size} 个日程安排"
            targetEvents.forEach { event ->
                inboxStyle.addLine("• [${event.startTime}] ${event.title}")
            }
        }

        // 5. 点击跳转回主页
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notifId, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 6. 发送通知
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(inboxStyle) // 使用多行样式
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notification)
        Log.i(TAG, "通知已发送 (ID: $notifId)")

        // 7. 【关键】自动设置明天的同一时间 (链式触发，保证循环)
        DailySummaryScheduler.scheduleNextRun(context, isMorning)
    }

    companion object {
        const val TAG = "DailySummary" // 用于 Logcat 过滤
        const val ACTION_MORNING_SUMMARY = "com.antgskds.calendarassistant.action.MORNING_SUMMARY"
        const val ACTION_NIGHT_SUMMARY = "com.antgskds.calendarassistant.action.NIGHT_SUMMARY"
        const val ID_MORNING = 8001
        const val ID_NIGHT = 8002
    }
}

/**
 * 调度器：负责计算时间并调用 AlarmManager
 */
object DailySummaryScheduler {

    private const val TAG = "DailyScheduler"

    // 配置时间点
    private val MORNING_TIME = LocalTime.of(6, 0)  // 早上 06:00
    private val NIGHT_TIME = LocalTime.of(22, 0)   // 晚上 22:00

    // 开启所有提醒
    fun scheduleAll(context: Context) {
        Log.i(TAG, "正在初始化所有定时任务...")
        scheduleNextRun(context, isMorning = true)
        scheduleNextRun(context, isMorning = false)
    }

    // 关闭所有提醒
    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 取消早上的 PendingIntent
        val pMorning = createPendingIntent(context, true, 0)
        am.cancel(pMorning)
        pMorning.cancel()

        // 取消晚上的 PendingIntent
        val pNight = createPendingIntent(context, false, 0)
        am.cancel(pNight)
        pNight.cancel()

        Log.i(TAG, "已取消所有每日提醒任务")
    }

    // 计算下一次触发时间并设置
    fun scheduleNextRun(context: Context, isMorning: Boolean) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val targetTime = if (isMorning) MORNING_TIME else NIGHT_TIME
        val typeStr = if (isMorning) "早报" else "晚报"

        val now = LocalDateTime.now()
        var nextTriggerDate = LocalDate.now()
        var nextTriggerDateTime = LocalDateTime.of(nextTriggerDate, targetTime)

        // 如果今天的时间已经过了，就设为明天
        if (now.isAfter(nextTriggerDateTime)) {
            nextTriggerDateTime = nextTriggerDateTime.plusDays(1)
        }

        val triggerMillis = nextTriggerDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = createPendingIntent(context, isMorning, PendingIntent.FLAG_UPDATE_CURRENT)

        // ---【新增日志：输出下次触发时间】---
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        Log.i(TAG, "设置下次任务 [$typeStr] -> ${nextTriggerDateTime.format(fmt)}")
        // ---------------------------------

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        }
    }

    private fun createPendingIntent(context: Context, isMorning: Boolean, flags: Int): PendingIntent {
        val action = if (isMorning) DailySummaryReceiver.ACTION_MORNING_SUMMARY else DailySummaryReceiver.ACTION_NIGHT_SUMMARY
        val reqCode = if (isMorning) 1001 else 1002

        val intent = Intent(context, DailySummaryReceiver::class.java).apply {
            this.action = action
            setPackage(context.packageName)
        }

        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or flags
        )
    }
}