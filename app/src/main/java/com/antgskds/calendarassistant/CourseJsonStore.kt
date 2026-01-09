package com.antgskds.calendarassistant

import android.content.Context
import android.util.Log
import com.antgskds.calendarassistant.model.Course
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class CourseJsonStore(private val context: Context) {
    private val filename = "courses.json"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun saveCourses(courses: List<Course>) {
        try {
            val jsonString = json.encodeToString(courses)
            context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                it.write(jsonString.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("CourseJsonStore", "Failed to save courses", e)
        }
    }

    fun loadCourses(): MutableList<Course> {
        val file = File(context.filesDir, filename)
        if (!file.exists()) return mutableListOf()
        return try {
            val jsonString = file.readText()
            json.decodeFromString<MutableList<Course>>(jsonString)
        } catch (e: Exception) {
            Log.e("CourseJsonStore", "Failed to load courses", e)
            mutableListOf()
        }
    }
}
