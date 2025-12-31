package com.antgskds.calendarassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.antgskds.calendarassistant.model.MySettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class MyApplication : Application() {

    private lateinit var appSettings: MySettings

    fun getSettings(): MySettings = appSettings

    val ktorClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }

            engine {
                config {
                    connectTimeout(120, TimeUnit.SECONDS)
                    readTimeout(120, TimeUnit.SECONDS)
                    writeTimeout(120, TimeUnit.SECONDS)
                }
            }

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
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. 普通通知渠道
            val name = "日程助手重要通知"
            val descriptionText = "显示日程创建结果"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)

            // 2. 实时通知渠道
            val liveName = "实时状态通知"
            val liveDesc = "显示取件码、排队号等实时信息"
            // 使用 IMPORTANCE_DEFAULT 或 HIGH 即可，promoted 会处理胶囊
            val liveChannel = NotificationChannel(CHANNEL_ID_LIVE, liveName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = liveDesc
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(liveChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "calendar_assistant_popup_channel_v2"
        const val CHANNEL_ID_LIVE = "calendar_assistant_live_channel"

        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: throw IllegalStateException("Application not yet initialized")
        }
    }
}