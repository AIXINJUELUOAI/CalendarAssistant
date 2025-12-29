package com.antgskds.calendarassistant.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R // 确保这里引用正确，如果没有R文件，系统会自动生成，或者检查包名
import kotlin.time.Duration.Companion.milliseconds

class CaptureTileService : TileService() {
    override fun onClick() {
        super.onClick()

        val service = TextAccessibilityService.instance

        if (service != null) {
            // 服务正常，执行逻辑
            service.closeNotificationPanel()
            service.startAnalysis(500.milliseconds)
        } else {
            // 服务未启动，发送通知替代 Toast
            sendEnableServiceNotification()
        }
    }

    private fun sendEnableServiceNotification() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 确保你的 drawable 里有这个图标，或者是 mipmap
            .setContentTitle("服务未开启")
            .setContentText("点击此处前往设置开启“无障碍服务”以使用识别功能")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent) // 点击跳转
            .setAutoCancel(true) // 点击后自动消失
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2001, notification)
    }
}