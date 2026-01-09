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

    var autoCreateAlarm: Boolean
        get() = prefs.getBoolean("auto_create_alarm", false)
        set(value) = prefs.edit().putBoolean("auto_create_alarm", value).apply()

    var showTomorrowEvents: Boolean
        get() = prefs.getBoolean("show_tomorrow_events", false)
        set(value) = prefs.edit().putBoolean("show_tomorrow_events", value).apply()

    var isDailySummaryEnabled: Boolean
        get() = prefs.getBoolean("daily_summary_enabled", false)
        set(value) = prefs.edit().putBoolean("daily_summary_enabled", value).apply()

    // 【新增/确认】临时事件时间基准
    var tempEventsUseRecognitionTime: Boolean
        get() = prefs.getBoolean("temp_events_use_rec_time", true)
        set(value) = prefs.edit().putBoolean("temp_events_use_rec_time", value).apply()

    // 【新增】截图延迟 (毫秒)
    var screenshotDelayMs: Long
        get() = prefs.getLong("screenshot_delay_ms", 500L)
        set(value) = prefs.edit().putLong("screenshot_delay_ms", value).apply()

    // 实况胶囊开关
    var isLiveCapsuleEnabled: Boolean
        get() = prefs.getBoolean("live_capsule_enabled", false)
        set(value) = prefs.edit().putBoolean("live_capsule_enabled", value).apply()

    // --- 课表设置 ---
    var semesterStartDate: String // yyyy-MM-dd
        get() = prefs.getString("semester_start_date", "") ?: ""
        set(value) = prefs.edit().putString("semester_start_date", value).apply()

    var totalWeeks: Int
        get() = prefs.getInt("semester_total_weeks", 20)
        set(value) = prefs.edit().putInt("semester_total_weeks", value).apply()

    var timeTableJson: String
        get() = prefs.getString("time_table_json", "") ?: ""
        set(value) = prefs.edit().putString("time_table_json", value).apply()
}