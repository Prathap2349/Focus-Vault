package com.stayfocused.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val isActive: Boolean = true
)
