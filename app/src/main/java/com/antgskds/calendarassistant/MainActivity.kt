package com.antgskds.calendarassistant

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.antgskds.calendarassistant.llm.RecognitionProcessor
import com.antgskds.calendarassistant.model.Course
import com.antgskds.calendarassistant.model.MyEvent
import com.antgskds.calendarassistant.model.MySettings
import com.antgskds.calendarassistant.service.TextAccessibilityService
import com.antgskds.calendarassistant.ui.CourseManagementDialog
import com.antgskds.calendarassistant.ui.ModelSettingsGroup
import com.antgskds.calendarassistant.ui.PreferenceSettingsGroup
import com.antgskds.calendarassistant.ui.ScheduleSettingsSidebar
import com.antgskds.calendarassistant.ui.ScheduleView
import com.antgskds.calendarassistant.ui.TimeTableEditorDialog
import com.antgskds.calendarassistant.ui.WheelDatePickerDialog
import com.antgskds.calendarassistant.ui.WheelReminderPickerDialog
import com.antgskds.calendarassistant.ui.WheelTimePickerDialog
import com.antgskds.calendarassistant.ui.theme.CalendarAssistantTheme
import com.antgskds.calendarassistant.util.NotificationScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt


// --- 工具函数与常量 ---

val EventColors = listOf(
    Color(0xFF91A3B0), Color(0xFFB4C3A1), Color(0xFFD1B29E),
    Color(0xFF968D8D), Color(0xFFBCCAD6), Color(0xFFCFD1D3),
    Color(0xFFA2B5BB), Color(0xFFE2C4C4)
)

fun getNextColor(currentListSize: Int): Color = EventColors[currentListSize % EventColors.size]

fun getLunarDate(date: LocalDate): String {
    val chineseCalendar = android.icu.util.ChineseCalendar()
    chineseCalendar.set(android.icu.util.Calendar.YEAR, date.year)
    chineseCalendar.set(android.icu.util.Calendar.MONTH, date.monthValue - 1)
    chineseCalendar.set(android.icu.util.Calendar.DAY_OF_MONTH, date.dayOfMonth)
    val month = chineseCalendar.get(android.icu.util.Calendar.MONTH) + 1
    val day = chineseCalendar.get(android.icu.util.Calendar.DAY_OF_MONTH)
    val monthNames = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    val dayNames = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )
    return "${monthNames.getOrElse(month - 1) { "" }}月${dayNames.getOrElse(day - 1) { "" }}"
}

// 辅助：判断日程是否已过期
fun isEventExpired(event: MyEvent): Boolean {
    return try {
        val timeParts = event.endTime.split(":")
        val hour = timeParts.getOrElse(0) { "23" }.toIntOrNull() ?: 23
        val minute = timeParts.getOrElse(1) { "59" }.toIntOrNull() ?: 59

        val endDateTime = LocalDateTime.of(event.endDate, LocalTime.of(hour, minute))
        endDateTime.isBefore(LocalDateTime.now())
    } catch (e: Exception) {
        false
    }
}

// 辅助：创建系统闹钟
fun createSystemAlarmHelper(context: Context, title: String, timeStr: String, date: LocalDate) {
    try {
        val parts = timeStr.split(":")
        val hour = parts.getOrElse(0) { "09" }.toInt()
        val minute = parts.getOrElse(1) { "00" }.toInt()

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)

            if (date != LocalDate.now()) {
                val dayOfWeek = date.dayOfWeek.value // 1-7
                val calendarDay = when (dayOfWeek) {
                    7 -> java.util.Calendar.SUNDAY // 7 -> 1
                    else -> dayOfWeek + 1          // 1 -> 2
                }
                putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(calendarDay))
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法设置闹钟: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// --- Activity 主入口 ---

class MainActivity : ComponentActivity() {

    private val myEvents = mutableStateListOf<MyEvent>()
    private val courses = mutableStateListOf<Course>()
    private lateinit var eventStore: EventJsonStore
    private lateinit var courseStore: CourseJsonStore
    private lateinit var settings: MySettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = MyApplication.getInstance().getSettings()
        eventStore = EventJsonStore(this)
        courseStore = CourseJsonStore(this)
        loadDataFromFile()

        setContent {
            CalendarAssistantTheme {
                MainScreen(
                    events = myEvents,
                    courses = courses,
                    settings = settings,
                    onDataChanged = { saveData() },
                    onCoursesChanged = { saveCourses() },
                    eventStore = eventStore,
                    onImportEvents = { importedList ->
                        val existingIds = myEvents.map { it.id }.toSet()
                        val newEvents = importedList.filter { it.id !in existingIds }
                        myEvents.addAll(newEvents)
                        saveData()
                        Toast.makeText(this, "成功导入 ${newEvents.size} 条日程", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadDataFromFile()
    }

    private fun loadDataFromFile() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = eventStore.loadEvents()
            val courseList = courseStore.loadCourses()
            withContext(Dispatchers.Main) {
                myEvents.clear()
                myEvents.addAll(list)
                courses.clear()
                courses.addAll(courseList)
            }
        }
    }

    private fun saveData() {
        val currentContext = this
        Thread {
            eventStore.saveEvents(myEvents.toList())
            myEvents.forEach { event ->
                NotificationScheduler.cancelReminders(currentContext, event)
                NotificationScheduler.scheduleReminders(currentContext, event)
            }
        }.start()
    }

    private fun saveCourses() {
        Thread {
            courseStore.saveCourses(courses.toList())
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

// --- UI 组件 ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    events: androidx.compose.runtime.snapshots.SnapshotStateList<MyEvent>,
    courses: androidx.compose.runtime.snapshots.SnapshotStateList<Course>,
    settings: MySettings,
    onDataChanged: () -> Unit,
    onCoursesChanged: () -> Unit,
    eventStore: EventJsonStore,
    onImportEvents: (List<MyEvent>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // --- UI 状态 ---
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showCourseManager by remember { mutableStateOf(false) }
    var showTimeTableEditor by remember { mutableStateOf(false) }

    // 【新增】AI 输入弹窗状态
    var showAiInputDialog by remember { mutableStateOf(false) }

    var editingEvent by remember { mutableStateOf<MyEvent?>(null) }
    var revealedEventId by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    // --- 设置状态缓存 ---
    var semesterStartDate by remember { mutableStateOf(if (settings.semesterStartDate.isNotBlank()) LocalDate.parse(settings.semesterStartDate) else null) }
    var totalWeeks by remember { mutableIntStateOf(settings.totalWeeks) }
    // 强制刷新 Key
    var timeTableJsonTrigger by remember { mutableStateOf(settings.timeTableJson) }

    // --- 侧边栏折叠状态 ---
    var expandedAI by remember { mutableStateOf(false) }
    var expandedSchedule by remember { mutableStateOf(false) }
    var expandedPrefs by remember { mutableStateOf(false) }

    // --- 数据计算 ---
    val dailyCourses = remember(courses.toList(), selectedDate, semesterStartDate, totalWeeks, timeTableJsonTrigger) {
        CourseManager.getDailyCourses(
            targetDate = selectedDate,
            allCourses = courses,
            settings = settings
        )
    }

    val currentEvents = remember(events.toList(), selectedDate, dailyCourses) {
        val dateEvents = events.filter {
            it.startDate == selectedDate && it.eventType != "temp"
        }
        (dateEvents + dailyCourses).sortedBy { it.startTime }
    }

    val tomorrowEvents = remember(events.toList(), selectedDate) {
        if (selectedDate == LocalDate.now() && settings.showTomorrowEvents) {
            val tomorrow = LocalDate.now().plusDays(1)
            events.filter { it.startDate == tomorrow && it.eventType != "temp" }
        } else {
            emptyList()
        }
    }

    // --- 日程导入/导出逻辑 ---
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            val jsonString = BufferedReader(InputStreamReader(inputStream)).readText()
                            val imported = eventStore.jsonStringToEvents(jsonString)
                            withContext(Dispatchers.Main) {
                                if (imported.isNotEmpty()) {
                                    onImportEvents(imported)
                                    drawerState.close()
                                } else {
                                    Toast.makeText(context, "文件解析失败或为空", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    )

    fun exportEvents() {
        scope.launch(Dispatchers.IO) {
            try {
                val jsonString = eventStore.eventsToJsonString(events.toList())
                val fileName = "CalendarBackup_${System.currentTimeMillis()}.json"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(jsonString.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已导出到下载目录: $fileName", Toast.LENGTH_LONG).show()
                        drawerState.close()
                    }
                } ?: run {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "无法创建导出文件", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    // --- 课程导入/导出逻辑 ---
    val importCoursesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            val jsonString = BufferedReader(InputStreamReader(inputStream)).readText()
                            val jsonParser = Json { ignoreUnknownKeys = true }
                            val importedCourses = jsonParser.decodeFromString<List<Course>>(jsonString)

                            withContext(Dispatchers.Main) {
                                if (importedCourses.isNotEmpty()) {
                                    courses.clear()
                                    courses.addAll(importedCourses)
                                    onCoursesChanged()
                                    Toast.makeText(context, "成功导入 ${importedCourses.size} 门课程", Toast.LENGTH_SHORT).show()
                                    drawerState.close()
                                } else {
                                    Toast.makeText(context, "文件解析为空", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "导入课程失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    )

    fun exportCourses() {
        scope.launch(Dispatchers.IO) {
            try {
                val jsonParser = Json { prettyPrint = true }
                val jsonString = jsonParser.encodeToString<List<Course>>(courses.toList())

                val fileName = "CoursesBackup_${System.currentTimeMillis()}.json"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(jsonString.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "课表已导出到下载目录", Toast.LENGTH_LONG).show()
                        drawerState.close()
                    }
                } ?: run {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "无法创建导出文件", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    val tabs = listOf("今日", "全部")
    val icons = listOf(Icons.Default.Today, Icons.Default.FormatListBulleted)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(windowInsets = WindowInsets(0, 0, 0, 0)) {
                Spacer(Modifier.height(48.dp))
                Text("设置", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        ModelSettingsGroup(snackbarHostState, expandedAI) { expandedAI = it }

                        ScheduleSettingsSidebar(
                            expanded = expandedSchedule,
                            onExpandedChange = { expandedSchedule = it },
                            semesterStartDate = semesterStartDate,
                            onSemesterStartDateChange = {
                                semesterStartDate = it
                                settings.semesterStartDate = it.toString()
                            },
                            totalWeeks = totalWeeks,
                            onTotalWeeksChange = {
                                totalWeeks = it
                                settings.totalWeeks = it
                            },
                            onManageCourses = { showCourseManager = true },
                            onEditTimeTable = { showTimeTableEditor = true },
                            onExportCourses = { exportCourses() },
                            onImportCourses = { importCoursesLauncher.launch(arrayOf("application/json")) }
                        )

                        PreferenceSettingsGroup(snackbarHostState, expandedPrefs) { expandedPrefs = it }

                        Spacer(Modifier.height(16.dp))
                        Text("数据备份", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        OutlinedButton(onClick = { exportEvents() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导出所有日程")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导入日程")
                        }
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (selectedTab == 0) "日程视图" else "全部列表") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Settings, null) }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                // 【修改】双按钮 FAB
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { showAiInputDialog = true },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Icon(Icons.Default.AutoAwesome, "AI 创建")
                    }

                    FloatingActionButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, null)
                    }
                }
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(icons[index], null) },
                            label = { Text(title) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { revealedEventId = null }) }
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    label = "TabChange",
                    modifier = Modifier.fillMaxSize()
                ) { targetTab ->
                    when (targetTab) {
                        0 -> {
                            // --- 下拉查看课表交互逻辑 ---
                            val offsetY = remember { Animatable(0f) }
                            val maxOffsetPx = with(LocalDensity.current) { 600.dp.toPx() }

                            val nestedScrollConnection = remember {
                                object : NestedScrollConnection {
                                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                        if (offsetY.value > 0 && available.y < 0) {
                                            val newOffset = (offsetY.value + available.y).coerceAtLeast(0f)
                                            val consumed = offsetY.value - newOffset
                                            scope.launch { offsetY.snapTo(newOffset) }
                                            return Offset(0f, -consumed)
                                        }
                                        return Offset.Zero
                                    }

                                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                        if (available.y > 0) {
                                            val newOffset = (offsetY.value + available.y).coerceAtMost(maxOffsetPx)
                                            scope.launch { offsetY.snapTo(newOffset) }
                                            return Offset(0f, available.y)
                                        }
                                        return Offset.Zero
                                    }

                                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                        val target = if (available.y > 1000f) {
                                            maxOffsetPx
                                        } else if (available.y < -1000f) {
                                            0f
                                        } else {
                                            if (offsetY.value > maxOffsetPx / 3f) maxOffsetPx else 0f
                                        }
                                        scope.launch { offsetY.animateTo(target) }
                                        return super.onPostFling(consumed, available)
                                    }
                                }
                            }

                            BackHandler(enabled = offsetY.value > 0f) {
                                scope.launch { offsetY.animateTo(0f) }
                            }

                            Box(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
                                val progress = (offsetY.value / maxOffsetPx).coerceIn(0f, 1f)

                                ScheduleView(
                                    courses = courses,
                                    semesterStartDateStr = settings.semesterStartDate,
                                    totalWeeks = totalWeeks,
                                    modifier = Modifier
                                        .matchParentSize()
                                        .draggable(
                                            state = rememberDraggableState { delta ->
                                                if (offsetY.value > 0) {
                                                    val newOffset = (offsetY.value + delta).coerceIn(0f, maxOffsetPx)
                                                    scope.launch { offsetY.snapTo(newOffset) }
                                                }
                                            },
                                            orientation = Orientation.Vertical,
                                            onDragStopped = { velocity ->
                                                val target = if (velocity > 1000f) maxOffsetPx
                                                else if (velocity < -1000f) 0f
                                                else if (offsetY.value > maxOffsetPx / 3f) maxOffsetPx else 0f
                                                scope.launch { offsetY.animateTo(target) }
                                            }
                                        )
                                        .graphicsLayer {
                                            alpha = progress
                                            scaleX = 0.9f + (0.1f * progress)
                                            scaleY = 0.9f + (0.1f * progress)
                                        }
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .offset { IntOffset(0, offsetY.value.roundToInt()) }
                                        .graphicsLayer { alpha = 1f - progress }
                                ) {
                                    TodayPageView(
                                        courses = courses,
                                        semesterStartDateStr = settings.semesterStartDate,
                                        totalWeeks = totalWeeks,
                                        currentEvents = currentEvents,
                                        tomorrowEvents = tomorrowEvents,
                                        selectedDate = selectedDate,
                                        onSelectedDateChange = { selectedDate = it },
                                        revealedId = revealedEventId,
                                        onRevealStateChange = { revealedEventId = it },
                                        onDelete = {
                                            NotificationScheduler.cancelReminders(context, it)
                                            events.remove(it)
                                            onDataChanged()
                                        },
                                        onImportant = { e ->
                                            val idx = events.indexOf(e)
                                            if (idx != -1) {
                                                events[idx] = e.copy(isImportant = !e.isImportant)
                                                onDataChanged()
                                            }
                                        },
                                        onEdit = { editingEvent = it }
                                    )
                                }
                            }
                        }

                        1 -> AllEventsPageView(
                            events, revealedEventId,
                            { revealedEventId = it },
                            {
                                NotificationScheduler.cancelReminders(context, it)
                                events.remove(it)
                                onDataChanged()
                            },
                            { e ->
                                val idx = events.indexOf(e)
                                if (idx != -1) {
                                    events[idx] = e.copy(isImportant = !e.isImportant)
                                    onDataChanged()
                                }
                            },
                            { editingEvent = it }
                        )
                    }
                }
            }
        }
    }

    // --- 弹窗逻辑 ---

    if (showCourseManager) {
        CourseManagementDialog(
            courses = courses,
            onDismiss = { showCourseManager = false },
            onAddCourse = { courses.add(it); onCoursesChanged() },
            onDeleteCourse = { courses.remove(it); onCoursesChanged() },
            onEditCourse = { newCourse ->
                val idx = courses.indexOfFirst { c -> c.id == newCourse.id }
                if (idx != -1) courses[idx] = newCourse
                onCoursesChanged()
            }
        )
    }

    if (showTimeTableEditor) {
        TimeTableEditorDialog(
            initialJson = settings.timeTableJson,
            onDismiss = { showTimeTableEditor = false },
            onConfirm = { newJson ->
                settings.timeTableJson = newJson
                timeTableJsonTrigger = newJson
                showTimeTableEditor = false
                Toast.makeText(context, "作息时间表已更新", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showAddDialog || editingEvent != null) {
        ManualAddEventDialog(
            eventToEdit = editingEvent,
            currentEventsCount = events.size,
            onDismiss = { showAddDialog = false; editingEvent = null },
            onConfirm = { newEvent ->
                // 【修复逻辑开始】
                // 1. 尝试查找是否存在旧日程
                val existingIndex = if (editingEvent != null) {
                    events.indexOfFirst { it.id == editingEvent!!.id }
                } else {
                    -1
                }

                if (existingIndex != -1) {
                    // 情况 A：这是在编辑一个【已存在】的日程 -> 执行更新
                    NotificationScheduler.cancelReminders(context, events[existingIndex])
                    events[existingIndex] = newEvent
                } else {
                    // 情况 B：这是【手动添加】(editingEvent为null)
                    //        或者【AI生成】(editingEvent不为null，但在列表中找不到ID)
                    //        -> 执行新增
                    events.add(newEvent)

                    val appSettings = MyApplication.getInstance().getSettings()
                    // 只有未来的普通日程才自动加闹钟
                    if (appSettings.autoCreateAlarm && newEvent.eventType == "event") {
                        // 简单的防呆检查，防止 AI 把时间设到过去导致闹钟崩溃
                        val isFuture = try {
                            val dt = LocalDateTime.parse("${newEvent.startDate} ${newEvent.startTime}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            dt.isAfter(LocalDateTime.now())
                        } catch (e: Exception) { true }

                        if (isFuture) {
                            createSystemAlarmHelper(context, newEvent.title, newEvent.startTime, newEvent.startDate)
                        }
                    }
                }
                // 【修复逻辑结束】

                onDataChanged()
                showAddDialog = false
                editingEvent = null
            }
        )
    }

    // 【新增】AI 输入弹窗逻辑
    if (showAiInputDialog) {
        AiCreationDialog(
            onDismiss = { showAiInputDialog = false },
            onConfirm = { userText ->
                showAiInputDialog = false
                Toast.makeText(context, "正在分析语义...", Toast.LENGTH_SHORT).show()

                scope.launch(Dispatchers.IO) {
                    val result = RecognitionProcessor.parseUserText(userText)

                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            val newEvent = MyEvent(
                                id = UUID.randomUUID().toString(),
                                title = result.title,
                                startDate = LocalDate.parse(result.startTime.substring(0, 10)),
                                startTime = result.startTime.substring(11, 16),
                                endDate = if(result.endTime.isNotBlank()) LocalDate.parse(result.endTime.substring(0, 10)) else LocalDate.now(),
                                endTime = if(result.endTime.isNotBlank()) result.endTime.substring(11, 16) else "",
                                location = result.location,
                                description = result.description,
                                eventType = if(result.type == "pickup") "temp" else "event",
                                color = getNextColor(events.size)
                            )

                            // 自动填充到编辑弹窗
                            editingEvent = newEvent
                        } else {
                            Toast.makeText(context, "AI 没听懂，请重试或手动添加", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun AiCreationDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("AI 智能创建")
            }
        },
        text = {
            Column {
                Text("请输入自然语言，例如：\n“明天下午3点在会议室开会”\n“下周五晚上8点去取快递 5566”",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    placeholder = { Text("在此输入...") },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("解析生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@Composable
fun TodayPageView(
    courses: List<Course>,              // 【新增参数】
    semesterStartDateStr: String,       // 【新增参数】
    totalWeeks: Int,                    // 【新增参数】
    currentEvents: List<MyEvent>,
    tomorrowEvents: List<MyEvent>,
    selectedDate: LocalDate,
    onSelectedDateChange: (LocalDate) -> Unit,
    revealedId: String?,
    onRevealStateChange: (String?) -> Unit,
    onDelete: (MyEvent) -> Unit,
    onImportant: (MyEvent) -> Unit,
    onEdit: (MyEvent) -> Unit
) {
    val context = LocalContext.current
    val isToday = selectedDate == LocalDate.now()

    var serviceEnabled by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(true) }

    LifecycleResumeEffect(context) {
        serviceEnabled = checkAccessibilityEnabled(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationEnabled = notificationManager.areNotificationsEnabled()
        onPauseOrDispose { }
    }

    // --- Schedule View Gesture Logic ---
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val maxOffsetPx = with(LocalDensity.current) { 600.dp.toPx() }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (offsetY.value > 0 && available.y < 0) {
                    val newOffset = (offsetY.value + available.y).coerceAtLeast(0f)
                    val consumed = offsetY.value - newOffset
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) {
                    val newOffset = (offsetY.value + available.y).coerceAtMost(maxOffsetPx)
                    scope.launch { offsetY.snapTo(newOffset) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val target = if (available.y > 1000f) {
                    maxOffsetPx // Hard swipe down -> Open
                } else if (available.y < -1000f) {
                    0f // Hard swipe up -> Close
                } else {
                    if (offsetY.value > maxOffsetPx / 3f) maxOffsetPx else 0f // Snap to closest
                }
                scope.launch { offsetY.animateTo(target) }
                return super.onPostFling(consumed, available)
            }
        }
    }

    BackHandler(enabled = offsetY.value > 0f) {
        scope.launch { offsetY.animateTo(0f) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        val progress = (offsetY.value / maxOffsetPx).coerceIn(0f, 1f)

        // Background: Schedule View
        // 【核心修改】：传入课程数据参数
        ScheduleView(
            courses = courses,
            semesterStartDateStr = semesterStartDateStr,
            totalWeeks = totalWeeks,
            modifier = Modifier
                .matchParentSize()
                .draggable(
                    state = rememberDraggableState { delta ->
                        if (offsetY.value > 0) {
                            val newOffset = (offsetY.value + delta).coerceIn(0f, maxOffsetPx)
                            scope.launch { offsetY.snapTo(newOffset) }
                        }
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val target = if (velocity > 1000f) {
                            maxOffsetPx
                        } else if (velocity < -1000f) {
                            0f
                        } else {
                            if (offsetY.value > maxOffsetPx / 3f) maxOffsetPx else 0f
                        }
                        scope.launch { offsetY.animateTo(target) }
                    }
                )
                .graphicsLayer {
                    alpha = progress
                    scaleX = 0.9f + (0.1f * progress)
                    scaleY = 0.9f + (0.1f * progress)
                }
        )

        // Foreground: Calendar List
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer {
                    alpha = 1f - progress
                }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                            .aspectRatio(0.95f)
                            .pointerInput(selectedDate) {
                                var totalDrag = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (totalDrag < -50) onSelectedDateChange(selectedDate.plusDays(1))
                                        else if (totalDrag > 50) onSelectedDateChange(selectedDate.minusDays(1))
                                        totalDrag = 0f
                                    },
                                    onHorizontalDrag = { change, dragAmount -> change.consume(); totalDrag += dragAmount }
                                )
                            },
                        shape = RoundedCornerShape(4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.2f)
                                    .background(if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceDim)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSelectedDateChange(LocalDate.now()) }
                                    )
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(0.8f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                    Text(text = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE), style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.width(8.dp))
                                    Text(text = getLunarDate(selectedDate), style = MaterialTheme.typography.titleLarge)
                                }
                                Text(
                                    text = selectedDate.dayOfMonth.toString(),
                                    fontSize = 140.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 140.sp,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSelectedDateChange(LocalDate.now()) }
                                    )
                                )
                                Text(text = "${selectedDate.year}年${selectedDate.monthValue}月", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                            }
                        }
                    }
                }

                if (!serviceEnabled) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(12.dp))
                                Text("无障碍服务未开启 (点击开启)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (!notificationEnabled) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                            onClick = {
                                val intent = Intent().apply {
                                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.NotificationsOff, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(12.dp))
                                Text("通知权限未开启 (点击开启)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (isToday) "今日安排" else "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 安排",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                if (currentEvents.isEmpty()) {
                    item { Text("暂无日程", modifier = Modifier.padding(vertical = 40.dp), color = Color.LightGray) }
                } else {
                    items(currentEvents, key = { it.id }) { event ->
                        SwipeableEventItem(event, revealedId == event.id, { onRevealStateChange(event.id) }, { onRevealStateChange(null) }, onDelete, onImportant, onEdit)
                    }
                }

                if (tomorrowEvents.isNotEmpty()) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "明日安排",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    items(tomorrowEvents, key = { it.id }) { event ->
                        SwipeableEventItem(event, revealedId == event.id, { onRevealStateChange(event.id) }, { onRevealStateChange(null) }, onDelete, onImportant, onEdit)
                    }
                }
            }
        }
    }
}

@Composable
fun AllEventsPageView(events: List<MyEvent>, revealedId: String?, onRevealStateChange: (String?) -> Unit, onDelete: (MyEvent) -> Unit, onImportant: (MyEvent) -> Unit, onEdit: (MyEvent) -> Unit) {
    var selectedCategory by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredEvents = events.filter { event ->
        // 【修改】: 修复分类逻辑
        val categoryMatch = if (selectedCategory == 0) {
            // Tab 0 (日程事件): 显示普通日程(event) 和 课程(course)
            event.eventType != "temp"
        } else {
            // Tab 1 (临时事件): 只显示 temp
            event.eventType == "temp"
        }

        val searchMatch = if (searchQuery.isBlank()) true else {
            event.title.contains(searchQuery, ignoreCase = true) ||
                    event.description.contains(searchQuery, ignoreCase = true) ||
                    event.location.contains(searchQuery, ignoreCase = true)
        }
        categoryMatch && searchMatch
    }.sortedByDescending { it.startDate }

    Column(modifier = Modifier.fillMaxSize()) {

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索标题、备注或地点...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除")
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        )

        TabRow(selectedTabIndex = selectedCategory) {
            Tab(selected = selectedCategory == 0, onClick = { selectedCategory = 0 }, text = { Text("日程事件") })
            Tab(selected = selectedCategory == 1, onClick = { selectedCategory = 1 }, text = { Text("临时事件") })
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filteredEvents.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(if(searchQuery.isBlank()) "暂无记录" else "未找到相关日程", color = Color.Gray)
                    }
                }
            }
            items(filteredEvents, key = { it.id }) { event ->
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    if (selectedCategory == 0) {
                        Text(text = "${event.startDate} ~ ${event.endDate}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 4.dp, top = 8.dp))
                    } else {
                        Text(text = "创建于: ${event.startDate}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(bottom = 4.dp, top = 8.dp))
                    }
                    SwipeableEventItem(event, revealedId == event.id, { onRevealStateChange(event.id) }, { onRevealStateChange(null) }, onDelete, onImportant, onEdit)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableEventItem(
    event: MyEvent,
    isRevealed: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onDelete: (MyEvent) -> Unit,
    onImportant: (MyEvent) -> Unit,
    onEdit: (MyEvent) -> Unit
) {
    val actionMenuWidth = 160.dp
    val density = LocalDensity.current
    val actionMenuWidthPx = with(density) { actionMenuWidth.toPx() }

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val isExpired = remember(event) { isEventExpired(event) }

    LaunchedEffect(isRevealed) {
        if (isRevealed) {
            offsetX.animateTo(-actionMenuWidthPx)
        } else {
            offsetX.animateTo(0f)
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Row(
            modifier = Modifier
                .width(actionMenuWidth)
                .fillMaxHeight()
                .padding(end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwipeActionIcon(Icons.Outlined.Edit, Color(0xFF4CAF50)) {
                onCollapse()
                onEdit(event)
            }
            SwipeActionIcon(if (event.isImportant) Icons.Filled.Star else Icons.Outlined.StarOutline, Color(0xFFFFC107)) {
                onCollapse()
                onImportant(event)
            }
            SwipeActionIcon(Icons.Outlined.Delete, Color(0xFFF44336)) {
                onCollapse()
                onDelete(event)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -actionMenuWidthPx / 2) {
                                    offsetX.animateTo(-actionMenuWidthPx)
                                    onExpand()
                                } else {
                                    offsetX.animateTo(0f)
                                    onCollapse()
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-actionMenuWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
                .clickable {
                    if (isRevealed) onCollapse() else onEdit(event)
                },
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.alpha(if (isExpired) 0.6f else 1f)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(if (event.isImportant) 8.dp else 5.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if(isExpired) Color.LightGray else event.color)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                event.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textDecoration = if (isExpired) TextDecoration.LineThrough else null
                            )
                            if (event.isImportant) Icon(Icons.Default.Star, null, Modifier.size(16.dp).padding(start = 4.dp), tint = Color(0xFFFFC107))
                        }
                        if (event.eventType == "temp" && event.description.isNotBlank()) {
                            Text(text = "号码: ${event.description}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${event.startTime} - ${event.endTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isExpired) Color.Gray else MaterialTheme.colorScheme.primary
                                )
                                if (isExpired) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "(已过期)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        if (event.location.isNotBlank()) Text(text = event.location, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun SwipeActionIcon(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(modifier = Modifier.size(48.dp).padding(4.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.15f)).clickable { onClick() }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
}



@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ManualAddEventDialog(eventToEdit: MyEvent?, currentEventsCount: Int, onDismiss: () -> Unit, onConfirm: (MyEvent) -> Unit) {
    val now = LocalDateTime.now()
    val defaultEnd = now.plusHours(1)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    var title by remember { mutableStateOf(eventToEdit?.title ?: "") }

    var startDate by remember { mutableStateOf(eventToEdit?.startDate ?: now.toLocalDate()) }
    var endDate by remember { mutableStateOf(eventToEdit?.endDate ?: defaultEnd.toLocalDate()) }
    var startTime by remember { mutableStateOf(eventToEdit?.startTime ?: now.format(timeFormatter)) }
    var endTime by remember { mutableStateOf(eventToEdit?.endTime ?: defaultEnd.format(timeFormatter)) }

    var location by remember { mutableStateOf(eventToEdit?.location ?: "") }
    var desc by remember { mutableStateOf(eventToEdit?.description ?: "") }
    var eventType by remember { mutableStateOf(eventToEdit?.eventType ?: "event") }
    val reminders = remember { mutableStateListOf<Int>().apply { addAll(eventToEdit?.reminders ?: emptyList()) } }
    var sourceBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(eventToEdit) {
        val path = eventToEdit?.sourceImagePath
        if (!path.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        sourceBitmap = BitmapFactory.decodeFile(file.absolutePath)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (eventToEdit == null) "新增日程" else "编辑日程") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("类型:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = eventType == "event", onClick = { eventType = "event" }, label = { Text("日程") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = eventType == "temp", onClick = { eventType = "temp" }, label = { Text("取件/取餐") })
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("始", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { showStartDatePicker = true }, modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(startDate.toString(), fontSize = 13.sp) }
                    OutlinedButton(onClick = { showStartTimePicker = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(startTime, fontSize = 13.sp) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("终", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { showEndDatePicker = true }, modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(endDate.toString(), fontSize = 13.sp) }
                    OutlinedButton(onClick = { showEndTimePicker = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(endTime, fontSize = 13.sp) }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showReminderPicker = true }.padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Outlined.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加提醒", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                if (reminders.isNotEmpty()) {
                    FlowRow(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reminders.forEach { mins ->
                            val label = NotificationScheduler.REMINDER_OPTIONS.find { it.first == mins }?.second ?: "${mins}分钟前"
                            InputChip(selected = false, onClick = { reminders.remove(mins) }, label = { Text(label) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) })
                        }
                    }
                }
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text(if (eventType == "temp") "取件码/取餐码" else "备注") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                if (sourceBitmap != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("日程来源 (滑动查看)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Image(bitmap = sourceBitmap!!.asImageBitmap(), contentDescription = "Source", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    onConfirm(MyEvent(
                        id = eventToEdit?.id ?: UUID.randomUUID().toString(),
                        title = title,
                        startDate = startDate,
                        endDate = endDate,
                        startTime = startTime,
                        endTime = endTime,
                        location = location,
                        description = desc,
                        color = eventToEdit?.color ?: getNextColor(currentEventsCount),
                        isImportant = eventToEdit?.isImportant ?: false,
                        sourceImagePath = eventToEdit?.sourceImagePath,
                        reminders = reminders.toList(),
                        eventType = eventType
                    ))
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
    if (showStartDatePicker) WheelDatePickerDialog(startDate, { showStartDatePicker = false }) { startDate = it; showStartDatePicker = false }
    if (showEndDatePicker) WheelDatePickerDialog(endDate, { showEndDatePicker = false }) { endDate = it; showEndDatePicker = false }
    if (showStartTimePicker) WheelTimePickerDialog(startTime, { showStartTimePicker = false }) { startTime = it; showStartTimePicker = false }
    if (showEndTimePicker) WheelTimePickerDialog(endTime, { showEndTimePicker = false }) { endTime = it; showEndTimePicker = false }
    if (showReminderPicker) {
        WheelReminderPickerDialog(30, { showReminderPicker = false }) { if (!reminders.contains(it)) reminders.add(it) }
    }
}

fun checkAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == TextAccessibilityService::class.java.name
    }
}