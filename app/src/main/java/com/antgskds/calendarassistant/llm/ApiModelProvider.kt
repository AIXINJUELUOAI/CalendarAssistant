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
import kotlinx.serialization.json.Json

object ApiModelProvider {

    suspend fun generate(request: ModelRequest): String {
        return try {
            val app = MyApplication.getInstance()
            val settings = app.getSettings()

            val apiKey = settings.modelKey
            val url = settings.modelUrl
            val modelName = settings.modelName

            if (url.isBlank() || apiKey.isBlank()) {
                Log.e("ApiModelProvider", "API URL or Key not configured")
                return "Error: 配置缺失"
            }

            Log.d("ApiModelProvider", "Requesting: $url (Model: $modelName)")

            val response = app.ktorClient.post {
                url(url)
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                setBody(request.copy(model = modelName))
            }

            val rawBody = response.bodyAsText()
            Log.d("DEBUG_HTTP", "服务器原始响应: $rawBody")

            if (!response.status.isSuccess()) {
                Log.e("ApiModelProvider", "Request failed: ${response.status} - $rawBody")
                return "Error: HTTP ${response.status}"
            }

            val json = Json { ignoreUnknownKeys = true }
            val modelResponse = json.decodeFromString<ModelResponse>(rawBody)

            modelResponse.choices.firstOrNull()?.message?.content ?: "Error: Empty Content"
        } catch (e: Exception) {
            Log.e("ApiModelProvider", "Network/Parse error", e)
            "Error: ${e.javaClass.simpleName} - ${e.message}"
        }
    }
}