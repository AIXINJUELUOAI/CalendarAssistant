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
 * 1. 作为一个正规的 [Service]，向系统申请 [startForeground] 权限，获取 Android 16 的信任。
 * 2. 维护一个 [activeNotifications] 集合，解决“多事件重叠”时的通知管理问题。
 *
 * 解决“通知卡死”的关键逻辑：
 * Android 的 Service 只能有一个 Notification 作为“前台锚点”。
 * 当有多个胶囊（A和B）同时存在时，如果 B 是当前的锚点，而 B 结束了：
 * 我们不能直接 cancel(B)，否则服务会失去锚点。
 * 我们必须先将锚点转移给 A (startForeground(A))，然后再 cancel(B)。
 */
class CapsuleService : Service() {

    // 存储当前活跃的通知对象：Map<NotificationId, Notification>
    // 作用：当需要转移前台锚点时，我们可以从这里取出旧的 Notification 对象重新绑定
    private val activeNotifications = mutableMapOf<Int, Notification>()

    // 记录当前哪一个 ID 是“前台锚点”
    private var currentForegroundId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // 获取 Event ID。如果为空则无法处理，直接忽略。
        val eventIdStr = intent?.getStringExtra("EVENT_ID") ?: ""

        if (eventIdStr.isEmpty()) return START_NOT_STICKY

        // 使用 HashCode 作为唯一 ID，与 AlarmReceiver 保持一致
        val notificationId = eventIdStr.hashCode()

        if (action == ACTION_START) {
            handleStart(intent, notificationId)
        } else if (action == ACTION_STOP) {
            handleStop(notificationId)
        }

        return START_NOT_STICKY
    }

    /**
     * 处理 [ACTION_START]：显示胶囊
     */
    private fun handleStart(intent: Intent?, notificationId: Int) {
        val title = intent?.getStringExtra("EVENT_TITLE") ?: "日程进行中"
        val location = intent?.getStringExtra("EVENT_LOCATION") ?: ""
        val startTime = intent?.getStringExtra("EVENT_START_TIME") ?: ""
        val endTime = intent?.getStringExtra("EVENT_END_TIME") ?: ""

        // 1. 构建 Notification 对象
        val notification = buildCapsuleNotification(notificationId, title, location, startTime, endTime)

        // 2. 存入 Map，作为“资产”备份
        activeNotifications[notificationId] = notification

        // 3. 抢占前台 (Promote to Foreground)
        // 无论是第一个还是第二个事件，最新的这个事件总是成为新的“前台锚点”。
        // 这符合“后进先出”的视觉逻辑，也让最新的胶囊获得最高优先级。
        promoteToForeground(notificationId, notification)
    }

    /**
     * 处理 [ACTION_STOP]：撤销胶囊
     */
    private fun handleStop(notificationId: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. 从资产 Map 中移除
        activeNotifications.remove(notificationId)

        // 2. 检查：我们正在移除的这个 ID，是不是当前撑着服务的“锚点”？
        if (notificationId == currentForegroundId) {
            // 糟糕，我们要移除锚点了。
            if (activeNotifications.isNotEmpty()) {
                // 情况 A：还有其他胶囊（例如事件1还在进行）。
                // 战术：找一个“替补”上位。
                // 我们取 Map 中剩下的最后一个（通常是上一个事件），让它重新成为前台。
                val nextAnchorId = activeNotifications.keys.last()
                val nextNotification = activeNotifications[nextAnchorId]

                if (nextNotification != null) {
                    Log.d(TAG, "锚点转移: $currentForegroundId -> $nextAnchorId")
                    promoteToForeground(nextAnchorId, nextNotification)
                }

                // 转移成功后，现在可以安全地 Cancel 掉旧的 ID 了
                manager.cancel(notificationId)
            } else {
                // 情况 B：没有其他胶囊了。
                // 战术：直接停止服务。
                // stopForeground(true) 会自动移除当前的 Notification，所以不需要手动 cancel。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.d(TAG, "所有胶囊已结束，服务停止")
            }
        } else {
            // 情况 C：我们移除的只是一个普通的胶囊，不是锚点。
            // 直接 Cancel 即可，不影响服务存活。
            manager.cancel(notificationId)
            Log.d(TAG, "移除普通胶囊: $notificationId")
        }
    }

    /**
     * 将指定 ID 提升为前台锚点
     */
    private fun promoteToForeground(id: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 必须声明类型
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(id, notification)
            }
            // 更新记录
            currentForegroundId = id
            Log.d(TAG, "startForeground 成功, 锚点ID: $id")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败", e)
            // 补救措施：如果前台启动失败，至少尝试发个普通通知
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, notification)
        }
    }

    /**
     * 构建实况通知对象
     * 完全对齐“快递通知”的成功参数：
     * 1. 使用 mipmap 图标
     * 2. 移除 setColorized
     * 3. 强制反射调用 Android 16 API
     */
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

        // 图标：使用 mipmap，对齐成功案例
        val icon = Icon.createWithResource(this, R.mipmap.ic_launcher)

        builder.setSmallIcon(icon)
            .setContentTitle(collapsedTitle)
            .setContentText("进行中")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setColor(capsuleColor)
            // 关键：不要设置 setColorized(true)，防止被系统误判为 MediaStyle 而拒绝胶囊化
            .setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.BigTextStyle()
                .setBigContentTitle(title)
                .bigText(expandedContent)
            )

        // Android 16 (Baklava) 强制反射
        try {
            val methodSetText = Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
            methodSetText.invoke(builder, collapsedTitle)
        } catch (e: Exception) { /* ignore */ }

        try {
            val methodSetPromoted = Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.java)
            methodSetPromoted.invoke(builder, true)
        } catch (e: Exception) { /* ignore */ }

        // 兼容性 Extras
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
    }
}