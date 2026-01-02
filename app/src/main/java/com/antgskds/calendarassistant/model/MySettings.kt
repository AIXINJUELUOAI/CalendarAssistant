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

    // 保留 Provider 标识以便未来扩展
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

    // [新增] 是否开启伪装胶囊通知 (CallStyle)
    var enableFakeCallStyle: Boolean
        get() = prefs.getBoolean("enable_fake_call_style", false)
        set(value) = prefs.edit().putBoolean("enable_fake_call_style", value).apply()
}