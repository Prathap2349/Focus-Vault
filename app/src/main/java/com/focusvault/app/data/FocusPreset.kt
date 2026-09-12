package com.focusvault.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_presets")
data class FocusPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMinutes: Int,
    val mode: SessionMode = SessionMode.NORMAL,
    val icon: String = "🎯",
    val isBuiltIn: Boolean = false,
    val targetGoalMinutes: Int = 0
)
