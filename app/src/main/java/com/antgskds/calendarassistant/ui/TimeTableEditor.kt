package com.antgskds.calendarassistant.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antgskds.calendarassistant.model.TimeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimeTableEditorDialog(
    initialJson: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jsonParser = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // --- 状态管理 ---
    // 总节数 (默认12)
    var totalNodes by remember { mutableIntStateOf(12) }
    // 单节时长 (默认45分钟)
    var courseDuration by remember { mutableIntStateOf(45) }

    // 三大锚点时间 (早/午/晚)
    var morningStart by remember { mutableStateOf(LocalTime.of(8, 0)) }
    var afternoonStart by remember { mutableStateOf(LocalTime.of(14, 0)) }
    var nightStart by remember { mutableStateOf(LocalTime.of(19, 0)) }

    // 特殊休息时间 Map: key=第几节课后, value=休息分钟数 (默认10)
    val customBreaks = remember { mutableStateMapOf<Int, Int>() }

    // 弹窗控制状态
    var showTotalNodesPicker by remember { mutableStateOf(false) }
    var showBreakPickerForNode by remember { mutableStateOf<Int?>(null) }
    var showTimePickerForAnchor by remember { mutableStateOf<String?>(null) } // "morning", "afternoon", "night"

    // --- 初始化逻辑 ---
    LaunchedEffect(Unit) {
        if (initialJson.isNotBlank()) {
            try {
                val nodes = jsonParser.decodeFromString<List<TimeNode>>(initialJson)
                if (nodes.isNotEmpty()) {
                    totalNodes = nodes.size
                    // 尝试反推锚点
                    morningStart = LocalTime.parse(nodes[0].startTime)
                    if (nodes.size >= 5 && nodes.size > 4) afternoonStart = LocalTime.parse(nodes[4].startTime)
                    if (nodes.size >= 9 && nodes.size > 8) nightStart = LocalTime.parse(nodes[8].startTime)

                    // 尝试反推时长 (取第一节课的时长)
                    val firstStart = LocalTime.parse(nodes[0].startTime)
                    val firstEnd = LocalTime.parse(nodes[0].endTime)
                    courseDuration = Duration.between(firstStart, firstEnd).toMinutes().toInt()

                    // 尝试反推特殊休息时间
                    for (i in 0 until nodes.size - 1) {
                        val currentEnd = LocalTime.parse(nodes[i].endTime)
                        val nextStart = LocalTime.parse(nodes[i + 1].startTime)
                        // 忽略跨段的时间 (比如第4节和第5节之间，这是由锚点决定的)
                        if (i + 1 == 4 || i + 1 == 8) continue

                        val diff = Duration.between(currentEnd, nextStart).toMinutes().toInt()
                        if (diff != 10) {
                            customBreaks[i + 1] = diff
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- 核心计算引擎：链式推导 ---
    val generatedNodes = remember(totalNodes, courseDuration, morningStart, afternoonStart, nightStart, customBreaks.toMap()) {
        val list = mutableListOf<TimeNode>()
        val fmt = DateTimeFormatter.ofPattern("HH:mm")

        for (i in 1..totalNodes) {
            val startTime: LocalTime

            if (i == 1) {
                startTime = morningStart
            } else if (i == 5) {
                startTime = afternoonStart
            } else if (i == 9) {
                startTime = nightStart
            } else {
                // 链式推导：上一节结束 + 休息时间
                val prevNode = list.last() // index i-2
                val prevEnd = LocalTime.parse(prevNode.endTime)
                val breakMinutes = customBreaks[i - 1] ?: 10 // 默认为10分钟
                startTime = prevEnd.plusMinutes(breakMinutes.toLong())
            }

            val endTime = startTime.plusMinutes(courseDuration.toLong())
            list.add(TimeNode(i, startTime.format(fmt), endTime.format(fmt)))
        }
        list
    }

    // --- 导入功能 ---
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val json = BufferedReader(InputStreamReader(input)).readText()
                        withContext(Dispatchers.Main) {
                            val nodes = jsonParser.decodeFromString<List<TimeNode>>(json)
                            if (nodes.isNotEmpty()) {
                                totalNodes = nodes.size
                                morningStart = LocalTime.parse(nodes[0].startTime)
                                if (nodes.size >= 5) afternoonStart = LocalTime.parse(nodes[4].startTime)
                                if (nodes.size >= 9) nightStart = LocalTime.parse(nodes[8].startTime)
                                customBreaks.clear()
                                onConfirm(json)
                                Toast.makeText(context, "导入成功并应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(16.dp)),
            topBar = {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    CenterAlignedTopAppBar(
                        title = { Text("作息时间设置") },
                        navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) } },
                        actions = {
                            TextButton(onClick = {
                                val jsonStr = jsonParser.encodeToString(generatedNodes)
                                onConfirm(jsonStr)
                            }) { Text("保存", fontWeight = FontWeight.Bold) }
                        }
                    )
                    // 全局控制栏
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showTotalNodesPicker = true }) {
                            Text("总节数: $totalNodes")
                        }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                            Icon(Icons.Default.UploadFile, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("导入配置")
                        }
                    }
                    HorizontalDivider()
                }
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.padding(innerPadding).fillMaxSize().background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(16.dp)
            ) {
                itemsIndexed(generatedNodes) { index, node ->
                    val nodeIndex = node.index

                    // 1. 分段标题
                    if (nodeIndex == 1) SectionHeader("上午课程", morningStart) { showTimePickerForAnchor = "morning" }
                    else if (nodeIndex == 5) SectionHeader("下午课程", afternoonStart) { showTimePickerForAnchor = "afternoon" }
                    else if (nodeIndex == 9) SectionHeader("晚上课程", nightStart) { showTimePickerForAnchor = "night" }

                    // 2. 课程卡片
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text(nodeIndex.toString(), modifier = Modifier.padding(4.dp))
                                }
                                Spacer(Modifier.width(16.dp))
                                Text("${node.startTime} - ${node.endTime}", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("45分钟", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }

                    // 3. 课间休息连接线
                    if (nodeIndex < totalNodes) {
                        if (nodeIndex == 4 || nodeIndex == 8) {
                            // 大段分隔（午休/晚饭）
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                HorizontalDivider(modifier = Modifier.alpha(0.3f))
                                Text(
                                    if(nodeIndex==4) "午休 (由下午开始时间决定)" else "晚饭 (由晚上开始时间决定)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp)
                                )
                            }
                        } else {
                            // 可调整休息时间
                            val breakTime = customBreaks[nodeIndex] ?: 10
                            Box(
                                Modifier.fillMaxWidth().height(30.dp).clickable { showBreakPickerForNode = nodeIndex },
                                contentAlignment = Alignment.Center
                            ) {
                                VerticalDivider(modifier = Modifier.height(15.dp).width(2.dp), color = MaterialTheme.colorScheme.primaryContainer)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        // 【修复】Icons.Default.Schedule (大写 S)
                                        Icon(Icons.Default.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Spacer(Modifier.width(4.dp))
                                        Text("休息 ${breakTime} 分钟", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    // --- 弹窗组件 ---

    // 总节数调整
    if (showTotalNodesPicker) {
        AlertDialog(
            onDismissRequest = { showTotalNodesPicker = false },
            title = { Text("设置每天总节数: $totalNodes") },
            text = {
                Column {
                    Slider(
                        value = totalNodes.toFloat(),
                        onValueChange = { totalNodes = it.toInt() },
                        valueRange = 4f..16f,
                        steps = 11
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showTotalNodesPicker = false }) { Text("确定") } }
            // 【修复】删除了重复的 onDismissRequest
        )
    }

    // 休息时间调整
    if (showBreakPickerForNode != null) {
        val nodeIdx = showBreakPickerForNode!!
        val currentBreak = customBreaks[nodeIdx] ?: 10
        val options = listOf(5, 10, 15, 20, 25, 30, 40)

        AlertDialog(
            onDismissRequest = { showBreakPickerForNode = null },
            title = { Text("第${nodeIdx}节 - 第${nodeIdx+1}节 课间") },
            text = {
                // 【修复】已添加 @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { min ->
                        FilterChip(
                            selected = currentBreak == min,
                            onClick = {
                                customBreaks[nodeIdx] = min
                                showBreakPickerForNode = null
                            },
                            label = { Text("${min}分钟") }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBreakPickerForNode = null }) { Text("取消") } }
        )
    }

    // 锚点时间调整 (复用 SettingsComponents 中的 WheelTimePickerDialog)
    if (showTimePickerForAnchor != null) {
        val initial = when(showTimePickerForAnchor) {
            "morning" -> morningStart
            "afternoon" -> afternoonStart
            else -> nightStart
        }
        WheelTimePickerDialog(
            initialTime = initial.toString(),
            onDismiss = { showTimePickerForAnchor = null },
            onConfirm = { timeStr ->
                val newTime = LocalTime.parse(timeStr)
                when(showTimePickerForAnchor) {
                    "morning" -> morningStart = newTime
                    "afternoon" -> afternoonStart = newTime
                    "night" -> nightStart = newTime
                }
                showTimePickerForAnchor = null
            }
        )
    }
}

@Composable
fun SectionHeader(title: String, time: LocalTime, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开始时间: $time", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.Edit, null, Modifier.size(16.dp).padding(start = 4.dp), tint = Color.Gray)
        }
    }
}