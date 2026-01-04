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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.core.graphics.ColorUtils
import com.antgskds.calendarassistant.EventJsonStore
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.MyEvent
import com.antgskds.calendarassistant.R
import com.antgskds.calendarassistant.getNextColor
import com.antgskds.calendarassistant.llm.RecognitionProcessor
import com.antgskds.calendarassistant.model.CalendarEventData
import com.antgskds.calendarassistant.util.FlymeUtils
import com.antgskds.calendarassistant.util.NotificationScheduler
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
    private var analysisJob: Job? = null
    private val NOTIFICATION_ID_STATUS = 1001
    private val NOTIFICATION_ID_LIVE = 2077 // 取件码实况通知 ID
    private val TEST_FLYME_LOGIC = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_CANCEL_ANALYSIS") {
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

    /**
     * 开始截图分析流程
     */
    fun startAnalysis(delayDuration: Duration = 500.milliseconds) {
        Log.e("TextAccessDebug", "startAnalysis 被调用，延迟: $delayDuration")

        analysisJob?.cancel()
        analysisJob = serviceScope.launch {
            Log.e("TextAccessDebug", "协程启动，开始等待...")
            delay(delayDuration)
            Log.e("TextAccessDebug", "等待结束，准备截图...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshotAndAnalyze()
            } else {
                showNotification("系统版本过低", "截图功能需要 Android 11+")
            }
        }
    }

    private fun takeScreenshotAndAnalyze() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        Log.e("TextAccessDebug", "调用系统 takeScreenshot API")
        showNotification("日程助手", "正在分析屏幕内容...", isProgress = true)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    Log.e("TextAccessDebug", "截图成功 onSuccess")
                    analysisJob = serviceScope.launch(Dispatchers.IO) {
                        processScreenshot(screenshotResult)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.e("TextAccessDebug", "截图失败 onFailure: $errorCode")
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

                val addedNormalEvents = if (normalEvents.isNotEmpty()) {
                    saveEventsLocally(normalEvents, imageFile.absolutePath)
                } else emptyList()

                val addedPickupEvents = if (pickupEvents.isNotEmpty()) {
                    saveEventsLocally(pickupEvents, imageFile.absolutePath)
                } else emptyList()

                withContext(Dispatchers.Main) {
                    // 1. 普通日程结果通知
                    if (normalEvents.isNotEmpty()) {
                        val duplicateCount = normalEvents.size - addedNormalEvents.size
                        if (addedNormalEvents.isNotEmpty()) {
                            val titles = addedNormalEvents.joinToString(separator = "，") { it.title }
                            val baseText = "新创建 ${addedNormalEvents.size} 条事项"
                            val alarmText = if (settings.autoCreateAlarm) "及闹钟" else ""
                            val filterText = if (duplicateCount > 0) " (已过滤 ${duplicateCount} 条重复)" else ""
                            val titleText = "$baseText$alarmText$filterText"
                            showNotification(titleText, titles, isProgress = false, autoLaunch = false)

                            // 立即检查是否需要启动实况胶囊 (已内部包含开关检查)
                            checkAndStartCapsuleImmediate(addedNormalEvents)

                        } else {
                            showNotification("无新增日程", "识别到 ${normalEvents.size} 条记录均为重复项", isProgress = false, autoLaunch = false)
                        }
                    }

                    // 2. 取件码实况通知
                    if (pickupEvents.isNotEmpty()) {
                        if (addedPickupEvents.isNotEmpty()) {
                            val count = addedPickupEvents.size
                            if (count == 1) {
                                val pickup = addedPickupEvents.first()
                                val chipText = pickup.description.trim()
                                val title = pickup.title
                                val location = if (pickup.location.isNotBlank()) pickup.location else "暂无位置信息"
                                val content = "位置: $location | 号码: $chipText"

                                cancelStatusNotification()
                                postLiveUpdateSafely(chipText, title, content)
                            } else {
                                val chipText = "待取${count}件"
                                val title = "当前有 $count 个待取件任务"
                                val sb = StringBuilder()
                                addedPickupEvents.forEachIndexed { index, event ->
                                    val locStr = if (event.location.isNotBlank()) " (${event.location})" else ""
                                    sb.append("• [${event.title}] ${event.description}$locStr")
                                    if (index < count - 1) sb.append("\n")
                                }
                                val finalContent = sb.toString()
                                cancelStatusNotification()
                                postLiveUpdateSafely(chipText, title, finalContent)
                            }
                        } else {
                            Log.d(TAG, "取件码已存在，跳过实时通知更新")
                            if (normalEvents.isEmpty()) {
                                showNotification("无新增内容", "相关取件码或日程已存在", isProgress = false)
                            }
                        }
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    showNotification("分析完成", "未识别到有效日程", isProgress = false)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("TextAccessDebug", "Error in processScreenshot", e)
            withContext(Dispatchers.Main) {
                showNotification("分析出错", "错误信息: ${e.message}")
            }
        }
    }

    /**
     * 检查并立即启动胶囊 (针对普通日程)
     */
    private fun checkAndStartCapsuleImmediate(events: List<MyEvent>) {
        val settings = MyApplication.getInstance().getSettings()
        // 【关键逻辑】：尊重用户意愿。如果开关关闭，坚决不启动胶囊。
        if (!settings.isLiveCapsuleEnabled) return

        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        events.forEach { event ->
            try {
                val startDateTime = LocalDateTime.parse("${event.startDate} ${event.startTime}", formatter)
                val endDateTime = LocalDateTime.parse("${event.endDate} ${event.endTime}", formatter)

                // 判定：如果 (现在 >= 开始) 且 (现在 < 结束)
                if ((now.isEqual(startDateTime) || now.isAfter(startDateTime)) && now.isBefore(endDateTime)) {
                    Log.d(TAG, "检测到即时日程: ${event.title}，立即启动胶囊")

                    val serviceIntent = Intent(this, CapsuleService::class.java).apply {
                        this.action = CapsuleService.ACTION_START
                        putExtra("EVENT_ID", event.id)
                        putExtra("EVENT_TITLE", event.title)
                        putExtra("EVENT_LOCATION", event.location)
                        putExtra("EVENT_START_TIME", event.startTime)
                        putExtra("EVENT_END_TIME", event.endTime)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析时间失败，跳过即时胶囊检查", e)
            }
        }
    }

    private fun saveEventsLocally(aiEvents: List<CalendarEventData>, imagePath: String): List<MyEvent> {
        val actuallyAdded = mutableListOf<MyEvent>()
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
                val newEventTitle = aiEvent.title.trim()
                val newEventDesc = aiEvent.description.trim()
                val newStartDate = startDateTime.toLocalDate()
                val newStartTime = startDateTime.format(DateTimeFormatter.ofPattern("HH:mm"))

                val isDuplicate = currentEvents.any { existing ->
                    if (finalEventType == "event") {
                        val sameDate = existing.startDate == newStartDate
                        val sameTime = existing.startTime == newStartTime
                        val sameTitle = existing.title.trim() == newEventTitle
                        val sameType = existing.eventType == "event"
                        sameDate && sameTime && sameTitle && sameType
                    } else {
                        val sameType = existing.eventType == "temp"
                        val sameTitle = existing.title.trim() == newEventTitle
                        val sameCode = existing.description.trim() == newEventDesc
                        sameType && sameTitle && sameCode
                    }
                }

                if (!isDuplicate) {
                    val newEvent = MyEvent(
                        id = UUID.randomUUID().toString(),
                        title = newEventTitle,
                        startDate = newStartDate,
                        endDate = endDateTime.toLocalDate(),
                        startTime = newStartTime,
                        endTime = endDateTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                        location = aiEvent.location,
                        description = newEventDesc,
                        color = getNextColor(currentEvents.size),
                        sourceImagePath = imagePath,
                        eventType = finalEventType
                    )
                    currentEvents.add(newEvent)
                    actuallyAdded.add(newEvent)

                    NotificationScheduler.scheduleReminders(this, newEvent)

                    if (shouldAutoAlarm && finalEventType == "event") {
                        createSystemAlarm(newEvent.title, startDateTime.hour, startDateTime.minute, startDateTime.toLocalDate())
                    }
                }
            }
            if (actuallyAdded.isNotEmpty()) {
                eventStore.saveEvents(currentEvents)
            }
        } catch (e: Exception) {
            Log.e(TAG, "后台保存失败", e)
        }
        return actuallyAdded
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
                    val calendarDay = if (dayOfWeek == 7) java.util.Calendar.SUNDAY else dayOfWeek + 1
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

    // =========================================================================
    // 【恢复】取件码实况通知核心逻辑
    // 以及辅助方法 (drawableToBitmap, tintBitmap 等)
    // =========================================================================

    private fun postLiveUpdateSafely(chipText: String, title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isMultiItem = title.contains("个待取")
        val capsuleColor = if (isMultiItem) {
            Color.parseColor("#00C853")
        } else if (isMealEvent(title)) {
            Color.parseColor("#FFD600")
        } else if (isPackageEvent(title)) {
            Color.parseColor("#2196F3")
        } else {
            Color.parseColor("#00C853")
        }

        // 【新增检查】：在发送取件码胶囊前，先检查用户是否开启了胶囊设置
        val settings = MyApplication.getInstance().getSettings()
        if (!settings.isLiveCapsuleEnabled) {
            // 如果用户关闭了胶囊，则降级为普通样式（带颜色、常驻）的通知
            // 避免反射调用实况 API
            postCompatSamsungNotification(manager, title, content, pendingIntent, capsuleColor)
            return
        }

        if (FlymeUtils.isLiveNotificationEnabled(this) || TEST_FLYME_LOGIC) {
            postFlymeLiveNotification(manager, chipText, title, content, pendingIntent, capsuleColor)
        }
        else if (Build.VERSION.SDK_INT >= 36) { // Android 16+
            if (!manager.canPostPromotedNotifications()) {
                sendPermissionGuidanceNotification()
                return
            }
            // 【关键修改】使用对齐过的增强版方法
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
            var iconDrawable = ContextCompat.getDrawable(this, R.drawable.ic_qs_recognition)
            if (iconDrawable == null) iconDrawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)

            val iconBitmap = iconDrawable?.let { drawableToBitmap(it) }?.let {
                tintBitmap(it, Color.WHITE)
            }
            val iconObj = if (iconBitmap != null) Icon.createWithBitmap(iconBitmap) else null
            val isLightBg = ColorUtils.calculateLuminance(color) > 0.5
            val contentTextColor = if (isLightBg) Color.BLACK else Color.WHITE

            val capsuleBundle = Bundle().apply {
                putInt("notification.live.capsuleStatus", 1)
                putInt("notification.live.capsuleType", 1)
                putString("notification.live.capsuleContent", chipText)

                if (iconObj != null) {
                    putParcelable("notification.live.capsuleIcon", iconObj)
                }
                putInt("notification.live.capsuleBgColor", color)
                putInt("notification.live.capsuleContentColor", contentTextColor)
            }

            val liveBundle = Bundle().apply {
                putBoolean("is_live", true)
                putInt("notification.live.operation", 0)
                putInt("notification.live.type", 10)
                putBundle("notification.live.capsule", capsuleBundle)
                putInt("notification.live.contentColor", contentTextColor)
            }

            val isListContent = content.contains("\n") || content.contains("•")
            val locationText = if (isListContent) {
                "下拉查看详情"
            } else {
                content.replace("位置: ", "").replace("| 号码: $chipText", "").trim()
            }
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
                .setStyle(Notification.BigTextStyle().bigText(content))
                .addExtras(liveBundle)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)

            manager.notify(NOTIFICATION_ID_LIVE, builder.build())
        } catch (e: Exception) {
            postCompatSamsungNotification(manager, title, content, pendingIntent, color)
        }
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
            // 【核心对齐】使用 mipmap 图标
            val icon = Icon.createWithResource(this, R.mipmap.ic_launcher)

            val builder = Notification.Builder(this, MyApplication.CHANNEL_ID_LIVE)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setColor(color)

                // 【核心对齐】移除 setColorized(true)
                // .setColorized(true) <--- 移除

                // 【核心对齐】设置 Category 和 Visibility
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)

            // 强制反射
            try {
                val methodSetText = Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java)
                methodSetText.invoke(builder, critText)
            } catch (e: Exception) { /* ignore */ }

            try {
                val methodSetPromoted = Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.java)
                methodSetPromoted.invoke(builder, true)
            } catch (e: Exception) { /* ignore */ }

            // 兼容性 Extra
            val extras = Bundle()
            extras.putBoolean("android.substName", true)
            extras.putString("android.title", critText) // 使用简短文本作为标题
            builder.addExtras(extras)

            builder.setOnlyAlertOnce(true)

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

    companion object {
        private const val TAG = "TextAccessibilityService"
        private const val ACTION_CANCEL_ANALYSIS = "ACTION_CANCEL_ANALYSIS"
        @Volatile var instance: TextAccessibilityService? = null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; serviceScope.cancel(); super.onDestroy() }
}