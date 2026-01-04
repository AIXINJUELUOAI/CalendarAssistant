package com.antgskds.calendarassistant.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R

/**
 * 实况胶囊前台服务 (CapsuleService)
 *
 * 核心职责：
 * 1. 作为一个正规的 [Service]，向系统申请 [startForeground] 权限。
 * 2. 维护 [activeNotifications] 集合，解决“多事件重叠”时的通知管理问题。
 * 3. [修复 Bug]: 即使存在普通通知，也要强制胶囊独立显示 (分组隔离 + 立即展示)。
 * 4. [修复 Crash]: 维护 isServiceRunning 状态，防止外部在服务未启动时错误调用 startForegroundService 发送停止命令。
 */
class CapsuleService : Service() {

    // 存储当前活跃的通知对象：Map<NotificationId, Notification>
    private val activeNotifications = mutableMapOf<Int, Notification>()

    // 记录当前哪一个 ID 是“前台锚点”
    private var currentForegroundId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    // 【新增 1】：维护服务运行状态
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.d(TAG, "Service Created, isServiceRunning = true")
    }

    // 【新增 2】：服务销毁时重置状态
    override fun onDestroy() {
        isServiceRunning = false
        Log.d(TAG, "Service Destroyed, isServiceRunning = false")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val eventIdStr = intent?.getStringExtra("EVENT_ID") ?: ""

        if (eventIdStr.isEmpty()) return START_NOT_STICKY

        val notificationId = eventIdStr.hashCode()

        if (action == ACTION_START) {
            handleStart(intent, notificationId)
        } else if (action == ACTION_STOP) {
            handleStop(notificationId)
        }

        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent?, notificationId: Int) {
        val title = intent?.getStringExtra("EVENT_TITLE") ?: "日程进行中"
        val location = intent?.getStringExtra("EVENT_LOCATION") ?: ""
        val startTime = intent?.getStringExtra("EVENT_START_TIME") ?: ""
        val endTime = intent?.getStringExtra("EVENT_END_TIME") ?: ""

        val notification = buildCapsuleNotification(notificationId, title, location, startTime, endTime)
        activeNotifications[notificationId] = notification
        promoteToForeground(notificationId, notification)
    }

    private fun handleStop(notificationId: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        activeNotifications.remove(notificationId)

        if (notificationId == currentForegroundId) {
            if (activeNotifications.isNotEmpty()) {
                val nextAnchorId = activeNotifications.keys.last()
                val nextNotification = activeNotifications[nextAnchorId]

                if (nextNotification != null) {
                    Log.d(TAG, "锚点转移: $currentForegroundId -> $nextAnchorId")
                    promoteToForeground(nextAnchorId, nextNotification)
                }
                manager.cancel(notificationId)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.d(TAG, "所有胶囊已结束，服务停止")
            }
        } else {
            manager.cancel(notificationId)
            Log.d(TAG, "移除普通胶囊: $notificationId")
        }
    }

    private fun promoteToForeground(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(id, notification)
            }
            currentForegroundId = id
            Log.d(TAG, "startForeground 成功, 锚点ID: $id")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, notification)
        }
    }

    private fun buildCapsuleNotification(
        notifId: Int,
        title: String,
        location: String,
        startTime: String,
        endTime: String
    ): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notifId, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val collapsedTitle = if (title.length > 4) "${title.take(4)}..." else title
        val locationStr = if (location.isNotBlank()) "地点: $location" else ""
        val timeStr = "$startTime - $endTime"
        val expandedContent = "$timeStr\n$locationStr".trim()
        val capsuleColor = Color.parseColor("#00C853")

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, MyApplication.CHANNEL_ID_LIVE)
        } else {
            Notification.Builder(this)
        }

        val icon = Icon.createWithResource(this, R.mipmap.ic_launcher)

        builder.setSmallIcon(icon)
            .setContentTitle(collapsedTitle)
            .setContentText("进行中")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(capsuleColor)
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.BigTextStyle()
                .setBigContentTitle(title)
                .bigText(expandedContent)
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        builder.setGroup("LIVE_CAPSULE_GROUP")
        builder.setGroupSummary(false)
        builder.setWhen(System.currentTimeMillis())
        builder.setShowWhen(true)
        builder.setSortKey(System.currentTimeMillis().toString())

        try {
            val methodSetText = Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
            methodSetText.invoke(builder, collapsedTitle)
        } catch (e: Exception) { /* ignore */ }

        try {
            val methodSetPromoted = Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.java)
            methodSetPromoted.invoke(builder, true)
        } catch (e: Exception) { /* ignore */ }

        val extras = Bundle()
        extras.putBoolean("android.substName", true)
        extras.putString("android.title", collapsedTitle)
        builder.addExtras(extras)

        builder.setOnlyAlertOnce(true)

        return builder.build()
    }

    companion object {
        const val TAG = "CapsuleService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"

        // 【新增 3】：全局标志位，供外部查询服务是否存活
        @Volatile
        var isServiceRunning = false
    }
}