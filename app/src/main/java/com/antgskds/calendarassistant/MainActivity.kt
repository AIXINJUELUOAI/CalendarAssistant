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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.antgskds.calendarassistant.service.TextAccessibilityService
import com.antgskds.calendarassistant.util.NotificationScheduler
import com.antgskds.calendarassistant.ui.theme.CalendarAssistantTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
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

// 辅助：创建系统闹钟 (修复日期 Bug)
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

            // 【核心修复】如果日期不是今天，强制指定星期几
            if (date != LocalDate.now()) {
                val dayOfWeek = date.dayOfWeek.value
                val calendarDay = when (dayOfWeek) {
                    7 -> java.util.Calendar.SUNDAY // 7 -> 1
                    else -> dayOfWeek + 1          // 1 -> 2, etc.
                }
                putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(calendarDay))
            }

            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        // Toast 已移除，避免与系统闹钟 Toast 重叠
    } catch (e: Exception) {
        Toast.makeText(context, "设置闹钟失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// --- Activity 主入口 ---

class MainActivity : ComponentActivity() {

    private val myEvents = mutableStateListOf<MyEvent>()
    private lateinit var eventStore: EventJsonStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        eventStore = EventJsonStore(this)
        loadDataFromFile()

        setContent {
            CalendarAssistantTheme {
                MainScreen(
                    events = myEvents,
                    onDataChanged = { saveData() },
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
        val savedData = eventStore.loadEvents()
        myEvents.clear()
        myEvents.addAll(savedData)
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
    onDataChanged: () -> Unit,
    eventStore: EventJsonStore,
    onImportEvents: (List<MyEvent>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<MyEvent?>(null) }
    var revealedEventId by remember { mutableStateOf<String?>(null) }

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

    val tabs = listOf("今日", "全部")
    val icons = listOf(Icons.Default.Today, Icons.Default.FormatListBulleted)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(48.dp))
                Text("设置", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
                Box(Modifier.padding(16.dp)) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        ModelSettingsSidebar(snackbarHostState)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        // 偏好设置
                        Text("偏好设置", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        PreferenceSettings(snackbarHostState)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        Text("数据备份", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        OutlinedButton(onClick = { exportEvents() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导出日程")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导入日程")
                        }
                        Spacer(Modifier.height(24.dp))
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
                FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, null) }
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
                        0 -> TodayPageView(events.filter { it.eventType == "event" }, revealedEventId, { revealedEventId = it }, {
                            NotificationScheduler.cancelReminders(context, it)
                            events.remove(it)
                            onDataChanged()
                        }, { e -> val idx = events.indexOf(e); if(idx != -1) { events[idx] = e.copy(isImportant = !e.isImportant); onDataChanged() } }, { editingEvent = it })
                        1 -> AllEventsPageView(events, revealedEventId, { revealedEventId = it }, {
                            NotificationScheduler.cancelReminders(context, it)
                            events.remove(it)
                            onDataChanged()
                        }, { e -> val idx = events.indexOf(e); if(idx != -1) { events[idx] = e.copy(isImportant = !e.isImportant); onDataChanged() } }, { editingEvent = it })
                    }
                }
            }
        }
    }

    if (showAddDialog || editingEvent != null) {
        ManualAddEventDialog(
            eventToEdit = editingEvent,
            currentEventsCount = events.size,
            onDismiss = { showAddDialog = false; editingEvent = null },
            onConfirm = { newEvent ->
                if (editingEvent != null) {
                    val index = events.indexOfFirst { it.id == editingEvent!!.id }
                    if (index != -1) {
                        NotificationScheduler.cancelReminders(context, events[index])
                        events[index] = newEvent
                    }
                } else {
                    events.add(newEvent)
                    // 手动创建日程，如果设置开启，自动创建系统闹钟
                    val settings = MyApplication.getInstance().getSettings()
                    if (settings.autoCreateAlarm && newEvent.eventType == "event") {
                        createSystemAlarmHelper(context, newEvent.title, newEvent.startTime, newEvent.startDate)
                    }
                }
                onDataChanged()
                showAddDialog = false
                editingEvent = null
            }
        )
    }
}

@Composable
fun TodayPageView(
    events: List<MyEvent>,
    revealedId: String?,
    onRevealStateChange: (String?) -> Unit,
    onDelete: (MyEvent) -> Unit,
    onImportant: (MyEvent) -> Unit,
    onEdit: (MyEvent) -> Unit
) {
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val context = LocalContext.current
    val isToday = selectedDate == LocalDate.now()
    val settings = remember { MyApplication.getInstance().getSettings() }

    val currentEvents = events.filter { it.startDate <= selectedDate && it.endDate >= selectedDate }

    val showTomorrow = settings.showTomorrowEvents
    val tomorrowEvents = if (isToday && showTomorrow) {
        val tomorrow = LocalDate.now().plusDays(1)
        events.filter { it.startDate == tomorrow }
    } else {
        emptyList()
    }

    var serviceEnabled by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(true) }

    LifecycleResumeEffect(context) {
        serviceEnabled = checkAccessibilityEnabled(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationEnabled = notificationManager.areNotificationsEnabled()
        onPauseOrDispose { }
    }

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
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (totalDrag < -50) selectedDate = selectedDate.plusDays(1)
                                else if (totalDrag > 50) selectedDate = selectedDate.minusDays(1)
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
                                onClick = { selectedDate = LocalDate.now() }
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
                                onClick = { selectedDate = LocalDate.now() }
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

@Composable
fun AllEventsPageView(events: List<MyEvent>, revealedId: String?, onRevealStateChange: (String?) -> Unit, onDelete: (MyEvent) -> Unit, onImportant: (MyEvent) -> Unit, onEdit: (MyEvent) -> Unit) {
    var selectedCategory by remember { mutableIntStateOf(0) }
    val filteredEvents = events.filter { event ->
        if (selectedCategory == 0) event.eventType == "event"
        else event.eventType != "event"
    }.sortedByDescending { it.startDate }

    Column(modifier = Modifier.fillMaxSize()) {
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
                        Text("暂无记录", color = Color.Gray)
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
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(if (event.isImportant) 8.dp else 5.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(event.color))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (event.isImportant) Icon(Icons.Default.Star, null, Modifier.size(16.dp).padding(start = 4.dp), tint = Color(0xFFFFC107))
                        }
                        if (event.eventType == "temp" && event.description.isNotBlank()) {
                            Text(text = "号码: ${event.description}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(text = "${event.startTime} - ${event.endTime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsSidebar(snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val settings = MyApplication.getInstance().getSettings()

    val currentUrl = settings.modelUrl
    val currentModel = settings.modelName

    val initialProvider = when {
        currentUrl.contains("deepseek") -> "DeepSeek"
        currentUrl.contains("openai") -> "OpenAI"
        currentUrl.contains("googleapis") -> "Gemini"
        currentUrl.isBlank() && currentModel.isBlank() -> "DeepSeek"
        else -> "Custom"
    }

    var selectedProvider by remember { mutableStateOf(initialProvider) }
    var expanded by remember { mutableStateOf(false) }

    var modelUrl by remember { mutableStateOf(settings.modelUrl) }
    var modelName by remember { mutableStateOf(settings.modelName) }
    var modelKey by remember { mutableStateOf(settings.modelKey) }

    val providers = listOf("DeepSeek", "OpenAI", "Gemini", "Custom")

    LaunchedEffect(selectedProvider) {
        when (selectedProvider) {
            "DeepSeek" -> {
                modelUrl = "https://api.deepseek.com/chat/completions"
                modelName = "deepseek-chat"
            }
            "OpenAI" -> {
                modelUrl = "https://api.openai.com/v1/chat/completions"
                modelName = "gpt-4o-mini"
            }
            "Gemini" -> {
                modelUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
                modelName = "gemini-1.5-flash"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI 模型配置", fontWeight = FontWeight.Bold)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedProvider,
                onValueChange = {},
                readOnly = true,
                label = { Text("服务提供商") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                providers.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = {
                            selectedProvider = item
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = modelKey,
            onValueChange = { modelKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
        )

        if (selectedProvider == "Custom") {
            OutlinedTextField(
                value = modelUrl,
                onValueChange = { modelUrl = it },
                label = { Text("API 地址 (URL)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("模型名称 (Model)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
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
            Text("保存 AI 配置")
        }
    }
}

@Composable
fun PreferenceSettings(snackbarHostState: SnackbarHostState) {
    val settings = MyApplication.getInstance().getSettings()
    var autoAlarm by remember { mutableStateOf(settings.autoCreateAlarm) }
    var showTomorrow by remember { mutableStateOf(settings.showTomorrowEvents) }

    // 【修改点】：删除了关于胶囊通知的开关变量和 UI

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("创建系统闹钟", style = MaterialTheme.typography.bodyLarge)
                Text("创建日程后自动创建闹钟", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(
                checked = autoAlarm,
                onCheckedChange = {
                    autoAlarm = it
                    settings.autoCreateAlarm = it
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("主页显示明日日程", style = MaterialTheme.typography.bodyLarge)
                Text("在今日日程列表底部预览", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(
                checked = showTomorrow,
                onCheckedChange = {
                    showTomorrow = it
                    settings.showTomorrowEvents = it
                }
            )
        }
    }
}

// -----------------------------------------------------------
// 滚轮样式的日期/时间选择器
// -----------------------------------------------------------
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
fun WheelTimePickerDialog(initialTime: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = initialTime.split(":"); val h = parts.getOrElse(0){"09"}.toIntOrNull()?:9; val m = parts.getOrElse(1){"00"}.toIntOrNull()?:0
    var sH by remember { mutableIntStateOf(h) }; var sM by remember { mutableIntStateOf(m) }
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

// ... (Utils like checkAccessibilityEnabled, serializers) ...
fun checkAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == TextAccessibilityService::class.java.name
    }
}

object LocalDateSerializer : KSerializer<LocalDate> {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.format(formatter))
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString(), formatter)
}

object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Color", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Color) = encoder.encodeInt(value.toArgb())
    override fun deserialize(decoder: Decoder): Color = Color(decoder.decodeInt())
}

@Serializable
data class MyEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate,
    val startTime: String,
    val endTime: String,
    val location: String,
    val description: String,
    @Serializable(with = ColorSerializer::class)
    val color: Color,
    val isImportant: Boolean = false,
    val sourceImagePath: String? = null,
    val reminders: List<Int> = emptyList(),
    val eventType: String = "event"
)