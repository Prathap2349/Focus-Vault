package com.focusvault.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Converters {
    @TypeConverter
    fun fromMode(mode: SessionMode): String = mode.name
    @TypeConverter
    fun toMode(value: String): SessionMode =
        runCatching { SessionMode.valueOf(value) }.getOrDefault(SessionMode.NORMAL)

    @TypeConverter
    fun fromState(state: SessionState): String = state.name
    @TypeConverter
    fun toState(value: String): SessionState = SessionState.fromString(value)
}

@Database(
    entities = [
        BlockedApp::class,
        BlockedSite::class,
        FocusSession::class,
        StreakDay::class,
        SessionHistoryEntry::class,
        FocusPreset::class,
        ScheduledSession::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockedSiteDao(): BlockedSiteDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun streakDao(): StreakDao
    abstract fun sessionHistoryDao(): SessionHistoryDao
    abstract fun focusPresetDao(): FocusPresetDao
    abstract fun scheduledSessionDao(): ScheduledSessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stay_focused.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Seed default presets
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                seedDefaultPresets(getInstance(context))
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }

        suspend fun seedDefaultPresets(db: AppDatabase) {
            val existing = db.focusPresetDao().getAllPresetsOnce()
            if (existing.isEmpty()) {
                db.focusPresetDao().upsert(FocusPreset(name = "Study", durationMinutes = 50, mode = SessionMode.NORMAL, icon = "📚", isBuiltIn = true))
                db.focusPresetDao().upsert(FocusPreset(name = "Coding", durationMinutes = 90, mode = SessionMode.LOCK, icon = "💻", isBuiltIn = true))
                db.focusPresetDao().upsert(FocusPreset(name = "Deep Work", durationMinutes = 120, mode = SessionMode.STRICT, icon = "⚡", isBuiltIn = true))
                db.focusPresetDao().upsert(FocusPreset(name = "Revision", durationMinutes = 45, mode = SessionMode.NORMAL, icon = "📝", isBuiltIn = true))
                db.focusPresetDao().upsert(FocusPreset(name = "Sleep", durationMinutes = 480, mode = SessionMode.STRICT, icon = "🌙", isBuiltIn = true))
            }
        }
    }
}
