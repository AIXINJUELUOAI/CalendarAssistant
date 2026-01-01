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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject

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

            // --- Gemini 原生支持分支 ---
            // 如果用户输入的 URL 包含 googleapis 或 gemini，则走这个分支
            if (url.contains("googleapis") || url.contains("gemini")) {
                return generateGemini(app.ktorClient, url, apiKey, request)
            }

            // --- 标准 OpenAI 格式分支 ---
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

    private suspend fun generateGemini(client: io.ktor.client.HttpClient, baseUrl: String, apiKey: String, request: ModelRequest): String {
        // Gemini 将 API Key 放在 Query 参数中，而不是 Header
        val finalUrl = if (baseUrl.contains("?")) "$baseUrl&key=$apiKey" else "$baseUrl?key=$apiKey"

        // 构造 Gemini 特有的 JSON 结构
        // Gemini 的 JSON 结构是 { "contents": [ { "parts": [ { "text": "..." } ] } ] }
        // 且它对 System Prompt 的支持方式与 OpenAI 不同，这里简化处理，将 system 和 user 消息拼接为纯文本
        val fullPrompt = request.messages.joinToString("\n\n") { msg ->
            "【${msg.role}】: ${msg.content}"
        }

        val geminiJson = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", fullPrompt)
                        })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", request.temperature)
            }
        }

        val response = client.post {
            url(finalUrl)
            contentType(ContentType.Application.Json)
            setBody(geminiJson)
        }

        val rawBody = response.bodyAsText()
        Log.d("DEBUG_HTTP_GEMINI", "Gemini 响应: $rawBody")

        if (!response.status.isSuccess()) {
            return "Error: Gemini HTTP ${response.status}"
        }

        // 手动解析 Gemini 的响应结构
        // { "candidates": [ { "content": { "parts": [ { "text": "结果..." } ] } } ] }
        return try {
            val root = JSONObject(rawBody)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    parts.getJSONObject(0).optString("text", "")
                } else {
                    "Error: Empty Parts"
                }
            } else {
                "Error: No Candidates"
            }
        } catch (e: Exception) {
            "Error: Parse Gemini Failed"
        }
    }
}