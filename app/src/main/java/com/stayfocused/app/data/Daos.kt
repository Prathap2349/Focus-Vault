package com.stayfocused.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_apps WHERE isActive = 1")
    fun getActiveBlockedApps(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_apps")
    suspend fun getAllOnce(): List<BlockedApp>

    @Query("SELECT packageName FROM blocked_apps WHERE isActive = 1")
    suspend fun getActivePackageNamesOnce(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: BlockedApp)

    @Delete
    suspend fun delete(app: BlockedApp)
}

@Dao
interface BlockedSiteDao {
    @Query("SELECT * FROM blocked_sites WHERE isActive = 1")
    fun getActiveBlockedSites(): Flow<List<BlockedSite>>

    @Query("SELECT * FROM blocked_sites")
    suspend fun getAllOnce(): List<BlockedSite>

    @Query("SELECT * FROM blocked_sites WHERE isActive = 1")
    suspend fun getActiveSitesOnce(): List<BlockedSite>

    @Query("SELECT domain FROM blocked_sites WHERE isActive = 1")
    suspend fun getActiveDomainsOnce(): List<String>

    @Query("SELECT domain FROM blocked_sites WHERE isActive = 1 AND isPermanent = 1")
    suspend fun getPermanentActiveDomainsOnce(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: BlockedSite)

    @Delete
    suspend fun delete(site: BlockedSite)
}

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_session WHERE id = 1")
    fun observeSession(): Flow<FocusSession?>

    @Query("SELECT * FROM focus_session WHERE id = 1")
    suspend fun getSessionOnce(): FocusSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: FocusSession)
}

@Dao
interface StreakDao {
    @Query("SELECT * FROM streak_day WHERE date = :date")
    suspend fun getDay(date: String): StreakDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: StreakDay)

    /** Most recent day first - StreakManager walks this list to find the longest unbroken
     * run of consecutive days ending today or yesterday. Must only select days with at least
     * one completed session. */
    @Query("SELECT date FROM streak_day WHERE completedSessions > 0 ORDER BY date DESC")
    suspend fun getAllDatesDesc(): List<String>

    /** Every day on/after [startDate] (yyyy-MM-dd), oldest first - used to build the
     * dashboard's 7-day weekly progress chart. */
    @Query("SELECT * FROM streak_day WHERE date >= :startDate ORDER BY date ASC")
    suspend fun getDaysSince(startDate: String): List<StreakDay>
}

@Dao
interface SessionHistoryDao {
    @Insert
    suspend fun insert(entry: SessionHistoryEntry)

    /** Most recent finished sessions first, for the dashboard's Recent Sessions list. */
    @Query("SELECT * FROM session_history ORDER BY endTimeMillis DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SessionHistoryEntry>

    /** All sessions that ended on/after [sinceMillis] - used for this-week statistics
     * (total hours, average/longest session). */
    @Query("SELECT * FROM session_history WHERE endTimeMillis >= :sinceMillis ORDER BY endTimeMillis DESC")
    suspend fun getSince(sinceMillis: Long): List<SessionHistoryEntry>

    @Query("DELETE FROM session_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM session_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM session_history")
    suspend fun deleteAll()
}

@Dao
interface FocusPresetDao {
    @Query("SELECT * FROM focus_presets ORDER BY isBuiltIn DESC, id ASC")
    fun observePresets(): Flow<List<FocusPreset>>

    @Query("SELECT * FROM focus_presets ORDER BY isBuiltIn DESC, id ASC")
    suspend fun getAllPresetsOnce(): List<FocusPreset>

    @Query("SELECT * FROM focus_presets ORDER BY isBuiltIn DESC, id ASC")
    suspend fun getAllOnce(): List<FocusPreset>

    @Query("SELECT * FROM focus_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): FocusPreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: FocusPreset): Long

    @Delete
    suspend fun delete(preset: FocusPreset)
}

@Dao
interface ScheduledSessionDao {
    @Query("SELECT * FROM scheduled_sessions ORDER BY startHour ASC, startMinute ASC")
    fun observeSchedules(): Flow<List<ScheduledSession>>

    @Query("SELECT * FROM scheduled_sessions ORDER BY startHour ASC, startMinute ASC")
    suspend fun getAllOnce(): List<ScheduledSession>

    @Query("SELECT * FROM scheduled_sessions WHERE isEnabled = 1")
    suspend fun getEnabledSchedules(): List<ScheduledSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduledSession): Long

    @Delete
    suspend fun delete(schedule: ScheduledSession)
}
