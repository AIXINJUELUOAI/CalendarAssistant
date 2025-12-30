package com.antgskds.calendarassistant.llm

import android.graphics.Bitmap
import android.util.Log
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.model.CalendarEventData
import com.antgskds.calendarassistant.model.ModelMessage
import com.antgskds.calendarassistant.model.ModelRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RecognitionProcessor {
    private const val TAG = "CALENDAR_OCR_DEBUG"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun analyzeImage(bitmap: Bitmap): List<CalendarEventData> {
        val context = MyApplication.getInstance()

        Log.i(TAG, ">>> 开始处理图片 (尺寸: ${bitmap.width} x ${bitmap.height})")

        val extractedText = try {
            extractTextFromBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "OCR 过程发生异常", e)
            return emptyList()
        }

        if (extractedText.isBlank()) {
            Log.w(TAG, "OCR 结果为空！")
            return emptyList()
        } else {
            Log.d(TAG, "OCR 排序后文本内容 (发送给AI):\n$extractedText")
        }

        val now = LocalDateTime.now()
        val dtfFull = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE")
        val dtfDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val timeStr = now.format(dtfFull)
        val dateToday = now.format(dtfDate)
        val dateYesterday = now.minusDays(1).format(dtfDate)
        val dateBeforeYesterday = now.minusDays(2).format(dtfDate)
        val dateTomorrow = now.plusDays(1).format(dtfDate)

        val settings = context.getSettings()

        val itemSchema = JSONObject().apply {
            put("title", "日程标题")
            put("startTime", "格式 yyyy-MM-dd HH:mm")
            put("endTime", "格式 yyyy-MM-dd HH:mm")
            put("location", "地点")
            put("description", "备注")
        }

        val systemPrompt = """
            你是一个日程计算助手。
            【当前系统时间】：$timeStr
            
            任务：根据OCR文本提取日程。
            
            【核心规则：时间相对性】
            1. **确定基准**：在内容上方寻找最近的时间戳。
               - "昨天" -> 基准日是 $dateYesterday
               - "前天" -> 基准日是 $dateBeforeYesterday
               - "今天" -> 基准日是 $dateToday
            
            2. **计算偏移**：
               - **重要禁忌**：聊天记录中的“今天”指的是【基准日】，**绝不是**当前系统时间！
               - 内容说 "今天晚上" = 基准日 (不是系统时间!)
               - 内容说 "明晚" = 基准日 + 1天
               - 内容说 "后天" = 基准日 + 2天
            
            【输出格式】
            纯 JSON 对象：
            {
              "reasoning": "必须写出：基准是哪天？内容偏移几天？最终日期是？",
              "events": [ $itemSchema ]
            }
        """.trimIndent()

        val userPrompt = """
            [OCR文本开始]
            $extractedText
            [OCR文本结束]
        """.trimIndent()

        return try {
            val modelName = settings.modelName.ifBlank { "deepseek-chat" }

            val modelRequest = ModelRequest(
                model = modelName,
                temperature = 0.1,
                responseFormat = null,
                messages = listOf(
                    ModelMessage("system", systemPrompt),
                    ModelMessage("user", userPrompt)
                )
            )

            Log.d(TAG, "正在请求模型: $modelName")

            val responseText = ApiModelProvider.generate(modelRequest)
            Log.d(TAG, "AI 原始响应: $responseText")

            if (responseText.startsWith("Error:")) {
                Log.e(TAG, "API 请求失败: $responseText")
                return emptyList()
            }

            try {
                var cleanJson = responseText.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.substringAfter("json").substringAfter("\n").substringBeforeLast("```")
                }

                val rootObject = JSONObject(cleanJson)

                if (rootObject.has("reasoning")) {
                    Log.e(TAG, "🤖 AI 推理过程: ${rootObject.getString("reasoning")}")
                }

                val eventsArray = rootObject.optJSONArray("events") ?: JSONArray()

                if (eventsArray.length() > 0) {
                    jsonParser.decodeFromString<List<CalendarEventData>>(eventsArray.toString())
                } else {
                    Log.w(TAG, "AI 返回了空事件列表")
                    emptyList()
                }

            } catch (e: Exception) {
                Log.e(TAG, "JSON 解析失败", e)
                if (responseText.contains("[")) {
                    val arrayStr = "[" + responseText.substringAfter("[").substringBeforeLast("]") + "]"
                    try {
                        jsonParser.decodeFromString<List<CalendarEventData>>(arrayStr)
                    } catch (e2: Exception) { emptyList() }
                } else {
                    emptyList()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "AI 分析严重错误", e)
            emptyList()
        }
    }

    private suspend fun extractTextFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val allLines = visionText.textBlocks.flatMap { it.lines }
                    val sortedLines = allLines.sortedBy { it.boundingBox?.top ?: 0 }
                    val resultText = sortedLines.joinToString("\n") { it.text }
                    continuation.resume(resultText)
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}