package com.antgskds.calendarassistant.model

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

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
