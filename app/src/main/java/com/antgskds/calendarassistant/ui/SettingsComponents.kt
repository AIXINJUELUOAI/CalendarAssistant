package com.antgskds.calendarassistant.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antgskds.calendarassistant.MyApplication
import com.antgskds.calendarassistant.receiver.DailySummaryScheduler
import com.antgskds.calendarassistant.util.NotificationScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// --- 1. Expandable Group Component ---

@Composable
fun ExpandableSettingsGroup(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    showDivider: Boolean = true,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 【修改 1】：去除点击波浪纹
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onExpandedChange(!expanded) }
                .padding(vertical = 18.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow")
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
                tint = Color.Gray
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
                content()
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

// --- 2. Schedule Settings Sidebar ---

@Composable
fun ScheduleSettingsSidebar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    semesterStartDate: LocalDate?,
    onSemesterStartDateChange: (LocalDate) -> Unit,
    totalWeeks: Int,
    onTotalWeeksChange: (Int) -> Unit,
    onManageCourses: () -> Unit,
    onEditTimeTable: () -> Unit,
    onExportCourses: () -> Unit,
    onImportCourses: () -> Unit
) {
    ExpandableSettingsGroup(
        title = "课表设置",
        icon = Icons.Default.TableChart,
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        // Calculate current week
        val currentWeek = if (semesterStartDate != null) {
            val daysDiff = ChronoUnit.DAYS.between(semesterStartDate, LocalDate.now())
            (daysDiff / 7).toInt() + 1
        } else {
            1
        }

        var showDatePicker by remember { mutableStateOf(false) }

        // 【修改 3】：新增周次选择器状态
        var showWeekPicker by remember { mutableStateOf(false) }
        var showTotalWeeksPicker by remember { mutableStateOf(false) }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Row 1: First Day of Week 1
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("第一周第一天", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = semesterStartDate?.toString() ?: "未设置",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Row 2: Current Week (可点击)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWeekPicker = true } // 【修改 3】：添加点击事件
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("当前周", style = MaterialTheme.typography.bodyLarge)
                Text("第 $currentWeek 周", color = MaterialTheme.colorScheme.primary)
            }

            // Row 3: Total Weeks (可点击)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTotalWeeksPicker = true } // 【修改 3】：添加点击事件
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("学期总周数", style = MaterialTheme.typography.bodyLarge)
                Text("$totalWeeks 周", color = MaterialTheme.colorScheme.primary)
            }

            // Row 4: Course Management
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onManageCourses() }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("课程管理", style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }

            // Row 5: Time Table Editor
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onEditTimeTable() }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("作息时间设置", style = MaterialTheme.typography.bodyLarge)
                    Text("设置每日节次时间段", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Icon(Icons.Default.AccessTime, null, tint = Color.Gray)
            }

            Spacer(Modifier.height(8.dp))

            // 【修改 2】：改为垂直排列，统一样式
            Text("课表数据", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            OutlinedButton(
                onClick = onExportCourses,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导出课程数据")
            }
            OutlinedButton(
                onClick = onImportCourses,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导入课程数据")
            }
        }

        if (showDatePicker) {
            WheelDatePickerDialog(semesterStartDate ?: LocalDate.now(), { showDatePicker = false }) {
                onSemesterStartDateChange(it)
                showDatePicker = false
            }
        }

        // 【修改 3】：当前周选择器 (反推开始日期)
        if (showWeekPicker) {
            val weekOptions = (1..30).toList()
            var selectedWeek by remember { mutableIntStateOf(currentWeek) }

            AlertDialog(
                onDismissRequest = { showWeekPicker = false },
                title = { Text("设置当前是第几周") },
                text = {
                    WheelPicker(
                        items = weekOptions.map { "第 $it 周" },
                        initialIndex = (currentWeek - 1).coerceAtLeast(0),
                        onSelectionChanged = { selectedWeek = weekOptions[it] }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 逻辑：如果今天是第 N 周，那么 (N-1) 周前是第一周
                        val today = LocalDate.now()
                        val daysToSubtract = (selectedWeek - 1) * 7L
                        val newStartDate = today.minusDays(daysToSubtract)
                        onSemesterStartDateChange(newStartDate)
                        showWeekPicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showWeekPicker = false }) { Text("取消") } }
            )
        }

        // 【修改 3】：总周数选择器
        if (showTotalWeeksPicker) {
            val totalOptions = (10..30).toList()
            var selectedTotal by remember { mutableIntStateOf(totalWeeks) }

            AlertDialog(
                onDismissRequest = { showTotalWeeksPicker = false },
                title = { Text("设置学期总周数") },
                text = {
                    WheelPicker(
                        items = totalOptions.map { "$it 周" },
                        initialIndex = totalOptions.indexOf(totalWeeks).coerceAtLeast(0),
                        onSelectionChanged = { selectedTotal = totalOptions[it] }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onTotalWeeksChange(selectedTotal)
                        showTotalWeeksPicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showTotalWeeksPicker = false }) { Text("取消") } }
            )
        }
    }
}

// --- 3. Model Settings ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsGroup(
    snackbarHostState: SnackbarHostState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    ExpandableSettingsGroup(
        title = "AI 模型配置",
        icon = Icons.Default.Android,
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        val scope = rememberCoroutineScope()
        val settings = MyApplication.getInstance().getSettings()

        val currentUrl = settings.modelUrl
        val currentModel = settings.modelName

        val initialProvider = when {
            currentUrl.contains("deepseek") -> "DeepSeek"
            currentUrl.contains("openai") -> "OpenAI"
            currentUrl.contains("googleapis") -> "Gemini"
            currentUrl.isBlank() && currentModel.isBlank() -> "DeepSeek"
            else -> "自定义"
        }

        var selectedProvider by remember { mutableStateOf(initialProvider) }
        var expandedProvider by remember { mutableStateOf(false) }
        var expandedModel by remember { mutableStateOf(false) }

        var modelUrl by remember { mutableStateOf(settings.modelUrl) }
        var modelName by remember { mutableStateOf(settings.modelName) }
        var modelKey by remember { mutableStateOf(settings.modelKey) }

        val providers = listOf("DeepSeek", "OpenAI", "Gemini", "自定义")
        val availableModels = mapOf(
            "DeepSeek" to listOf("deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
            "OpenAI" to listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo"),
            "Gemini" to listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash-exp")
        )

        LaunchedEffect(selectedProvider) {
            if (selectedProvider != "自定义") {
                modelUrl = when (selectedProvider) {
                    "DeepSeek" -> "https://api.deepseek.com/chat/completions"
                    "OpenAI" -> "https://api.openai.com/v1/chat/completions"
                    "Gemini" -> "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
                    else -> ""
                }
                val models = availableModels[selectedProvider] ?: emptyList()
                if (models.isNotEmpty() && modelName !in models) {
                    modelName = models.first()
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(
                expanded = expandedProvider,
                onExpandedChange = { expandedProvider = !expandedProvider }
            ) {
                OutlinedTextField(
                    value = selectedProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("服务提供商") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedProvider) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedProvider,
                    onDismissRequest = { expandedProvider = false }
                ) {
                    providers.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = { selectedProvider = item; expandedProvider = false }
                        )
                    }
                }
            }

            if (selectedProvider != "自定义") {
                val models = availableModels[selectedProvider] ?: emptyList()
                ExposedDropdownMenuBox(
                    expanded = expandedModel,
                    onExpandedChange = { expandedModel = !expandedModel }
                ) {
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("模型名称") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedModel) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedModel,
                        onDismissRequest = { expandedModel = false }
                    ) {
                        models.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    modelName = item
                                    expandedModel = false
                                    if (selectedProvider == "Gemini") {
                                        modelUrl = "https://generativelanguage.googleapis.com/v1beta/models/$item:generateContent"
                                    }
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = modelKey,
                onValueChange = { modelKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (selectedProvider == "自定义") {
                OutlinedTextField(value = modelUrl, onValueChange = { modelUrl = it }, label = { Text("API URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = modelName, onValueChange = { modelName = it }, label = { Text("Model Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }

            Button(
                onClick = {
                    scope.launch {
                        settings.modelUrl = modelUrl.trim()
                        settings.modelName = modelName.trim()
                        settings.modelKey = modelKey.trim()
                        snackbarHostState.showSnackbar("AI 配置已保存")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存配置")
            }
        }
    }
}

// --- 4. Preference Settings ---

@Composable
fun PreferenceSettingsGroup(
    snackbarHostState: SnackbarHostState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val settings = MyApplication.getInstance().getSettings()

    var autoAlarm by remember { mutableStateOf(settings.autoCreateAlarm) }
    var showTomorrow by remember { mutableStateOf(settings.showTomorrowEvents) }
    var dailySummary by remember { mutableStateOf(settings.isDailySummaryEnabled) }
    var liveCapsule by remember { mutableStateOf(settings.isLiveCapsuleEnabled) }
    var useRecTime by remember { mutableStateOf(settings.tempEventsUseRecognitionTime) }
    var delayMs by remember { mutableLongStateOf(settings.screenshotDelayMs) }

    ExpandableSettingsGroup(
        title = "偏好设置",
        icon = Icons.Default.Tune,
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Auto Alarm
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("创建系统闹钟", style = MaterialTheme.typography.bodyLarge)
                    Text("自动创建关联闹钟", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = autoAlarm, onCheckedChange = { autoAlarm = it; settings.autoCreateAlarm = it })
            }

            // Show Tomorrow
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示明日日程", style = MaterialTheme.typography.bodyLarge)
                    Text("列表底部预览", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = showTomorrow, onCheckedChange = { showTomorrow = it; settings.showTomorrowEvents = it })
            }

            // Time Recognition Preference
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("智能提取取件时间", style = MaterialTheme.typography.bodyLarge)
                    Text("开启以使用短信内时间，关闭则使用当前时间", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = useRecTime, onCheckedChange = { useRecTime = it; settings.tempEventsUseRecognitionTime = it })
            }

            // Screenshot Delay
            Column(Modifier.fillMaxWidth()) {
                Text("截图识别延迟: ${delayMs}ms", style = MaterialTheme.typography.bodyLarge)
                Text("调整等待通知栏收起的时间", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Slider(
                    value = delayMs.toFloat(),
                    onValueChange = { delayMs = it.toLong() },
                    onValueChangeFinished = { settings.screenshotDelayMs = delayMs },
                    valueRange = 200f..2000f,
                    steps = 17
                )
            }

            // Live Capsule
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实况胶囊通知 (Beta)", style = MaterialTheme.typography.bodyLarge)
                    Text("需开启无障碍权限，可能会耗电", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Switch(checked = liveCapsule, onCheckedChange = {
                    liveCapsule = it
                    settings.isLiveCapsuleEnabled = it
                    if (it) Toast.makeText(context, "请确保已开启无障碍服务", Toast.LENGTH_LONG).show()
                })
            }

            // Daily Summary
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每日日程提醒", style = MaterialTheme.typography.bodyLarge)
                    Text("早6点/晚10点推送", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Switch(checked = dailySummary, onCheckedChange = { isChecked ->
                    dailySummary = isChecked
                    settings.isDailySummaryEnabled = isChecked
                    if (isChecked) DailySummaryScheduler.scheduleAll(context) else DailySummaryScheduler.cancelAll(context)
                })
            }
        }
    }
}

// --- Pickers ---

@Composable
fun WheelDatePickerDialog(initialDate: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    var selectedDate by remember { mutableStateOf(initialDate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = { WheelDatePicker(initialDate, { selectedDate = it }) },
        confirmButton = { TextButton(onClick = { onConfirm(selectedDate) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

@Composable
fun WheelDatePicker(initialDate: LocalDate, onDateChanged: (LocalDate) -> Unit) {
    val years = (2020..2035).toList(); val months = (1..12).toList()
    var sY by remember { mutableIntStateOf(initialDate.year) }
    var sM by remember { mutableIntStateOf(initialDate.monthValue) }
    var sD by remember { mutableIntStateOf(initialDate.dayOfMonth) }
    val daysInMonth = remember(sY, sM) { YearMonth.of(sY, sM).lengthOfMonth() }
    LaunchedEffect(daysInMonth) { if (sD > daysInMonth) sD = daysInMonth; onDateChanged(LocalDate.of(sY, sM, sD)) }
    LaunchedEffect(sY, sM, sD) { if (sD <= daysInMonth) onDateChanged(LocalDate.of(sY, sM, sD)) }
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WheelPicker(years.map{"${it}年"}, years.indexOf(sY).coerceAtLeast(0), Modifier.weight(1.3f)) { sY = years[it] }
        WheelPicker(months.map{String.format("%02d月",it)}, months.indexOf(sM).coerceAtLeast(0), Modifier.weight(1f)) { sM = months[it] }
        WheelPicker((1..daysInMonth).map{String.format("%02d日",it)}, (sD-1).coerceIn(0,daysInMonth-1), Modifier.weight(1f)) { sD = it+1 }
    }
}

@Composable
fun WheelPicker(items: List<String>, initialIndex: Int, modifier: Modifier = Modifier, onSelectionChanged: (Int) -> Unit) {
    val listState = rememberPagerState(initialPage = initialIndex) { items.size }
    LaunchedEffect(listState.currentPage) { onSelectionChanged(listState.currentPage) }
    Box(modifier.height(175.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal=4.dp).height(35.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f), RoundedCornerShape(12.dp)))
        VerticalPager(state = listState, contentPadding = PaddingValues(vertical = 70.dp), modifier = Modifier.fillMaxSize()) { page ->
            val pageOffset = (listState.currentPage - page) + listState.currentPageOffsetFraction
            val alpha = (1f - (Math.abs(pageOffset) * 0.6f)).coerceAtLeast(0.2f)
            Box(Modifier.height(35.dp), contentAlignment = Alignment.Center) {
                Text(text = items[page], fontSize = 18.sp, fontWeight = if (Math.abs(pageOffset) < 0.5) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().alpha(alpha))
            }
        }
    }
}

@Composable
fun WheelTimePickerDialog(initialTime: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = initialTime.split(":")
    val h = parts.getOrElse(0) { "09" }.toIntOrNull() ?: 9
    val m = parts.getOrElse(1) { "00" }.toIntOrNull() ?: 0
    var sH by remember { mutableIntStateOf(h) }
    var sM by remember { mutableIntStateOf(m) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = { WheelTimePicker(h, m, { hh, mm -> sH = hh; sM = mm }) },
        confirmButton = { TextButton(onClick = { onConfirm(String.format("%02d:%02d", sH, sM)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

@Composable
fun WheelReminderPickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val options = NotificationScheduler.REMINDER_OPTIONS
    val defaultIndex = options.indexOfFirst { it.first == initialMinutes }.takeIf { it != -1 }
        ?: options.indexOfFirst { it.first == 30 }.takeIf { it != -1 } ?: 4

    var selectedIndex by remember { mutableIntStateOf(defaultIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加提醒", style = MaterialTheme.typography.titleMedium) },
        text = {
            WheelPicker(
                items = options.map { it.second },
                initialIndex = defaultIndex,
                onSelectionChanged = { selectedIndex = it }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(options[selectedIndex].first)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

@Composable
fun WheelTimePicker(initialHour: Int, initialMinute: Int, onTimeChanged: (Int, Int) -> Unit) {
    val hours = (0..23).toList(); val minutes = (0..59).toList()
    var cH by remember { mutableIntStateOf(initialHour) }; var cM by remember { mutableIntStateOf(initialMinute) }
    LaunchedEffect(cH, cM) { onTimeChanged(cH, cM) }
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        WheelPicker(hours.map{String.format("%02d",it)}, initialHour, Modifier.weight(1f)) { cH = hours[it] }
        Text(":", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically))
        WheelPicker(minutes.map{String.format("%02d",it)}, initialMinute, Modifier.weight(1f)) { cM = minutes[it] }
    }
}