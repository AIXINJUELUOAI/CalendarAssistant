package com.antgskds.calendarassistant.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.antgskds.calendarassistant.*
import com.antgskds.calendarassistant.llm.RecognitionProcessor
import com.antgskds.calendarassistant.model.CalendarEventData
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TextAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val NOTIFICATION_ID_STATUS = 1001
    private val NOTIFICATION_ID_LIVE = 2077

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun closeNotificationPanel(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    fun startAnalysis(delayDuration: Duration = 500.milliseconds) {
        serviceScope.launch {
            delay(delayDuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshotAndAnalyze()
            } else {
                showNotification("系统版本过低", "截图功能需要 Android 11+")
            }
        }
    }

    private fun takeScreenshotAndAnalyze() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        showNotification("日程助手", "正在分析屏幕内容...", isProgress = true)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    processScreenshot(screenshotResult)
                }
                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: $errorCode")
                    showNotification("截图失败", "无法获取屏幕内容，错误码: $errorCode")
                }
            }
        )
    }

    private fun processScreenshot(result: ScreenshotResult) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val hardwareBuffer = result.hardwareBuffer
                val colorSpace = result.colorSpace
                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)

                if (bitmap == null) {
                    cancelStatusNotification() // 失败也要取消进度条
                    return@launch
                }

                val imagesDir = File(filesDir, "event_screenshots")
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imageFile = File(imagesDir, "IMG_$timestamp.jpg")

                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle()
                hardwareBuffer.close()

                FileOutputStream(imageFile).use { out ->
                    softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                Log.d(TAG, "截图保存: ${imageFile.absolutePath}")

                val settings = MyApplication.getInstance().getSettings()
                if (settings.modelKey.isBlank() || settings.modelUrl.isBlank()) {
                    withContext(Dispatchers.Main) {
                        showNotification("配置缺失", "请先在 App 侧边栏填写 API Key 和 URL", isProgress = false, autoLaunch = true)
                    }
                    return@launch
                }

                val eventsList = RecognitionProcessor.analyzeImage(softwareBitmap)
                softwareBitmap.recycle()

                val validEvents = eventsList.filter { it.title.isNotBlank() }

                if (validEvents.isNotEmpty()) {
                    val (pickupEvents, normalEvents) = validEvents.partition { it.type == "pickup" }

                    // 1. 普通日程
                    if (normalEvents.isNotEmpty()) {
                        saveEventsLocally(normalEvents, imageFile.absolutePath)
                        withContext(Dispatchers.Main) {
                            val titles = normalEvents.joinToString(separator = "，") { it.title }
                            showNotification("成功创建 ${normalEvents.size} 条事项", titles, isProgress = false, autoLaunch = false)
                        }
                    }

                    // 2. 取件码 -> 胶囊通知 (Live Update)
                    if (pickupEvents.isNotEmpty()) {
                        saveEventsLocally(pickupEvents, imageFile.absolutePath)

                        val pickup = pickupEvents.first()
                        val chipText = if (pickup.description.length in 1..10) pickup.description
                        else pickup.title.take(6)
                        val title = pickup.title
                        val content = "位置: ${pickup.location} | 号码: ${pickup.description}"

                        withContext(Dispatchers.Main) {
                            // 【修复2】发送胶囊前，先取消“正在分析”的进度条通知
                            cancelStatusNotification()
                            postLiveUpdateSafely(chipText, title, content)
                        }
                    } else {
                        // 如果只有普通日程没有取件码，也要把普通日程的通知 ID 覆盖掉状态通知
                        // (上面的 showNotification 用的 ID 是 NOTIFICATION_ID_STATUS，已经自动覆盖了)
                    }

                } else {
                    withContext(Dispatchers.Main) {
                        showNotification("分析完成", "未识别到有效日程", isProgress = false)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                withContext(Dispatchers.Main) {
                    showNotification("分析出错", "错误信息: ${e.message}")
                }
            }
        }
    }

    // 取消“正在分析”的通知
    private fun cancelStatusNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)
    }

    private fun postLiveUpdateSafely(critText: String, title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 1. Android 16+ 权限检查
        if (Build.VERSION.SDK_INT >= 36) {
            if (!manager.canPostPromotedNotifications()) {
                sendPermissionGuidanceNotification()
                return
            }
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 动态颜色计算
        val capsuleColor = if (isMealEvent(title)) Color.parseColor("#FFD600") // 黄
        else if (isPackageEvent(title)) Color.parseColor("#2196F3") // 蓝
        else Color.parseColor("#00C853") // 绿

        // 【修复1】分支策略
        // 如果是 Android 16+ (原生胶囊)，必须去掉 setColorized(true)，否则无法“上岛”
        if (Build.VERSION.SDK_INT >= 36) {
            postNativeBaklavaNotification(manager, critText, title, content, pendingIntent, capsuleColor)
        } else {
            // 如果是 三星/旧版本，必须保留 setColorized(true) 才能触发灵动岛
            postCompatSamsungNotification(manager, title, content, pendingIntent, capsuleColor)
        }
    }

    /**
     * 针对 Android 16 (Baklava) 的原生路径
     * 特点：无 Colorized，反射调用 Live Update API
     */
    private fun postNativeBaklavaNotification(
        manager: NotificationManager,
        critText: String,
        title: String,
        content: String,
        pendingIntent: PendingIntent,
        color: Int
    ) {
        try {
            val builder = Notification.Builder(this, MyApplication.CHANNEL_ID_LIVE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setStyle(Notification.BigTextStyle().bigText(content))
                // 注意：Android 16 规范禁止 setCustomContentView 和 setColorized(true)
                // 但允许 setColor，系统可能会用它来渲染胶囊的图标或文字颜色
                .setColor(color)

            // 反射调用 setShortCriticalText
            val methodSetText = Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
            methodSetText.invoke(builder, critText)

            // 反射调用 setRequestPromotedOngoing
            val methodSetPromoted = Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.java)
            methodSetPromoted.invoke(builder, true)

            manager.notify(NOTIFICATION_ID_LIVE, builder.build())
            Log.d(TAG, "Posted Android 16 Native Live Update")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to post native Baklava notification", e)
            // 失败则降级
            postCompatSamsungNotification(manager, title, content, pendingIntent, color)
        }
    }

    /**
     * 针对 三星 One UI / 旧 Android 的兼容路径
     * 特点：启用 Colorized(true) 以触发三星灵动岛
     */
    private fun postCompatSamsungNotification(
        manager: NotificationManager,
        title: String,
        content: String,
        pendingIntent: PendingIntent,
        color: Int
    ) {
        val builder = NotificationCompat.Builder(this, MyApplication.CHANNEL_ID_LIVE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            // 三星灵动岛的核心：必须着色
            .setColor(color)
            .setColorized(true)

        manager.notify(NOTIFICATION_ID_LIVE, builder.build())
        Log.d(TAG, "Posted Compat/Samsung Notification")
    }

    private fun isMealEvent(title: String): Boolean {
        val keywords = listOf("取餐", "麦当劳", "肯德基", "必胜客", "星巴克", "瑞幸", "喜茶", "奈雪", "餐饮", "外卖", "饭")
        return keywords.any { title.contains(it, ignoreCase = true) }
    }

    private fun isPackageEvent(title: String): Boolean {
        val keywords = listOf("取件", "快递", "驿站", "包裹", "丰巢", "菜鸟", "顺丰", "京东", "邮政", "中通", "圆通", "申通", "韵达")
        return keywords.any { title.contains(it, ignoreCase = true) }
    }

    private fun sendPermissionGuidanceNotification() {
        val intent = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("权限提示")
            .setContentText("请点击开启“允许提升通知”权限。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(999, builder.build())
    }

    private fun saveEventsLocally(aiEvents: List<CalendarEventData>, imagePath: String) {
        try {
            val eventStore = EventJsonStore(this)
            val currentEvents = eventStore.loadEvents()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

            aiEvents.forEach { aiEvent ->
                val startDateTime = try {
                    if (aiEvent.startTime.isNotBlank()) LocalDateTime.parse(aiEvent.startTime, formatter) else LocalDateTime.now()
                } catch (e: Exception) { LocalDateTime.now() }

                val endDateTime = try {
                    if (aiEvent.endTime.isNotBlank()) LocalDateTime.parse(aiEvent.endTime, formatter) else startDateTime.plusHours(1)
                } catch (e: Exception) { startDateTime.plusHours(1) }

                val finalEventType = if (aiEvent.type == "pickup") "temp" else "event"

                val newEvent = MyEvent(
                    id = UUID.randomUUID().toString(),
                    title = aiEvent.title,
                    startDate = startDateTime.toLocalDate(),
                    endDate = endDateTime.toLocalDate(),
                    startTime = startDateTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    endTime = endDateTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                    location = aiEvent.location,
                    description = aiEvent.description,
                    color = getNextColor(currentEvents.size),
                    sourceImagePath = imagePath,
                    eventType = finalEventType
                )
                currentEvents.add(newEvent)
            }
            eventStore.saveEvents(currentEvents)
        } catch (e: Exception) {
            Log.e(TAG, "后台保存失败", e)
        }
    }

    private fun showNotification(title: String, content: String, isProgress: Boolean = false, autoLaunch: Boolean = false) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (isProgress) {
            builder.setProgress(0, 0, true)
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
            builder.setOngoing(true)
        } else {
            builder.setProgress(0, 0, false)
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            builder.setOngoing(false)
        }
        manager.notify(NOTIFICATION_ID_STATUS, builder.build())
        if (autoLaunch) startActivity(intent)
    }

    companion object {
        private const val TAG = "TextAccessibilityService"
        @Volatile var instance: TextAccessibilityService? = null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; serviceScope.cancel(); super.onDestroy() }
}