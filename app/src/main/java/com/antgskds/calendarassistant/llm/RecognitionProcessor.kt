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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val dtfTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val timeStr = now.format(dtfFull)
        val dateToday = now.format(dtfDate)
        val dateYesterday = now.minusDays(1).format(dtfDate)
        val dateBeforeYesterday = now.minusDays(2).format(dtfDate)

        val settings = context.getSettings()
        val modelName = settings.modelName.ifBlank { "deepseek-chat" }

        // --- 1. 原有的日程提取 Prompt (保持不变) ---
        val itemSchema = JSONObject().apply {
            put("title", "日程标题")
            put("startTime", "格式 yyyy-MM-dd HH:mm")
            put("endTime", "格式 yyyy-MM-dd HH:mm")
            put("location", "地点")
            put("description", "备注")
            put("type", "固定填 'event'")
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

        // --- 2. 优化后的：取件码/取餐码提取 Prompt ---
        // 【Issue 2 修复】：强调提取短号码、顶部大字号
        // 【Issue 3 修复】：强调标题格式包含号码
        val codeSystemPrompt = """
            你是一个生活助手，专门从文本中提取【取件码】和【取餐码】。
            当前系统时间：$timeStr
            
            任务规则：
            1. 识别快递短信、丰巢通知、外卖订单中的取件码或取餐码。
            2. **号码识别优先级**：
               - 优先提取**短号码**（通常3-6位数字）或**货架号**（如 1-100, 100-6-3007）。     
               - 优先提取位于文本**顶部**或**字号较大**（独立一行）的号码。
               - **排除**底部的营销数字、会员群号、长串订单号。
            3.【防幻觉特别指令】：
            - 取件码经常包含字母（例如 "L-6-xxxx" 或 "A-12"）。
            - 取餐码可能包含字母（例如 "A112" 或 "B34"）。
            - **严禁**将字母 "L" 自动纠错为数字 "1"。
            - **严禁**将字母 "O" 自动纠错为数字 "0"。
            - 必须按 OCR 看到的原始内容提取，保留连字符和字母。
            4. 如果没有相关代码，返回空列表。
            
            【输出格式】
            纯 JSON 对象：
            {
              "events": [
                 {
                    "title": "格式必须为: '品牌/类型 + 号码' (例如: '丰巢取件 88-9022', '麦当劳取餐 35230', '取件码 1-6-3007')",
                    "description": "只填号码(可含字母和连字符)，严禁包含空格或'取件码'等前缀",
                    "location": "如果有柜机位置或餐厅名则填入，否则留空",
                    "type": "pickup",
                    "startTime": "${now.format(dtfTime)}",
                    "endTime": "${now.plusHours(1).format(dtfTime)}"
                 }
              ]
            }
        """.trimIndent()

        val userPrompt = """
            [OCR文本开始]
            $extractedText
            [OCR文本结束]
        """.trimIndent()

        return try {
            coroutineScope {
                // 任务 A: 原有日程提取
                val scheduleJob = async {
                    Log.d(TAG, "正在请求模型 (日程)...")
                    val request = ModelRequest(
                        model = modelName,
                        temperature = 0.1,
                        messages = listOf(
                            ModelMessage("system", systemPrompt),
                            ModelMessage("user", userPrompt)
                        )
                    )
                    executeAiRequest(request, "日程任务")
                }

                // 任务 B: 临时事件提取 (取件码/取餐码)
                val codeJob = async {
                    // 【Issue 4 修复】：增加 "包裹", "驿站", "领取" 关键词，确保快递短信能触发此任务
                    val keywords = listOf("取件", "取餐", "提货", "单号", "验证码", "取货", "餐号", "包裹", "驿站", "领取")
                    if (keywords.none { extractedText.contains(it) }) {
                        return@async emptyList<CalendarEventData>()
                    }

                    Log.d(TAG, "正在请求模型 (取件码)...")
                    val request = ModelRequest(
                        model = modelName,
                        temperature = 0.1,
                        messages = listOf(
                            ModelMessage("system", codeSystemPrompt),
                            ModelMessage("user", userPrompt)
                        )
                    )
                    executeAiRequest(request, "取件码任务")
                }

                val schedules = scheduleJob.await()
                val codes = codeJob.await()

                Log.d(TAG, "AI 结果汇总: 日程=${schedules.size}条, 码=${codes.size}条")

                // 【逻辑优化】：如果识别出了取件码(临时事件)，通常意味着这是一张取件码截图。
                // 此时忽略“日程任务”的结果，避免生成重复的、格式不理想的“去取快递”日程。
                // 这样也解决了“被放到日程事件分类下”的问题，因为只会返回 type="pickup" 的事件。
                if (codes.isNotEmpty()) {
                    codes
                } else {
                    schedules
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI 分析严重错误", e)
            emptyList()
        }
    }

    private suspend fun executeAiRequest(request: ModelRequest, debugTag: String): List<CalendarEventData> {
        return try {
            val responseText = ApiModelProvider.generate(request)
            if (responseText.startsWith("Error:")) {
                Log.e(TAG, "[$debugTag] API 请求失败: $responseText")
                return emptyList()
            }

            var cleanJson = responseText.trim()
            if (cleanJson.startsWith("```")) {
                cleanJson = cleanJson.substringAfter("json").substringAfter("\n").substringBeforeLast("```")
            }

            val rootObject = JSONObject(cleanJson)
            if (rootObject.has("reasoning")) {
                Log.d(TAG, "[$debugTag] 推理: ${rootObject.getString("reasoning")}")
            }

            val eventsArray = rootObject.optJSONArray("events") ?: JSONArray()
            if (eventsArray.length() > 0) {
                jsonParser.decodeFromString<List<CalendarEventData>>(eventsArray.toString())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$debugTag] JSON 解析失败", e)
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