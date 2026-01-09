package com.antgskds.calendarassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.antgskds.calendarassistant.model.Course
import java.util.UUID
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CourseManagementDialog(
    courses: List<Course>,
    onDismiss: () -> Unit,
    onAddCourse: (Course) -> Unit,
    onDeleteCourse: (Course) -> Unit,
    onEditCourse: (Course) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var courseToEdit by remember { mutableStateOf<Course?>(null) }

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
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("课程管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    }

                    if (courses.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("暂无课程", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(courses) { course ->
                                CourseItem(course, onDelete = { onDeleteCourse(course) }, onClick = { courseToEdit = course; showEditDialog = true })
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { courseToEdit = null; showEditDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加课程")
                    }
                }
            }
        }
    }
}

@Composable
fun CourseItem(course: Course, onDelete: () -> Unit, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(12.dp).background(course.color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(course.name, fontWeight = FontWeight.Bold)
                Text(
                    "周${course.dayOfWeek} 第${course.startNode}-${course.endNode}节 (${course.startWeek}-${course.endWeek}周)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                if (course.location.isNotBlank()) {
                    Text(course.location, style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

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
    var dayOfWeek by remember { mutableStateOf(course?.dayOfWeek ?: 1) }
    var startNode by remember { mutableStateOf(course?.startNode ?: 1) }
    var endNode by remember { mutableStateOf(course?.endNode ?: 2) }
    var startWeek by remember { mutableStateOf(course?.startWeek ?: 1) }
    var endWeek by remember { mutableStateOf(course?.endWeek ?: 16) }
    var weekType by remember { mutableStateOf(course?.weekType ?: 0) } // 0=All, 1=Odd, 2=Even
    var color by remember { mutableStateOf(course?.color ?: Color(0xFF6200EE)) } // Default Purple

    val colors = listOf(
        Color(0xFFF44336), Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7),
        Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4),
        Color(0xFF009688), Color(0xFF4CAF50), Color(0xFF8BC34A), Color(0xFFCDDC39),
        Color(0xFFFFEB3B), Color(0xFFFFC107), Color(0xFFFF9800), Color(0xFFFF5722)
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(if (course == null) "添加课程" else "编辑课程", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = teacher, onValueChange = { teacher = it }, label = { Text("教师") }, modifier = Modifier.fillMaxWidth())

                Text("时间设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("星期")
                    Slider(
                        value = dayOfWeek.toFloat(),
                        onValueChange = { dayOfWeek = it.toInt() },
                        valueRange = 1f..7f,
                        steps = 5,
                        modifier = Modifier.weight(1f)
                    )
                    Text("周$dayOfWeek")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("节次范围: $startNode - $endNode 节")
                }
                RangeSlider(
                    value = startNode.toFloat()..endNode.toFloat(),
                    onValueChange = { range ->
                        startNode = range.start.toInt()
                        endNode = range.endInclusive.toInt()
                    },
                    valueRange = 1f..12f,
                    steps = 10
                )

                Text("周次范围", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 $startWeek - $endWeek 周")
                }
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

                Text("颜色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(c, CircleShape)
                                .clickable { color = c }
                                .padding(4.dp)
                        ) {
                            if (color == c) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
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
                                weekType = weekType
                            ))
                        }
                    }) { Text("确定") }
                }
            }
        }
    }
}