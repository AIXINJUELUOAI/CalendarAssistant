package com.antgskds.calendarassistant.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antgskds.calendarassistant.MainActivity
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R
import com.antgskds.calendarassistant.service.CapsuleService
import com.antgskds.calendarassistant.service.TextAccessibilityService
import com.antgskds.calendarassistant.util.NotificationScheduler

/**
 * 广播接收器：AlarmReceiver
 *
 * 职责：接收 AlarmManager 的定时广播，并分流处理：
 * 1. 普通提醒 -> 直接发送 Notification
 * 2. 胶囊开始 -> 启动 [CapsuleService] (ACTION_START)
 * 3. 胶囊结束 -> 启动 [CapsuleService] (ACTION_STOP)
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val eventId = intent.getStringExtra("EVENT_ID") ?: return
        val eventTitle = intent.getStringExtra("EVENT_TITLE") ?: "日程提醒"

        when (action) {
            NotificationScheduler.ACTION_CAPSULE_START -> {
                handleCapsuleStart(context, intent, eventId, eventTitle)
            }
            NotificationScheduler.ACTION_CAPSULE_END -> {
                handleCapsuleEnd(context, eventId)
            }
            else -> {
                val reminderLabel = intent.getStringExtra("REMINDER_LABEL") ?: ""
                showStandardNotification(context, eventId, eventTitle, reminderLabel)
            }
        }
    }

    private fun handleCapsuleStart(context: Context, intent: Intent, eventId: String, title: String) {
        val settings = MyApplication.getInstance().getSettings()
        // 检查无障碍服务是否运行 (作为系统能力锁)
        // 检查用户开关 (作为意愿锁)
        val isServiceRunning = TextAccessibilityService.instance != null
        val isEnabled = settings.isLiveCapsuleEnabled

        if (isEnabled && isServiceRunning) {
            Log.d("AlarmReceiver", "启动胶囊服务: $title")

            // 1. 视觉层：启动胶囊 UI
            val serviceIntent = Intent(context, CapsuleService::class.java).apply {
                this.action = CapsuleService.ACTION_START
                putExtras(intent) // 转发所有参数
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // 2. 听觉层：逻辑补位 ("替身"逻辑)
            // 场景：实况胶囊本身是静音的。
            // 如果用户没有开启"自动创建系统闹钟"，他们可能会错过视觉上的胶囊。
            // 此时，如果用户授予了通知权限，我们手动播放一次提示音作为补充。
            val hasSystemAlarm = settings.autoCreateAlarm
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val hasPermission = notificationManager.areNotificationsEnabled()

            if (!hasSystemAlarm) {
                if (hasPermission) {
                    Log.i("AlarmReceiver", "胶囊已启动，无系统闹钟，且有权限 -> 播放'替身'提示音")
                    playAlert(context)
                } else {
                    Log.i("AlarmReceiver", "胶囊已启动，无系统闹钟，但无通知权限 -> 保持静默")
                }
            } else {
                Log.i("AlarmReceiver", "胶囊已启动，有系统闹钟 -> 保持静默，等待闹钟响起")
            }

        } else {
            // 【降级逻辑】
            // 如果条件不满足（开关关闭 或 无障碍未开启），则发送普通通知。
            // 普通通知自带 DEFAULT_ALL (声音+震动)，所以这里不需要额外调用 playAlert
            Log.d("AlarmReceiver", "跳过实况胶囊 (开关:$isEnabled, OCR服务:$isServiceRunning) -> 降级为普通通知")
            showStandardNotification(context, eventId, title, "日程开始")
        }
    }

    private fun handleCapsuleEnd(context: Context, eventId: String) {
        // 即使服务可能已经停止，发送 STOP Intent 也是安全的。
        val serviceIntent = Intent(context, CapsuleService::class.java).apply {
            this.action = CapsuleService.ACTION_STOP
            putExtra("EVENT_ID", eventId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun showStandardNotification(context: Context, eventId: String, title: String, label: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventId.hashCode(), tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(if(label.isNotEmpty()) "$label: $title" else "日程即将开始")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // 这里自带了声音和震动
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(eventId.hashCode(), notification)
    }

    /**
     * 播放默认通知音并震动
     * 仅在胶囊模式且无系统闹钟时调用
     */
    private fun playAlert(context: Context) {
        try {
            // 1. 播放系统默认通知音
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()

            // 2. 震动 (两短震动，提醒注意)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                val timing = longArrayOf(0, 200, 100, 200) // 等待0ms, 震200ms, 停100ms, 震200ms
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // -1 表示不重复
                    vibrator.vibrate(VibrationEffect.createWaveform(timing, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timing, -1)
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "播放替身提示音失败", e)
        }
    }
}