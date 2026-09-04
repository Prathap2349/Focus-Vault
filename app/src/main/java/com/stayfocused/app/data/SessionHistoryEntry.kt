package com.stayfocused.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per finished session (however it ended). Unlike [FocusSession] - a singleton row
 * that only ever tracks the *current* session - this is an append-only log, kept specifically
 * to power the dashboard's "Recent Sessions" list and this-week statistics. */
@Entity(tableName = "session_history")
data class SessionHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: SessionMode,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMinutes: Int,
    val completedNaturally: Boolean,
    val title: String = "Focus Session",
    val distractionsBlocked: Int = 0,
    val stopReason: String = ""
)
