package com.antgskds.calendarassistant.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
    private var analysisJob: Job? = null // 用于管理分析任务的生命周期

    private val NOTIFICATION_ID_STATUS = 1001
    private val NOTIFICATION_ID_LIVE = 2077

    // 【生产环境配置】: 设为 false，如果你想在非魅族手机测试魅族逻辑，可设为 true
    private val TEST_FLYME_LOGIC = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // 处理来自通知栏按钮的点击指令
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ANALYSIS) {
            cancelCurrentAnalysis()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun cancelCurrentAnalysis() {
        Log.d(TAG, "用户取消了分析任务")
        analysisJob?.cancel()
        cancelStatusNotification()
    }

    fun closeNotificationPanel(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    fun startAnalysis(delayDuration: Duration = 500.milliseconds) {
        // 如果当前有正在进行的任务，先取消
        analysisJob?.cancel()

        analysisJob = serviceScope.launch {
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
                    // 在 onSuccess 中继续维护协程任务
                    analysisJob = serviceScope.launch(Dispatchers.IO) {
                        processScreenshot(screenshotResult)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: $errorCode")
                    showNotification("截图失败", "无法获取屏幕内容，错误码: $errorCode")
                }
            }
        )
    }

    private suspend fun processScreenshot(result: ScreenshotResult) {
        try {
            val hardwareBuffer = result.hardwareBuffer
            val colorSpace = result.colorSpace
            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (bitmap == null) {
                cancelStatusNotification()
                return
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

            val settings = MyApplication.getInstance().getSettings()
            if (settings.modelKey.isBlank()) {
                withContext(Dispatchers.Main) {
                    showNotification("配置缺失", "请先在 App 侧边栏填写 API Key", isProgress = false, autoLaunch = true)
                }
                return
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
                        val titleText = if (settings.autoCreateAlarm) {
                            "成功创建 ${normalEvents.size} 条事项和 ${normalEvents.size} 个闹钟"
                        } else {
                            "成功创建 ${normalEvents.size} 条事项"
                        }
                        showNotification(titleText, titles, isProgress = false, autoLaunch = false)
                    }
                }

                // 2. 取件码 -> 实时通知
                if (pickupEvents.isNotEmpty()) {
                    saveEventsLocally(pickupEvents, imageFile.absolutePath)
                    val pickup = pickupEvents.first()

                    val chipText = pickup.description.trim() // 取件码
                    val title = pickup.title
                    val location = if (pickup.location.isNotBlank()) pickup.location else "暂无位置信息"
                    val content = "位置: $location | 号码: $chipText"

                    withContext(Dispatchers.Main) {
                        cancelStatusNotification()
                        postLiveUpdateSafely(chipText, title, content)
                    }
                }

            } else {
                withContext(Dispatchers.Main) {
                    showNotification("分析完成", "未识别到有效日程", isProgress = false)
                }
            }

        } catch (e: Exception) {
            if (e is CancellationException) throw e // 确保协程取消正常工作
            Log.e(TAG, "Error", e)
            withContext(Dispatchers.Main) {
                showNotification("分析出错", "错误信息: ${e.message}")
            }
        }
    }

    private fun saveEventsLocally(aiEvents: List<CalendarEventData>, imagePath: String) {
        try {
            val app = MyApplication.getInstance()
            val settings = app.getSettings()
            val eventStore = EventJsonStore(this)
            val currentEvents = eventStore.loadEvents()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val shouldAutoAlarm = settings.autoCreateAlarm

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

                if (shouldAutoAlarm && finalEventType == "event") {
                    createSystemAlarm(aiEvent.title, startDateTime.hour, startDateTime.minute, startDateTime.toLocalDate())
                }
            }
            eventStore.saveEvents(currentEvents)
        } catch (e: Exception) {
            Log.e(TAG, "后台保存失败", e)
        }
    }

    private fun createSystemAlarm(title: String, hour: Int, minute: Int, date: java.time.LocalDate) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)

                if (date != java.time.LocalDate.now()) {
                    val dayOfWeek = date.dayOfWeek.value
                    val calendarDay = when (dayOfWeek) {
                        7 -> java.util.Calendar.SUNDAY
                        else -> dayOfWeek + 1
                    }
                    putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(calendarDay))
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "无法设置闹钟", e)
        }
    }

    private fun cancelStatusNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_STATUS)
    }

    private fun isFlyme(): Boolean {
        val displayId = Build.DISPLAY
        val manufacturer = Build.MANUFACTURER
        return manufacturer.contains("Meizu", ignoreCase = true) || displayId.contains("Flyme", ignoreCase = true)
    }

    private fun postLiveUpdateSafely(chipText: String, title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val capsuleColor = if (isMealEvent(title)) Color.parseColor("#FFD600") // 黄色
        else if (isPackageEvent(title)) Color.parseColor("#2196F3") // 蓝色
        else Color.parseColor("#00C853") // 绿色

        val isFlymeEnv = isFlyme()

        if (isFlymeEnv || TEST_FLYME_LOGIC) {
            postFlymeLiveNotification(manager, chipText, title, content, pendingIntent, capsuleColor)
        }
        else if (Build.VERSION.SDK_INT >= 36) {
            if (!manager.canPostPromotedNotifications()) {
                sendPermissionGuidanceNotification()
                return
            }
            postNativeBaklavaNotification(manager, chipText, title, content, pendingIntent, capsuleColor)
        }
        else {
            postCompatSamsungNotification(manager, title, content, pendingIntent, capsuleColor)
        }
    }

    private fun postFlymeLiveNotification(
        manager: NotificationManager,
        chipText: String,
        title: String,
        content: String,
        pendingIntent: PendingIntent,
        color: Int
    ) {
        try {
            val iconDrawable = ContextCompat.getDrawable(this, R.drawable.ic_qs_recognition)
                ?: ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)

            val iconBitmap = iconDrawable?.mutate()?.let {
                val bmp = it.toBitmap()
                tintBitmap(bmp, Color.WHITE)
            }
            val iconObj = if (iconBitmap != null) Icon.createWithBitmap(iconBitmap) else null

            val capsuleBundle = Bundle().apply {
                putInt("notification.live.capsuleStatus", 1)
                putInt("notification.live.capsuleType", 3)
                putString("notification.live.capsuleContent", chipText)

                if (iconObj != null) {
                    putParcelable("notification.live.capsuleIcon", iconObj)
                }
                putInt("notification.live.capsuleBgColor", color)
                putInt("notification.live.capsuleContentColor", Color.WHITE)
            }

            val liveBundle = Bundle().apply {
                putBoolean("is_live", true)
                putInt("notification.live.operation", 0)
                putInt("notification.live.type", 10)
                putBundle("notification.live.capsule", capsuleBundle)
                putInt("notification.live.contentColor", Color.WHITE)
            }

            val locationText = content.replace("位置: ", "").replace("| 号码: $chipText", "").trim()
            val finalLocation = if (locationText.isBlank() || locationText == "暂无位置信息") title else locationText

            val contentRemoteViews = RemoteViews(packageName, R.layout.notification_live_flyme).apply {
                setTextViewText(R.id.live_title, title)
                setTextViewText(R.id.live_content, chipText)
                setTextViewText(R.id.live_location, finalLocation)
                setTextViewText(R.id.live_time, "刚刚")
                if (iconBitmap != null) {
                    setImageViewBitmap(R.id.live_icon, iconBitmap)
                }
            }

            val builder = Notification.Builder(this, MyApplication.CHANNEL_ID_LIVE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setCustomContentView(contentRemoteViews)
                .setCustomBigContentView(contentRemoteViews)
                .addExtras(liveBundle)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_EVENT)

            manager.notify(NOTIFICATION_ID_LIVE, builder.build())
        } catch (e: Exception) {
            postCompatSamsungNotification(manager, title, content, pendingIntent, color)
        }
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

    private fun postNativeBaklavaNotification(
        manager: NotificationManager,
        critText: String,
        title: String,
        content: String,
        pendingIntent: PendingIntent,
        color: Int
    ) {
        try {
            val icon = android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher)
            val builder = Notification.Builder(this, MyApplication.CHANNEL_ID_LIVE)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setColor(color)

            val methodSetText = Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
            methodSetText.invoke(builder, critText)

            val methodSetPromoted = Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.java)
            methodSetPromoted.invoke(builder, true)

            manager.notify(NOTIFICATION_ID_LIVE, builder.build())
        } catch (e: Exception) {
            postCompatSamsungNotification(manager, title, content, pendingIntent, color)
        }
    }

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
            .setColor(color)
            .setColorized(true)
        manager.notify(NOTIFICATION_ID_LIVE, builder.build())
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

            // 添加取消按钮
            val cancelIntent = Intent(this, TextAccessibilityService::class.java).apply {
                action = ACTION_CANCEL_ANALYSIS
            }
            val cancelPendingIntent = PendingIntent.getService(
                this, 102, cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消分析", cancelPendingIntent)

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
        private const val ACTION_CANCEL_ANALYSIS = "ACTION_CANCEL_ANALYSIS"
        @Volatile var instance: TextAccessibilityService? = null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; serviceScope.cancel(); super.onDestroy() }
}
