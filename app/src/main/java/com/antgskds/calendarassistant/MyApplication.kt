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
import io.ktor.client.plugins.HttpTimeout // 【新增导入】
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class MyApplication : Application() {

    private lateinit var appSettings: MySettings

    fun getSettings(): MySettings = appSettings

    val ktorClient by lazy {
        HttpClient(OkHttp) {
            // 【关键修改】使用 Ktor 标准的超时插件，确保设置生效
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000 // 请求超时：2分钟
                connectTimeoutMillis = 120_000 // 连接超时：2分钟
                socketTimeoutMillis = 120_000  // Socket超时：2分钟
            }

            // 依然保留 Engine 配置作为双重保险
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
            val name = "日程助手重要通知"
            val descriptionText = "显示日程创建结果"
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "calendar_assistant_popup_channel_v2"

        @Volatile
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: throw IllegalStateException("Application not yet initialized")
        }
    }
}