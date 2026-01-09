package com.antgskds.calendarassistant.model

import kotlinx.serialization.Serializable
import com.antgskds.calendarassistant.model.ColorSerializer
import com.antgskds.calendarassistant.model.MyEvent
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
    val weekType: Int = 0 // 0=All, 1=Odd, 2=Even
)

@Serializable
data class TimeNode(
    val index: Int,
    val startTime: String, // HH:mm
    val endTime: String    // HH:mm
)
