package com.antgskds.calendarassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antgskds.calendarassistant.model.Course
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// 配置项
private val HeaderHeight = 50.dp
private val SidebarWidth = 35.dp
private val NodeHeight = 64.dp // 每一节课的高度

@Composable
fun ScheduleView(
    courses: List<Course>,
    semesterStartDateStr: String, // yyyy-MM-dd
    totalWeeks: Int,
    modifier: Modifier = Modifier
) {
    // 1. 计算当前周
    val today = LocalDate.now()
    val semesterStart = try {
        if (semesterStartDateStr.isNotBlank()) LocalDate.parse(semesterStartDateStr) else today
    } catch (e: Exception) { today }

    // 计算实际的当前周 (1-based)
    val realCurrentWeek = if (semesterStartDateStr.isNotBlank()) {
        (ChronoUnit.DAYS.between(semesterStart, today) / 7).toInt() + 1
    } else {
        1
    }

    // 用户当前查看的周 (默认为当前周，但在范围内)
    var viewingWeek by remember {
        mutableIntStateOf(realCurrentWeek.coerceIn(1, totalWeeks.coerceAtLeast(1)))
    }

    // 计算 viewingWeek 那一周的周一日期
    val viewingWeekMonday = semesterStart.plusWeeks((viewingWeek - 1).toLong())

    // 筛选出该周应该显示的课程
    val displayCourses = remember(courses, viewingWeek) {
        // weekType: 0=All, 1=Odd(单), 2=Even(双)
        val isOdd = viewingWeek % 2 != 0
        val targetType = if (isOdd) 1 else 2

        courses.filter { course ->
            val weekRangeMatch = viewingWeek in course.startWeek..course.endWeek
            val typeMatch = course.weekType == 0 || course.weekType == targetType
            weekRangeMatch && typeMatch
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface) // 底色
    ) {
        // --- A. 顶部控制栏 (切换周次) ---
        WeekControllerBar(
            currentWeek = viewingWeek,
            maxWeeks = totalWeeks,
            currentMonth = viewingWeekMonday.monthValue,
            onPrev = { if (viewingWeek > 1) viewingWeek-- },
            onNext = { if (viewingWeek < totalWeeks) viewingWeek++ },
            onJumpToCurrent = { viewingWeek = realCurrentWeek.coerceIn(1, totalWeeks) }
        )

        // --- B. 课表主体 ---
        // 使用 BoxWithConstraints 获取宽度，以便均分列宽
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val screenWidth = maxWidth
            val dayColumnWidth = (screenWidth - SidebarWidth) / 7

            Column {
                // B1. 星期表头 (Mon - Sun)
                WeekHeaderRow(
                    mondayDate = viewingWeekMonday,
                    today = today,
                    sidebarWidth = SidebarWidth,
                    colWidth = dayColumnWidth
                )

                // B2. 滚动区域 (节次 + 课程网格)
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // 左侧节次栏 (1-12)
                    SidebarColumn(NodeHeight)

                    // 课程网格区域
                    Box(
                        modifier = Modifier
                            .height(NodeHeight * 12) // 总高度 = 12节 * 单节高
                            .weight(1f)
                    ) {
                        // 1. 绘制背景横线 (辅助线)
                        for (i in 1..12) {
                            HorizontalDivider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = NodeHeight * i - 1.dp) // 画在每节课底部
                                    .alpha(0.1f)
                            )
                        }

                        // 3. 绘制课程卡片
                        displayCourses.forEach { course ->
                            CourseCard(
                                course = course,
                                colWidth = dayColumnWidth,
                                nodeHeight = NodeHeight
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- 组件：顶部周次控制器 ---
@Composable
fun WeekControllerBar(
    currentWeek: Int,
    maxWeeks: Int,
    currentMonth: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToCurrent: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onPrev, enabled = currentWeek > 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一周")
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onJumpToCurrent() }) {
            Text(
                text = "第 $currentWeek 周",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${currentMonth}月",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        IconButton(onClick = onNext, enabled = currentWeek < maxWeeks) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一周")
        }
    }
}

// --- 组件：星期表头 ---
@Composable
fun WeekHeaderRow(
    mondayDate: LocalDate,
    today: LocalDate,
    sidebarWidth: Dp,
    colWidth: Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .background(MaterialTheme.colorScheme.surface)
            .border(width = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        // 左上角空白块 (对应节次栏)
        Box(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${mondayDate.monthValue}月",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
        }

        // 周一到周日
        val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        for (i in 0..6) {
            val date = mondayDate.plusDays(i.toLong())
            val isToday = date == today

            Column(
                modifier = Modifier
                    .width(colWidth)
                    .fillMaxHeight()
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = weekDays[i],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${date.monthValue}/${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = if (isToday) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
        }
    }
}

// --- 组件：左侧节次栏 ---
@Composable
fun SidebarColumn(nodeHeight: Dp) {
    Column(
        modifier = Modifier
            .width(SidebarWidth)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f))
    ) {
        for (i in 1..12) {
            Box(
                modifier = Modifier
                    .height(nodeHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$i",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- 组件：课程卡片 (绝对定位) ---
@Composable
fun CourseCard(
    course: Course,
    colWidth: Dp,
    nodeHeight: Dp
) {
    // dayOfWeek: 1=Mon, ..., 7=Sun
    // Box 的 x 偏移量 = (dayOfWeek - 1) * colWidth
    val xOffset = colWidth * (course.dayOfWeek - 1)

    // Box 的 y 偏移量 = (startNode - 1) * nodeHeight
    val yOffset = nodeHeight * (course.startNode - 1)

    // Box 的高度 = (endNode - startNode + 1) * nodeHeight
    val span = (course.endNode - course.startNode + 1).coerceAtLeast(1)
    val height = nodeHeight * span - 2.dp // 减去一点间隙，防止连在一起

    // 卡片主体
    Card(
        modifier = Modifier
            .width(colWidth - 2.dp) // 减去左右间隙
            .height(height)
            .offset(x = xOffset + 1.dp, y = yOffset + 1.dp), // 加一点偏移居中
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = course.color.copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                lineHeight = 11.sp
            )
            if (course.location.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "@${course.location}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 9.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    lineHeight = 10.sp
                )
            }
        }
    }
}