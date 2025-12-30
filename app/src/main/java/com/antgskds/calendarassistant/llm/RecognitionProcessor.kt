package com.antgskds.calendarassistant.llm

import android.graphics.Bitmap
import android.graphics.Rect
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

        // 1. 提取并【按坐标排序】文字
        val extractedText = try {
            extractTextFromBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "OCR 过程发生异常", e)
            return emptyList()
        }

        // 打印日志，确认排序是否正确 (已去除边框)
        if (extractedText.isBlank()) {
            Log.w(TAG, "OCR 结果为空！")
        } else {
            Log.d(TAG, "OCR 排序后文本内容:")
            Log.d(TAG, extractedText)
        }

        if (extractedText.isBlank()) {
            return emptyList()
        }

        // 2. 准备时间数据，直接算好给 AI，减少 AI 计算压力
        val now = LocalDateTime.now()
        val dtfFull = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE")
        val dtfDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val timeStr = now.format(dtfFull)
        val dateToday = now.format(dtfDate)
        val dateYesterday = now.minusDays(1).format(dtfDate)
        val dateTwoDaysAgo = now.minusDays(2).format(dtfDate)

        val settings = context.getSettings()

        val itemSchema = JSONObject().apply {
            put("hasEvent", "布尔值")
            put("title", "日程标题")
            put("startTime", "格式 yyyy-MM-dd HH:mm (必须是绝对时间)")
            put("endTime", "格式 yyyy-MM-dd HH:mm")
            put("location", "地点")
            put("description", "原文内容或备注")
        }

        // 3. 构建核心 Prompt (双层时间逻辑)
        val systemPrompt = """
            你是一个由 OCR 驱动的日程管理助手。
            【当前系统时间】：$timeStr
            
            你的核心任务是解决聊天记录中的“双层时间相对性”问题，请严格按照以下步骤思考：
            
            ---
            
            ### 第一步：确立消息基准时间 (Message Base Time)
            OCR 文本已按视觉顺序从上到下排列。
            1. **寻找时间戳**：在每一行日程内容的**正上方**或**附近**寻找最近的时间标记。
            2. **解析时间戳**：将 OCR 识别到的相对时间标记转换为绝对日期。
               - 识别到 "昨天..." -> 基准日期为 $dateYesterday
               - 识别到 "前天..." -> 基准日期为 $dateTwoDaysAgo
               - 识别到 "今天..." -> 基准日期为 $dateToday
               - **继承规则**：如果某条消息上方没有直接时间戳，它继承**再上一条**消息的时间戳。
            
            ---
            
            ### 第二步：计算日程实际时间 (Event Target Time)
            **切记：** 消息内容里的“今天”、“明天”是相对于【第一步得到的消息基准时间】的，而不是相对于【当前系统时间】。
            
            公式：[日程日期] = [消息基准时间] + [内容中的相对描述]
            
            **典型案例分析（假设消息基准时间是 12月29日）：**
            *   [OCR内容]: "明天去钓鱼"
                -> 计算: 12月29日 + 1天 = **12月30日** (正确)
                -> 错误: 不要直接用系统时间的明天。
            *   [OCR内容]: "今天晚上吃饭"
                -> 计算: 12月29日 + 0天 = **12月29日** (正确)
            
            ---
            
            ### 第三步：提取与输出
            忽略聊天中的闲聊，只提取明确的日程。
            
            输出格式为 JSON 数组 [...]，对象结构：$itemSchema
            1. 如果无法解析出有效日程，hasEvent 设为 false。
            2. startTime 和 endTime 必须转换为 yyyy-MM-dd HH:mm 格式。
            3. 如果没有明确结束时间，默认持续1小时。
        """.trimIndent()

        val userPrompt = """
            [OCR文本数据开始]
            $extractedText
            [OCR文本数据结束]
            
            请根据 System Prompt 中的【双层时间逻辑】提取上述数据中的日程。
        """.trimIndent()

        return try {
            val modelName = settings.modelName.ifBlank { "deepseek-chat" }

            val modelRequest = ModelRequest(
                model = modelName,
                temperature = 0.1, // 降低温度以提高逻辑稳定性
                responseFormat = ModelRequest.ResponseFormat("json_object"),
                messages = listOf(
                    ModelMessage("system", systemPrompt),
                    ModelMessage("user", userPrompt)
                )
            )

            Log.d(TAG, "正在请求模型: $modelName")

            val responseText = ApiModelProvider.generate(modelRequest)
            Log.d(TAG, "AI 返回结果: $responseText")

            try {
                jsonParser.decodeFromString<List<CalendarEventData>>(responseText)
            } catch (e: Exception) {
                // 容错处理：有时模型会包裹在 key 中
                if (responseText.trim().startsWith("{")) {
                    val jsonObject = JSONObject(responseText)
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

    // 提取文本并按 Y 轴坐标排序
    private suspend fun extractTextFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // 1. 获取所有 TextBlock 中的 Line（行）
                    val allLines = visionText.textBlocks.flatMap { it.lines }

                    // 2. 按照 Line 的上方坐标 (top) 进行排序
                    val sortedLines = allLines.sortedBy { it.boundingBox?.top ?: 0 }

                    // 3. 拼接结果
                    val resultText = sortedLines.joinToString("\n") { it.text }

                    continuation.resume(resultText)
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}