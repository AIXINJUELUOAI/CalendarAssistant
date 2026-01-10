package com.antgskds.calendarassistant.model

import kotlinx.serialization.Serializable
import com.antgskds.calendarassistant.model.ColorSerializer
import androidx.compose.ui.graphics.Color

@Serializable
data class Course(
    val id: String,
    val name: String,
    val location: String = "",
    val teacher: String = "",
    @Serializable(with = ColorSerializer::class)
    val color: Color,
    val dayOfWeek: Int, // 1=Mon, 7=Sun
    val startNode: Int,
    val endNode: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int = 0, // 0=All, 1=Odd, 2=Even

    // --- 排除列表 (黑名单) ---
    val excludedDates: List<String> = emptyList(),

    // --- 新增：影子课程标记 ---
    // true 表示这是一个由修改产生的临时课程，不应在管理列表中显示
    val isTemp: Boolean = false,

    // --- 新增：父课程 ID ---
    // 记录它源自哪个主课程，删除主课程时需同步删除其子影子
    val parentCourseId: String? = null
)

@Serializable
data class TimeNode(
    val index: Int,
    val startTime: String, // HH:mm
    val endTime: String    // HH:mm
)