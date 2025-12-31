package com.antgskds.calendarassistant.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.R
import kotlin.time.Duration.Companion.milliseconds

class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        tile.state = Tile.STATE_INACTIVE
        tile.label = "识别事件"

        // 【修改此处】：引用刚才新建的、无边距的专用图标
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_recognition)

        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        val tile = qsTile
        if (tile != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }

        val service = TextAccessibilityService.instance

        if (service != null) {
            service.closeNotificationPanel()
            service.startAnalysis(500.milliseconds)

            if (tile != null) {
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        } else {
            sendEnableServiceNotification()

            if (tile != null) {
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    private fun sendEnableServiceNotification() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MyApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("服务未开启")
            .setContentText("点击此处前往设置开启“无障碍服务”以使用识别功能")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2001, notification)
    }
}