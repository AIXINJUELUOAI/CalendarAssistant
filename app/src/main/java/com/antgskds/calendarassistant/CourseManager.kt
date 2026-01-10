package com.antgskds.calendarassistant

import com.antgskds.calendarassistant.model.Course
import com.antgskds.calendarassistant.model.MyEvent
import com.antgskds.calendarassistant.model.MySettings
import com.antgskds.calendarassistant.model.TimeNode
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object CourseManager {

    private val json = Json { ignoreUnknownKeys = true }

    fun getDailyCourses(
        targetDate: LocalDate,
        allCourses: List<Course>,
        settings: MySettings
    ): List<MyEvent> {
        val startDateStr = settings.semesterStartDate
        if (startDateStr.isBlank()) return emptyList()

        val startDate = try {
            LocalDate.parse(startDateStr)
        } catch (e: Exception) {
            return emptyList()
        }

        if (targetDate.isBefore(startDate)) return emptyList()

        val daysDiff = ChronoUnit.DAYS.between(startDate, targetDate)
        val currentWeek = (daysDiff / 7).toInt() + 1
        val maxWeeks = settings.totalWeeks

        if (currentWeek > maxWeeks) return emptyList()

        // 0=All, 1=Odd(单), 2=Even(双)
        val currentWeekType = if (currentWeek % 2 != 0) 1 else 2

        // Parse TimeTable
        val timeTableJson = settings.timeTableJson
        val timeNodes = if (timeTableJson.isBlank()) {
            getDefaultTimeNodes()
        } else {
            try {
                json.decodeFromString<List<TimeNode>>(timeTableJson)
            } catch (e: Exception) {
                getDefaultTimeNodes()
            }
        }
        val timeMap = timeNodes.associateBy { it.index }

        val dayOfWeek = targetDate.dayOfWeek.value // 1 (Mon) - 7 (Sun)
        val targetDateStr = targetDate.toString() // yyyy-MM-dd

        return allCourses.filter { course ->
            // 1. 检查周次范围
            val weekMatch = currentWeek in course.startWeek..course.endWeek
            // 2. 检查单双周
            val typeMatch = course.weekType == 0 || course.weekType == currentWeekType
            // 3. 检查星期几
            val dayMatch = course.dayOfWeek == dayOfWeek
            // 4. 【新增】检查排除日期 (如果今天在黑名单里，跳过)
            val notExcluded = !course.excludedDates.contains(targetDateStr)

            weekMatch && typeMatch && dayMatch && notExcluded
        }.mapNotNull { course ->
            val startNode = timeMap[course.startNode]
            val endNode = timeMap[course.endNode]

            if (startNode != null && endNode != null) {
                // 构造虚拟 ID： course_{CourseID}_{Date}
                // 用于后续在 UI 层识别这是哪门课的哪一天
                val virtualId = "course_${course.id}_${targetDateStr}"

                MyEvent(
                    id = virtualId,
                    title = course.name,
                    startDate = targetDate,
                    endDate = targetDate,
                    startTime = startNode.startTime,
                    endTime = endNode.endTime,
                    location = course.location + (if (course.teacher.isNotBlank()) " | ${course.teacher}" else ""),
                    description = "第${course.startNode}-${course.endNode}节",
                    color = course.color,
                    isImportant = false,
                    eventType = "course" // 标记类型为课程
                )
            } else {
                null
            }
        }
    }

    fun getDefaultTimeNodes(): List<TimeNode> {
        return listOf(
            TimeNode(1, "08:00", "08:45"), TimeNode(2, "08:55", "09:40"),
            TimeNode(3, "10:00", "10:45"), TimeNode(4, "10:55", "11:40"),
            TimeNode(5, "14:00", "14:45"), TimeNode(6, "14:55", "15:40"),
            TimeNode(7, "16:00", "16:45"), TimeNode(8, "16:55", "17:40"),
            TimeNode(9, "19:00", "19:45"), TimeNode(10, "19:55", "20:40"),
            TimeNode(11, "20:50", "21:35"), TimeNode(12, "21:45", "22:30")
        )
    }
}