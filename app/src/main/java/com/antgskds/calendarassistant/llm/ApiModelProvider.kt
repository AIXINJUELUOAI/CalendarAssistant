package com.antgskds.calendarassistant.llm

import android.util.Log
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.model.ModelRequest
import com.antgskds.calendarassistant.model.ModelResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

object ApiModelProvider {

    suspend fun generate(request: ModelRequest): String {
        return try {
            val app = MyApplication.getInstance()
            val settings = app.getSettings()

            // 1. 直接读取用户设置的 URL、Key 和模型名称
            val apiKey = settings.modelKey
            val url = settings.modelUrl
            val modelName = settings.modelName

            if (url.isBlank() || apiKey.isBlank()) {
                Log.e("ApiModelProvider", "API URL or Key not configured")
                return "{}"
            }

            Log.d("ApiModelProvider", "Requesting: $url with model: $modelName")

            val response = app.ktorClient.post {
                url(url)
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                // 2. 确保请求体中使用用户设置的模型名称 (如 deepseek-chat)
                setBody(request.copy(model = modelName))
            }

            if (!response.status.isSuccess()) {
                val errorMsg = response.bodyAsText()
                Log.e("ApiModelProvider", "Request failed: $errorMsg")
                return "{}"
            }

            val modelResponse: ModelResponse = response.body()
            modelResponse.choices.firstOrNull()?.message?.content ?: "{}"
        } catch (e: Exception) {
            Log.e("ApiModelProvider", "Network error", e)
            "{}"
        }
    }
}