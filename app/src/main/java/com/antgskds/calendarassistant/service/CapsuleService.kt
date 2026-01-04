package com.antgskds.calendarassistant.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R
import com.antgskds.calendarassistant.util.FlymeUtils

/**
 * 实况胶囊前台服务 (CapsuleService)
 *
 * 核心职责：
 * 1. 作为一个正规的 [Service]，向系统申请 [startForeground] 权限。
 * 2. 维护 [activeNotifications] 集合，解决“多事件重叠”时的通知管理问题。
 * 3. [修复 Bug]: 即使存在普通通知，也要强制胶囊独立显示 (分组隔离 + 立即展示)。
 * 4. [修复 Crash]: 维护 isServiceRunning 状态。
 * 5. [新增]: 适配魅族 Flyme 实况胶囊。
 */
class CapsuleService : Service() {

    // 存储当前活跃的通知对象
    private val activeNotifications = mutableMapOf<Int, Notification>()

    // 记录当前哪一个 ID 是“前台锚点”
    private var currentForegroundId = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Log.d(TAG, "Service Created, isServiceRunning = true")
    }

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

        // 使用 mipmap 图标
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

        // Android 16 (Baklava) 反射
        try {
            val methodSetText = Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
            methodSetText.invoke(builder, collapsedTitle)
        } catch (e: Exception) { /* ignore */ }

        try {
            val methodSetPromoted = Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.java)
            methodSetPromoted.invoke(builder, true)
        } catch (e: Exception) { /* ignore */ }

        // ========================================================================
        // 【新增】：魅族 Flyme 实况胶囊适配
        // ========================================================================
        if (FlymeUtils.isFlyme()) {
            try {
                // 准备图标 (将 mipmap/drawable 转为白色 Bitmap)
                var iconDrawable = ContextCompat.getDrawable(this, R.drawable.ic_qs_recognition)
                if (iconDrawable == null) iconDrawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)

                val iconBitmap = iconDrawable?.let { drawableToBitmap(it) }?.let {
                    tintBitmap(it, Color.WHITE)
                }
                val iconObj = if (iconBitmap != null) Icon.createWithBitmap(iconBitmap) else null

                // 胶囊参数
                val capsuleBundle = Bundle().apply {
                    putInt("notification.live.capsuleStatus", 1)
                    putInt("notification.live.capsuleType", 1)
                    putString("notification.live.capsuleContent", collapsedTitle)
                    if (iconObj != null) {
                        putParcelable("notification.live.capsuleIcon", iconObj)
                    }
                    putInt("notification.live.capsuleBgColor", capsuleColor)
                    putInt("notification.live.capsuleContentColor", Color.WHITE)
                }

                // 外部 Bundle
                val liveBundle = Bundle().apply {
                    putBoolean("is_live", true)
                    putInt("notification.live.operation", 0)
                    putInt("notification.live.type", 10)
                    putBundle("notification.live.capsule", capsuleBundle)
                    putInt("notification.live.contentColor", Color.BLACK)
                }

                builder.addExtras(liveBundle)
            } catch (e: Exception) {
                Log.e(TAG, "Flyme 适配异常", e)
            }
        }

        val extras = Bundle()
        extras.putBoolean("android.substName", true)
        extras.putString("android.title", collapsedTitle)
        builder.addExtras(extras)

        builder.setOnlyAlertOnce(true)

        return builder.build()
    }

    // =========================================
    // 辅助方法：处理图标颜色
    // =========================================
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun tintBitmap(source: Bitmap, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return bitmap
    }

    companion object {
        const val TAG = "CapsuleService"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        @Volatile
        var isServiceRunning = false
    }
}