package com.antgskds.calendarassistant.model

import android.content.Context
import android.content.SharedPreferences

class MySettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var modelKey: String
        get() = prefs.getString("model_key", "") ?: ""
        set(value) = prefs.edit().putString("model_key", value).apply()

    // 修改：默认为空，不指定任何模型
    var modelName: String
        get() = prefs.getString("model_name", "") ?: ""
        set(value) = prefs.edit().putString("model_name", value).apply()

    // 保留 Provider 标识以便未来扩展（目前逻辑主要依赖 URL，这个字段暂时影响不大）
    var modelProvider: String
        get() = prefs.getString("model_provider", "") ?: ""
        set(value) = prefs.edit().putString("model_provider", value).apply()

    // 修改：默认为空，不指定任何 API 地址
    var modelUrl: String
        get() = prefs.getString("model_url", "") ?: ""
        set(value) = prefs.edit().putString("model_url", value).apply()
}