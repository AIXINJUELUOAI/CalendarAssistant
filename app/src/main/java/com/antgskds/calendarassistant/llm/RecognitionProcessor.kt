package com.antgskds.calendarassistant.llm

import android.content.Context
import android.net.Uri
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
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RecognitionProcessor {
    private const val TAG = "RecognitionProcessor"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // 修改返回值：从单个对象变为 List
    suspend fun analyzeImage(imageFile: File): List<CalendarEventData> {
        val context = MyApplication.getInstance()

        Log.d(TAG, "开始本地 OCR 识别: ${imageFile.name}")
        val extractedText = try {
            extractTextFromImage(context, imageFile)
        } catch (e: Exception) {
            Log.e(TAG, "OCR 识别失败", e)
            return emptyList()
        }

        if (extractedText.isBlank()) {
            Log.w(TAG, "OCR 未识别到任何文字")
            return emptyList()
        }

        Log.d(TAG, "OCR 结果 (前200字): ${extractedText.take(200).replace("\n", " ")}...")

        val now = LocalDateTime.now()
        val timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE"))
        val settings = context.getSettings()

        // 定义单个对象的结构
        val itemSchema = JSONObject().apply {
            put("hasEvent", "布尔值，是否包含日程信息")
            put("title", "日程标题")
            put("startTime", "开始时间(yyyy-MM-dd HH:mm)，结合参考时间($timeStr)推断")
            put("endTime", "结束时间(yyyy-MM-dd HH:mm)")
            put("location", "地点")
            put("description", "备注")
        }

        // 修改 Prompt：明确要求返回 JSON 数组
        val systemPrompt = """
            你是一个日程管理助手。当前参考时间是：$timeStr
            任务：从 OCR 文本中提取所有可能的日程。
            
            要求：
            1. 必须输出一个 JSON 数组（Array），其中包含多个对象。
            2. 即使只有一个日程，也要包裹在数组中: [...]
            3. 单个日程对象的结构如下：$itemSchema
            4. 将相对时间转换为标准格式 yyyy-MM-dd HH:mm。
            5. 过滤掉无意义的 UI 文字。
            6. 仅输出 JSON。
        """.trimIndent()

        val userPrompt = """
            [OCR文本]
            $extractedText
        """.trimIndent()

        return try {
            val modelName = settings.modelName.ifBlank { "deepseek-chat" }

            val modelRequest = ModelRequest(
                model = modelName,
                temperature = 0.1,
                responseFormat = ModelRequest.ResponseFormat("json_object"),
                messages = listOf(
                    ModelMessage("system", systemPrompt),
                    ModelMessage("user", userPrompt)
                )
            )

            Log.d(TAG, "正在请求模型: $modelName")

            val responseText = ApiModelProvider.generate(modelRequest)
            Log.d(TAG, "LLM Response: $responseText")

            // 尝试解析
            try {
                // 情况A: AI 听话，返回了纯数组 [...]
                jsonParser.decodeFromString<List<CalendarEventData>>(responseText)
            } catch (e: Exception) {
                // 情况B: AI 可能包裹了一层 key，比如 {"events": [...]}，这是 json_object 模式的常见行为
                // 这里做一个简单的容错处理
                if (responseText.trim().startsWith("{")) {
                    val jsonObject = JSONObject(responseText)
                    // 尝试寻找可能的数组字段
                    val keys = jsonObject.keys()
                    var foundList = emptyList<CalendarEventData>()
                    while(keys.hasNext()) {
                        val key = keys.next()
                        val optArray = jsonObject.optJSONArray(key)
                        if (optArray != null) {
                            foundList = jsonParser.decodeFromString<List<CalendarEventData>>(optArray.toString())
                            break
                        }
                    }
                    foundList
                } else {
                    emptyList()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "AI 分析失败", e)
            emptyList()
        }
    }

    private suspend fun extractTextFromImage(context: Context, file: File): String = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it.text) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}