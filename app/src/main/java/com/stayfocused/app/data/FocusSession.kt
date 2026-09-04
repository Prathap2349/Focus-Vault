package com.stayfocused.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SessionMode { NORMAL, LOCK, STRICT }

enum class SessionState {
    IDLE,
    STARTING,
    ACTIVE,
    PAUSED,
    COMPLETING,
    COMPLETED,
    STOPPED,
    INTERRUPTED,
    RECOVERING,
    PROTECTION_FAILED;

    val isLive: Boolean
        get() = this == ACTIVE || this == PAUSED || this == RECOVERING

    companion object {
        fun fromString(value: String?): SessionState {
            return when (value) {
                "RUNNING" -> ACTIVE
                null -> IDLE
                else -> runCatching { valueOf(value) }.getOrDefault(IDLE)
            }
        }
    }
}

@Entity(tableName = "focus_session")
data class FocusSession(
    @PrimaryKey val id: Int = 1, // singleton row - only one active session at a time
    val mode: SessionMode = SessionMode.NORMAL,
    val state: SessionState = SessionState.IDLE,
    val startTimeMillis: Long = 0L,
    val endTimeMillis: Long = 0L,
    val pausedDurationMillis: Long = 0L,
    val pauseStartTimeMillis: Long = 0L,
    val title: String = "Focus Session",
    val distractionsBlocked: Int = 0,
    val emergencyUnlocksUsedToday: Int = 0,
    val lastEmergencyUnlockDay: String = "" // yyyy-MM-dd, resets the counter daily
)
