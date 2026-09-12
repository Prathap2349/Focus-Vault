package com.focusvault.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_sites")
data class BlockedSite(
    @PrimaryKey val domain: String, // e.g. "youtube.com" - subdomains are matched too
    val isActive: Boolean = true,
    val isPermanent: Boolean = false
)
