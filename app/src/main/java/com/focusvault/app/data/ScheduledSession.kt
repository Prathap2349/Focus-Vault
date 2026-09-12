package com.focusvault.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_sessions")
data class ScheduledSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val daysOfWeek: String, // Comma separated integers: 1=Sun, 2=Mon... or "ONCE"
    val startHour: Int,
    val startMinute: Int,
    val durationMinutes: Int,
    val mode: SessionMode = SessionMode.NORMAL,
    val isEnabled: Boolean = true
)
