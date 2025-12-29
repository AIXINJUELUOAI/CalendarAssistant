package com.antgskds.calendarassistant

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class EventJsonStore(private val context: Context) {
    private val filename = "events.json"

    // 将 JSON 配置暴露出来，或者提供转换方法
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // 保存到内部存储（原有功能）
    fun saveEvents(events: List<MyEvent>) {
        try {
            val jsonString = json.encodeToString(events)
            context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                it.write(jsonString.toByteArray())
            }
            Log.d("EventJsonStore", "Saved ${events.size} events.")
        } catch (e: Exception) {
            Log.e("EventJsonStore", "Failed to save", e)
        }
    }

    // 从内部存储读取（原有功能）
    fun loadEvents(): MutableList<MyEvent> {
        val file = File(context.filesDir, filename)
        if (!file.exists()) return mutableListOf()

        return try {
            val jsonString = file.readText()
            json.decodeFromString<MutableList<MyEvent>>(jsonString)
        } catch (e: Exception) {
            Log.e("EventJsonStore", "Failed to load", e)
            mutableListOf()
        }
    }

    // --- 新增：供导出使用 ---
    fun eventsToJsonString(events: List<MyEvent>): String {
        return json.encodeToString(events)
    }

    // --- 新增：供导入使用 ---
    fun jsonStringToEvents(jsonString: String): List<MyEvent> {
        return try {
            json.decodeFromString<List<MyEvent>>(jsonString)
        } catch (e: Exception) {
            Log.e("EventJsonStore", "Parse import failed", e)
            emptyList()
        }
    }
}