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

    // --- 新增：处理自然语言文本输入 (带详细日志) ---
    suspend fun parseUserText(text: String): CalendarEventData? {
        val now = LocalDateTime.now()
        val dtfFull = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE")
        val timeStr = now.format(dtfFull)

        // 1. 构建 Prompt
        val prompt = """
            你是一个日程助手。
            【当前系统时间】：$timeStr
            
            任务：从用户的自然语言描述中提取日程信息。
            
            【规则】
            1. 根据当前时间推断相对时间（如“明天”、“下周三”）。
            2. 提取标题、时间、地点、备注。
            3. 如果用户没有说明结束时间，默认持续1小时。
            4. 如果内容包含取件码/验证码，type 设置为 "pickup"，否则为 "event"。
            
            【输出格式】
            纯 JSON 对象 (不要 Markdown，不要 ```json 包裹)：
            {
               "title": "简短标题",
               "startTime": "yyyy-MM-dd HH:mm",
               "endTime": "yyyy-MM-dd HH:mm",
               "location": "地点(可选)",
               "description": "备注或原文",
               "type": "event 或 pickup"
            }
        """.trimIndent()

        // --- 【LOG】输出输入信息 ---
        Log.d(TAG, "========== [AI 自然语言输入开始] ==========")
        Log.d(TAG, "用户输入: $text")
        Log.d(TAG, "参考时间: $timeStr")
        // 如果需要调试 Prompt，可以取消下面这行的注释
        // Log.d(TAG, "完整 Prompt:\n$prompt")

        val settings = MyApplication.getInstance().getSettings()
        val modelName = settings.modelName.ifBlank { "deepseek-chat" }

        val request = ModelRequest(
            model = modelName,
            messages = listOf(
                ModelMessage("system", prompt),
                ModelMessage("user", text)
            ),
            temperature = 0.3
        )

        return try {
            Log.d(TAG, "正在请求模型: $modelName ...")

            // 2. 发起请求
            val response = ApiModelProvider.generate(request)

            // --- 【LOG】输出原始响应 ---
            Log.d(TAG, "AI 原始响应: $response")

            // 检查 API 错误
            if (response.startsWith("Error:")) {
                Log.e(TAG, "API 返回错误: $response")
                return null
            }

            // 3. 清洗 JSON
            var cleanJson = response.trim()
            if (cleanJson.contains("```")) {
                cleanJson = cleanJson.substringAfter("json").substringAfter("\n").substringBeforeLast("```")
            }

            // --- 【LOG】输出清洗后的 JSON ---
            Log.d(TAG, "准备解析 JSON: $cleanJson")

            // 4. 解析对象
            val result = jsonParser.decodeFromString<CalendarEventData>(cleanJson)

            Log.d(TAG, "解析成功! 标题: [${result.title}], 时间: [${result.startTime}]")
            Log.d(TAG, "========== [AI 自然语言输入结束] ==========")

            result

        } catch (e: Exception) {
            Log.e(TAG, "AI 解析过程发生异常", e)
            null
        }
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

        val useRecTimeForTemp = settings.tempEventsUseRecognitionTime

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
               
            3. **【绝对邻近原则 & 纯时间判定 - 优先级最高】**：
               - 必须以**物理距离最近**（紧挨着消息内容上方）的那一行时间戳为准。
               - **严禁**跳过紧邻的纯时间戳（如"08:30"）去参考更上面、更远的带日期时间戳（如"昨天 13:40"）。距离越近，权重绝对越高。
               - **默认规则**：如果最近的时间戳只是 "HH:mm"（无“昨天”、“星期几”等前缀），它**绝对代表今天**（${'$'}dateToday）。
            
            【输出格式】
            纯 JSON 对象：
            {
              "reasoning": "必须写出：基准是哪天？内容偏移几天？最终日期是？",
              "events": [ $itemSchema ]
            }
        """.trimIndent()

        val tempTimeInstruction = if (useRecTimeForTemp) {
            """
            5. **时间设定强制规则**：
               - 必须忽略文本中的时间信息。
               - "startTime" 必须填入当前系统时间：${now.format(dtfTime)}
               - "endTime" 必须填入当前时间后推1小时：${now.plusHours(1).format(dtfTime)}
            """.trimIndent()
        } else {
            """
            5. **时间设定智能规则**：
               - 优先在文本中寻找事件发生的具体时间（例如："14:30已存柜"、"请在22:00前取件"、"下单时间 11:45"）。
               - 如果找到具体时间，请结合当前日期 "$dateToday" (或昨/前天) 计算出准确的 "startTime"。
               - 如果文本完全未提及时间，才回退使用当前系统时间：${now.format(dtfTime)}。
               - "endTime" 设为 "startTime" 往后推1小时。
            """.trimIndent()
        }

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
            4. 如果没有相关代码，返回空列表。
            $tempTimeInstruction
            
            【输出格式】
            纯 JSON 对象：
            {
              "events": [
                 {
                    "title": "格式必须为: '品牌/平台 + 动作' (例如: '丰巢取件', '麦当劳取餐')。严禁包含具体的取件码！",
                    "description": "只填号码(可含字母和连字符)，严禁包含空格或'取件码'等前缀",
                    "location": "如果有柜机位置、具体的楼栋单元或餐厅名则填入，否则留空",
                    "type": "pickup",
                    "startTime": "格式 yyyy-MM-dd HH:mm (遵循规则5)",
                    "endTime": "格式 yyyy-MM-dd HH:mm"
                 }
              ]
            }
        """.trimIndent()

        val unifiedPrompt = """
            【强制模式选择指令 - 先读此指令再处理】
            你必须严格按以下规则选择处理模式，绝不允许混合、误判或参考非选中模式的内容：

            1. 扫描OCR文本的全部内容，立即判断（优先级最高）：
               - 如果文本包含这些核心锚定词中的任何一个：【取件、取餐、提货、验证码、快递单号、运单号、丰巢、菜鸟驿站、货架、取货码、取件码、取餐码】
               - → 强制使用【模式B】，**完全跳过、不读取、不参考模式A的任何内容**
               
            2. 如果上述锚定词一个都没有：
               - → 强制使用【模式A】，**完全跳过、不读取、不参考模式B的任何内容**

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

        return try {
            val request = ModelRequest(
                model = modelName,
                temperature = 0.1,
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