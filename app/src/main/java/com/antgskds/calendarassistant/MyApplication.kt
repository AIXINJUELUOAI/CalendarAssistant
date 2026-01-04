package com.antgskds.calendarassistant

import android.app.Application
import android.app.Notification
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

            // 2. 实时通知渠道 (实况胶囊专用)
            // 【修改】升级版本号 v3，防止系统缓存旧的配置
            val liveName = "实时状态通知"
            val liveDesc = "显示取件码、日程实况等信息"

            // 必须 HIGH
            val liveChannel = NotificationChannel(CHANNEL_ID_LIVE, liveName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = liveDesc
                // 【欺骗系统】：开启震动但时长为0，防止被降级
                enableVibration(true)
                vibrationPattern = longArrayOf(0L)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(liveChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "calendar_assistant_popup_channel_v2"
        // 【修改】强制刷新渠道配置
        const val CHANNEL_ID_LIVE = "calendar_assistant_live_channel_v3"

        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: throw IllegalStateException("Application not yet initialized")
        }
    }
}