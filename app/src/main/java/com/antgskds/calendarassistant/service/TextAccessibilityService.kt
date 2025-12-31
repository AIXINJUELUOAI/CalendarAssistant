package com.antgskds.calendarassistant.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
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

                if (bitmap == null) return@launch

                val imagesDir = File(filesDir, "event_screenshots")
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imageFile = File(imagesDir, "IMG_$timestamp.jpg")

                // 必须转换位图格式供 ML Kit 使用
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

                // 只要标题不为空就认为是有效日程
                val validEvents = eventsList.filter { it.title.isNotBlank() }

                if (validEvents.isNotEmpty()) {
                    saveEventsLocally(validEvents, imageFile.absolutePath)

                    withContext(Dispatchers.Main) {
                        val titles = validEvents.joinToString(separator = "，") { it.title }
                        showNotification("成功创建 ${validEvents.size} 条事项", titles, isProgress = false, autoLaunch = false)
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

                // 关键逻辑：根据 AI 返回的 type 决定 eventType
                // 如果 type 是 pickup，则归类为 temp (临时事件)，否则为 event (日程)
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
                    eventType = finalEventType // 新增字段赋值
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

        if (autoLaunch) {
            startActivity(intent)
        }
    }

    companion object {
        private const val TAG = "TextAccessibilityService"
        @Volatile var instance: TextAccessibilityService? = null
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; serviceScope.cancel(); super.onDestroy() }
}