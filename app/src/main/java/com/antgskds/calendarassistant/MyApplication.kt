package com.antgskds.calendarassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.antgskds.calendarassistant.model.MySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MyApplication : Application() {

    private lateinit var appSettings: MySettings

    fun getSettings(): MySettings = appSettings

    val ktorClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                })
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appSettings = MySettings(this)

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "日程助手重要通知"
            val descriptionText = "显示日程创建结果"

            // 【关键修改】改为 HIGH，只有 HIGH 才会从屏幕顶部弹出
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true) // 开启震动有助于触发弹出
                enableLights(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        // 【关键修改】修改 ID 字符串，强制系统重新注册渠道设置
        // 如果不改 ID，旧的“默认重要性”设置会一直保留，导致无法弹出
        const val CHANNEL_ID = "calendar_assistant_popup_channel_v2"

        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: throw IllegalStateException("Application not yet initialized")
        }
    }
}