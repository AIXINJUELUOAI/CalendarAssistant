package com.antgskds.calendarassistant.ui

import androidx.compose.material.icons.filled.DateRange
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antgskds.calendarassistant.model.Course
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CourseManagementDialog(
    courses: List<Course>, // 传入的是所有课程（包括临时的）
    onDismiss: () -> Unit,
    onAddCourse: (Course) -> Unit,
    onDeleteCourse: (Course) -> Unit,
    onEditCourse: (Course) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var courseToEdit by remember { mutableStateOf<Course?>(null) }

    // 【关键逻辑 1】只显示非临时的课程 (isTemp == false)
    val displayCourses = remember(courses.map { it.id }) { // 使用课程ID列表作为key，确保在课程内容变化时重组
        courses.filter { !it.isTemp }
    }

    if (showEditDialog) {
        CourseEditDialog(
            course = courseToEdit,
            onDismiss = { showEditDialog = false; courseToEdit = null },
            onConfirm = {
                if (courseToEdit != null) {
                    onEditCourse(it)
                } else {
                    onAddCourse(it)
                }
                showEditDialog = false
                courseToEdit = null
            }
        )
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.80f)
                    .heightIn(max = 600.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("课程管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (displayCourses.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("暂无课程", color = MaterialTheme.colorScheme.secondary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 12.dp)
                        ) {
                            items(displayCourses) { course ->
                                CourseItem(
                                    course = course,
                                    // 【关键逻辑 2】删除时，回调会传递这个主课程对象
                                    // 具体的“连坐”删除逻辑在 MainActivity 中实现
                                    onDelete = { onDeleteCourse(course) },
                                    onClick = { courseToEdit = course; showEditDialog = true }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { courseToEdit = null; showEditDialog = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加课程", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}


// CourseItem
@Composable
fun CourseItem(course: Course, onDelete: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(course.color)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "周${course.dayOfWeek} 第${course.startNode}-${course.endNode}节",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                val weekInfo = "第${course.startWeek}-${course.endWeek}周" +
                        if(course.weekType == 1) " (单)" else if(course.weekType == 2) " (双)" else ""
                val locInfo = if (course.location.isNotBlank()) " @${course.location}" else ""
                Text(
                    text = "$weekInfo$locInfo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}

// 普通编辑/添加弹窗 (保持不变，用于管理主课程)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditDialog(
    course: Course?,
    onDismiss: () -> Unit,
    onConfirm: (Course) -> Unit
) {
    var name by remember { mutableStateOf(course?.name ?: "") }
    var location by remember { mutableStateOf(course?.location ?: "") }
    var teacher by remember { mutableStateOf(course?.teacher ?: "") }
    var dayOfWeek by remember { mutableIntStateOf(course?.dayOfWeek ?: 1) }
    var startNode by remember { mutableIntStateOf(course?.startNode ?: 1) }
    var endNode by remember { mutableIntStateOf(course?.endNode ?: 2) }
    var startWeek by remember { mutableIntStateOf(course?.startWeek ?: 1) }
    var endWeek by remember { mutableIntStateOf(course?.endWeek ?: 16) }
    var weekType by remember { mutableIntStateOf(course?.weekType ?: 0) }
    var color by remember { mutableStateOf(course?.color ?: Color(0xFF6200EE)) }

    val colors = listOf(
        Color(0xFFF44336), Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4),
        Color(0xFF009688), Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFCDDC39),
        Color(0xFFFFEB3B), Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722),
        Color(0xFF795548), Color(0xFF9E9E9E), Color(0xFF607D8B), Color(0xFF000000)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .heightIn(max = 650.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    if (course == null) "添加课程" else "编辑课程",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("教师") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("时间设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("星期", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = dayOfWeek.toFloat(),
                        onValueChange = { dayOfWeek = it.roundToInt() },
                        valueRange = 1f..7f,
                        steps = 5,
                        modifier = Modifier.weight(1f)
                    )
                    Text("周$dayOfWeek", fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                }

                Text("节次范围: 第 $startNode - $endNode 节")
                RangeSlider(
                    value = startNode.toFloat()..endNode.toFloat(),
                    onValueChange = { range ->
                        startNode = range.start.toInt()
                        endNode = range.endInclusive.toInt()
                    },
                    valueRange = 1f..12f,
                    steps = 10
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("周次范围", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("周次: 第 $startWeek - $endWeek 周")
                RangeSlider(
                    value = startWeek.toFloat()..endWeek.toFloat(),
                    onValueChange = { range ->
                        startWeek = range.start.toInt()
                        endWeek = range.endInclusive.toInt()
                    },
                    valueRange = 1f..25f,
                    steps = 23
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = weekType == 0, onClick = { weekType = 0 }, label = { Text("每周") })
                    FilterChip(selected = weekType == 1, onClick = { weekType = 1 }, label = { Text("单周") })
                    FilterChip(selected = weekType == 2, onClick = { weekType = 2 }, label = { Text("双周") })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("颜色标签", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(c, CircleShape)
                                .clickable { color = c }
                                .padding(4.dp)
                        ) {
                            if (color == c) {
                                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (name.isNotBlank()) {
                            onConfirm(Course(
                                id = course?.id ?: UUID.randomUUID().toString(),
                                name = name,
                                location = location,
                                teacher = teacher,
                                color = color,
                                dayOfWeek = dayOfWeek,
                                startNode = startNode,
                                endNode = endNode,
                                startWeek = startWeek,
                                endWeek = endWeek,
                                weekType = weekType,
                                excludedDates = course?.excludedDates ?: emptyList(),
                                isTemp = false, // 正常添加的是主课程
                                parentCourseId = null
                            ))
                        }
                    }) { Text("确定") }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

// --- 新增：课程单次修改弹窗 (只改节次，可改日期，可删除) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSingleEditDialog(
    initialName: String,
    initialLocation: String,
    initialStartNode: Int,
    initialEndNode: Int,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (String, String, Int, Int, LocalDate) -> Unit // name, location, startNode, endNode, date
) {
    var name by remember { mutableStateOf(initialName) }
    var location by remember { mutableStateOf(initialLocation) }
    var startNode by remember { mutableIntStateOf(initialStartNode) }
    var endNode by remember { mutableIntStateOf(initialEndNode) }
    var date by remember { mutableStateOf(initialDate) }
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        WheelDatePickerDialog(
            initialDate = date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                showDatePicker = false
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑单次课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "注意：此修改仅对本次生效，并在课表中作为独立块显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课程名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 日期选择
                OutlinedTextField(
                    value = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                    onValueChange = {},
                    label = { Text("日期") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true },
                    enabled = false, // 禁止直接输入，必须通过点击
                    readOnly = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    trailingIcon = {
                        Icon(Icons.Default.DateRange, null)
                    }
                )

                HorizontalDivider()

                Text("节次调整: 第 $startNode - $endNode 节")
                RangeSlider(
                    value = startNode.toFloat()..endNode.toFloat(),
                    onValueChange = { range ->
                        startNode = range.start.toInt()
                        endNode = range.endInclusive.toInt()
                    },
                    valueRange = 1f..12f,
                    steps = 10
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    onConfirm(name, location, startNode, endNode, date)
                }) { Text("确定") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
