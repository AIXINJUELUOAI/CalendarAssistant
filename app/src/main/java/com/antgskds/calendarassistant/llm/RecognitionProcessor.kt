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
        val dtfTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val timeStr = now.format(dtfFull)
        val dateToday = now.format(dtfDate)
        val dateYesterday = now.minusDays(1).format(dtfDate)
        val dateBeforeYesterday = now.minusDays(2).format(dtfDate)

        val settings = context.getSettings()
        val modelName = settings.modelName.ifBlank { "deepseek-chat" }

        // =================================================================================
        // 原有 Prompt 1：普通日程提取 (完整保留，一字未删，保证日期逻辑稳定性)
        // =================================================================================
        val itemSchema = JSONObject().apply {
            put("title", "日程标题")
            put("startTime", "格式 yyyy-MM-dd HH:mm")
            put("endTime", "格式 yyyy-MM-dd HH:mm")
            put("location", "地点")
            put("description", "备注")
            put("type", "固定填 'event'")
        }

        val originalSchedulePrompt = """
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

        // =================================================================================
        // 原有 Prompt 2：取件码提取 (完整保留，一字未删，保证防幻觉指令有效性)
        // =================================================================================
        val originalCodePrompt = """
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
                    "title": "格式必须为: '品牌/平台 + 动作' (例如: '丰巢取件', '麦当劳取餐', '菜鸟驿站包裹', '兔喜生活取件')。注意：标题中**严禁**包含具体的取件码或取餐号！",
                    "description": "只填号码(可含字母和连字符)，严禁包含空格或'取件码'等前缀",
                    "location": "如果有柜机位置、具体的楼栋单元或餐厅名则填入，否则留空",
                    "type": "pickup",
                    "startTime": "${now.format(dtfTime)}",
                    "endTime": "${now.plusHours(1).format(dtfTime)}"
                 }
              ]
            }
        """.trimIndent()

        // =================================================================================
        // 核心修改：使用“强制锚定词”+“物理分隔”策略，融合以上两段 Prompt
        // =================================================================================
        val unifiedPrompt = """
            【强制模式选择指令 - 先读此指令再处理】
            你必须严格按以下规则选择处理模式，绝不允许混合、误判或参考非选中模式的内容：

            1. 扫描OCR文本的全部内容，立即判断（优先级最高）：
               - 如果文本包含这些核心锚定词中的任何一个：【取件、取餐、提货、验证码、快递单号、运单号、丰巢、菜鸟驿站、货架、取货码、取件码、取餐码】
               - → 强制使用【模式B】，**完全跳过、不读取、不参考模式A的任何内容**
               
            2. 如果上述锚定词一个都没有：
               - → 强制使用【模式A】，**完全跳过、不读取、不参考模式B的任何内容**

            【重要】选择后，另一个模式的内容对你来说是无效文本，如同不存在！你仅能读取、执行选中模式的内容。

            【当前系统时间】：$timeStr
            （下面两个模式完整保留，你只看选中的那个）

            ==================================================================================
            如果选择【模式A】，只看以下内容：【执行边界：仅处理此部分，不回溯其他内容】
            ==================================================================================
            $originalSchedulePrompt

            ==================================================================================
            如果选择【模式B】，只看以下内容：【执行边界：仅处理此部分，不回溯其他内容】
            ==================================================================================
            $originalCodePrompt
            【模式B补充】必须在JSON中添加"reasoning"字段，内容固定为："识别到取件/取餐/快递信息"
            ==================================================================================

            【最终输出】
            根据你的选择，输出对应的纯JSON字符串，无任何额外文字、注释、换行或说明。
        """.trimIndent()

        val userPrompt = """
            [OCR文本开始]
            $extractedText
            [OCR文本结束]
        """.trimIndent()

        // --- 执行单次 AI 请求 ---
        return try {
            Log.d(TAG, "正在请求 AI (合并模式)...")
            val request = ModelRequest(
                model = modelName,
                temperature = 0.1, // 保持低温，确保严格遵循锚定词指令
                messages = listOf(
                    ModelMessage("system", unifiedPrompt),
                    ModelMessage("user", userPrompt)
                )
            )
            executeAiRequest(request, "智能分类任务")
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
            if (cleanJson.contains("```")) {
                cleanJson = cleanJson.substringAfter("json").substringAfter("\n").substringBeforeLast("```")
            }

            Log.d(TAG, "AI 原始响应: $cleanJson")

            val rootObject = JSONObject(cleanJson)
            // 记录推理过程（如果模式B触发，这里会显示"识别到取件/取餐/快递信息"）
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