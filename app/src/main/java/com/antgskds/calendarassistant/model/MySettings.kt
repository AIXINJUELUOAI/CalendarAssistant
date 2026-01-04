package com.antgskds.calendarassistant.model

import android.content.Context
import android.content.SharedPreferences

class MySettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var modelKey: String
        get() = prefs.getString("model_key", "") ?: ""
        set(value) = prefs.edit().putString("model_key", value).apply()

    var modelName: String
        get() = prefs.getString("model_name", "") ?: ""
        set(value) = prefs.edit().putString("model_name", value).apply()

    var modelUrl: String
        get() = prefs.getString("model_url", "") ?: ""
        set(value) = prefs.edit().putString("model_url", value).apply()

    var modelProvider: String
        get() = prefs.getString("model_provider", "") ?: ""
        set(value) = prefs.edit().putString("model_provider", value).apply()

    // --- 配置 ---

    // 是否在创建日程时自动设置系统闹钟
    var autoCreateAlarm: Boolean
        get() = prefs.getBoolean("auto_create_alarm", false)
        set(value) = prefs.edit().putBoolean("auto_create_alarm", value).apply()

    // 主页是否显示明日日程
    var showTomorrowEvents: Boolean
        get() = prefs.getBoolean("show_tomorrow_events", false)
        set(value) = prefs.edit().putBoolean("show_tomorrow_events", value).apply()

    // 每日日程汇总开关
    var isDailySummaryEnabled: Boolean
        get() = prefs.getBoolean("daily_summary_enabled", false)
        set(value) = prefs.edit().putBoolean("daily_summary_enabled", value).apply()

    // 临时事件(取件码)的时间基准
    var tempEventsUseRecognitionTime: Boolean
        get() = prefs.getBoolean("temp_events_use_rec_time", true)
        set(value) = prefs.edit().putBoolean("temp_events_use_rec_time", value).apply()

    // --- 【新增】: 实况胶囊通知开关 (Beta) ---
    // 只有当此开关开启 && 无障碍服务运行时，才会在日程开始时发送状态栏胶囊。
    var isLiveCapsuleEnabled: Boolean
        get() = prefs.getBoolean("live_capsule_enabled", false)
        set(value) = prefs.edit().putBoolean("live_capsule_enabled", value).apply()
}