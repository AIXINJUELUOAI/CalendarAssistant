package com.antgskds.calendarassistant.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * 魅族 Flyme 实况通知适配工具类
 */
object FlymeUtils {
    private const val TAG = "FlymeUtils"

    /**
     * 检测是否为魅族 Flyme 系统
     */
    fun isFlyme(): Boolean {
        val displayId = Build.DISPLAY
        val manufacturer = Build.MANUFACTURER
        return manufacturer.contains("Meizu", ignoreCase = true) ||
                displayId.contains("Flyme", ignoreCase = true)
    }

    /**
     * 获取 Flyme 版本号
     */
    fun getFlymeVersion(): Int {
        val display = Build.DISPLAY ?: return -1
        val match = Regex("Flyme\\s*([0-9]+)").find(display)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    /**
     * 检测实况通知是否可用 (系统开关 + 权限)
     * 必须在 AndroidManifest 中声明 flyme.permission.READ_NOTIFICATION_LIVE_STATE
     */
    fun isLiveNotificationEnabled(context: Context): Boolean {
        // 1. 基础环境判断
        if (!isFlyme()) return false

        // 2. 权限判断
        if (context.checkSelfPermission("flyme.permission.READ_NOTIFICATION_LIVE_STATE") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "缺少权限: flyme.permission.READ_NOTIFICATION_LIVE_STATE")
            return false
        }

        // 3. 通过 ContentProvider 查询系统开关状态
        return try {
            val uri = Uri.parse("content://com.android.systemui.notification.provider")
            val call: Bundle? = context.contentResolver.call(
                uri,
                "isNotificationLiveEnabled",
                null,
                null
            )
            call?.getBoolean("result", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "检测实况开关失败，默认为开启以防误判", e)
            true
        }
    }
}