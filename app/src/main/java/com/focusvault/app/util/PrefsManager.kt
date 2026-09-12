package com.focusvault.app.util

import android.content.Context
import com.focusvault.app.data.SessionMode
import com.focusvault.app.data.SessionState

/**
 * The Accessibility Service and VPN Service both need to check "is X blocked right now?"
 * on every single screen/DNS event. Querying Room (a suspend/async DB) on that hot path
 * would add latency and thread complexity, so we mirror the small amount of state that
 * matters into fast synchronous SharedPreferences. Every write to Room (from the UI) also
 * writes here; every read on the blocking hot-path reads from here only.
 */
object PrefsManager {
    private const val PREFS = "stay_focused_fast_cache"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
    private const val KEY_SESSION_MODE = "session_mode"
    private const val KEY_SESSION_STATE = "session_state"
    private const val KEY_SESSION_END = "session_end"
    private const val KEY_SESSION_START = "session_start"
    private const val KEY_EMERGENCY_USED = "emergency_used"
    private const val KEY_EMERGENCY_DAY = "emergency_day"
    private const val MAX_EMERGENCY_PER_DAY = 1
    private const val KEY_EMERGENCY_PAUSE_UNTIL = "emergency_pause_until"
    private const val KEY_EMERGENCY_PAUSE_LABEL = "emergency_pause_label"
    private const val KEY_LOCK_PIN = "lock_mode_pin"
    private const val KEY_SEC_QUESTION_INDEX = "security_question_index"
    private const val KEY_SEC_ANSWER = "security_answer"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_THEME_PALETTE = "theme_palette"
    private const val KEY_AMOLED_MODE = "amoled_mode"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_unlock_enabled"
    private const val KEY_DAILY_GOAL_MINUTES = "daily_goal_minutes"
    private const val KEY_WEEKLY_GOAL_MINUTES = "weekly_goal_minutes"
    private const val KEY_MONTHLY_GOAL_MINUTES = "monthly_goal_minutes"
    private const val KEY_VPN_SESSION_ONLY = "vpn_session_only"
    private const val KEY_VPN_MANUALLY_STOPPED = "vpn_manually_stopped"
    private const val KEY_PERMANENT_PAUSE_UNTIL = "permanent_block_pause_until"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val DEFAULT_DAILY_GOAL_MINUTES = 120

    const val PALETTE_AURORA = "aurora"
    const val PALETTE_MIDNIGHT = "midnight"
    const val PALETTE_OCEAN = "ocean"
    const val PALETTE_FOREST = "forest"
    const val PALETTE_SUNSET = "sunset"
    const val PALETTE_MINIMAL = "minimal"
    const val PALETTE_AMOLED_BLACK = "amoled_black"
    const val PALETTE_DYNAMIC = "dynamic"
    const val PALETTE_CYBERPUNK = "cyberpunk"
    const val PALETTE_EMERALD = "emerald"

    /** Preset "Forgot PIN?" questions to choose from - picking one and answering it is
     * simpler than being forced to answer several, while still being something only the
     * PIN's owner is likely to know. */
    val SECURITY_QUESTIONS = listOf(
        "What is your favorite dog breed?",
        "What is your favorite number?",
        "What is your favorite color?",
        "What is your favorite hobby?",
        "What city were you born in?",
        "What is your best friend's name?"
    )

    @Volatile private var cachedBlockedPackages: Set<String>? = null
    @Volatile private var cachedBlockedDomains: Set<String>? = null

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setBlockedPackages(context: Context, packages: Set<String>) {
        cachedBlockedPackages = packages
        prefs(context).edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
    }

    fun getBlockedPackages(context: Context): Set<String> {
        val mem = cachedBlockedPackages
        if (mem != null) return mem
        val set = prefs(context).getStringSet(KEY_BLOCKED_PACKAGES, emptySet()) ?: emptySet()
        cachedBlockedPackages = set
        return set
    }

    fun setBlockedDomains(context: Context, domains: Set<String>) {
        cachedBlockedDomains = domains
        prefs(context).edit().putStringSet(KEY_BLOCKED_DOMAINS, domains).apply()
    }

    fun getBlockedDomains(context: Context): Set<String> {
        val mem = cachedBlockedDomains
        if (mem != null) return mem
        val set = prefs(context).getStringSet(KEY_BLOCKED_DOMAINS, emptySet()) ?: emptySet()
        cachedBlockedDomains = set
        return set
    }

    private const val KEY_QUERIED_DOMAIN_COUNTS = "queried_domain_counts"

    @Synchronized
    fun recordQueriedDomain(context: Context, rawDomain: String) {
        val domain = rawDomain.removePrefix("www.").lowercase().trim()
        if (domain.isEmpty() || domain.endsWith(".arpa") || domain.endsWith(".local") || domain.contains("localhost")) return
        val currentBlocked = getBlockedDomains(context)
        if (currentBlocked.any { domain == it || domain.endsWith(".$it") }) return

        val prefs = prefs(context)
        val rawMap = prefs.getString(KEY_QUERIED_DOMAIN_COUNTS, "") ?: ""
        val countsMap = rawMap.split(";").filter { it.contains(":") }.mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
        }.toMap().toMutableMap()

        val currentCount = countsMap[domain] ?: 0
        countsMap[domain] = currentCount + 1

        val updatedStr = countsMap.entries.sortedByDescending { it.value }.take(50).joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_QUERIED_DOMAIN_COUNTS, updatedStr).apply()
    }

    fun getTopSuggestedDomains(context: Context, limit: Int = 5): List<String> {
        val currentBlocked = getBlockedDomains(context)
        val prefs = prefs(context)
        val rawMap = prefs.getString(KEY_QUERIED_DOMAIN_COUNTS, "") ?: ""
        val countsMap = rawMap.split(";").filter { it.contains(":") }.mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
        }
        return countsMap
            .filter { (domain, _) -> currentBlocked.none { domain == it || domain.endsWith(".$it") } }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    fun clearQueriedDomainCounts(context: Context) {
        prefs(context).edit().remove(KEY_QUERIED_DOMAIN_COUNTS).apply()
    }

    fun setSession(context: Context, mode: SessionMode, state: SessionState, endTimeMillis: Long) {
        val effectiveEnd = if (state.isLive) endTimeMillis else 0L
        val editor = prefs(context).edit()
            .putString(KEY_SESSION_MODE, mode.name)
            .putString(KEY_SESSION_STATE, state.name)
            .putLong(KEY_SESSION_END, effectiveEnd)
        if (state.isLive && prefs(context).getLong(KEY_SESSION_START, 0L) <= 0L) {
            editor.putLong(KEY_SESSION_START, System.currentTimeMillis())
        } else if (!state.isLive) {
            editor.remove(KEY_SESSION_START)
        }
        editor.apply()
    }

    fun getSessionStartTime(context: Context): Long = prefs(context).getLong(KEY_SESSION_START, 0L)

    fun getSessionMode(context: Context): SessionMode {
        val raw = prefs(context).getString(KEY_SESSION_MODE, SessionMode.NORMAL.name)
        return runCatching { SessionMode.valueOf(raw!!) }.getOrDefault(SessionMode.NORMAL)
    }

    fun getSessionState(context: Context): SessionState {
        val raw = prefs(context).getString(KEY_SESSION_STATE, SessionState.IDLE.name)
        return SessionState.fromString(raw)
    }

    fun getSessionEndTime(context: Context): Long = prefs(context).getLong(KEY_SESSION_END, 0L)

    /** Returns true if a session is currently active or paused in fast cache. */
    fun isSessionCurrentlyActive(context: Context): Boolean {
        val state = getSessionState(context)
        val end = getSessionEndTime(context)
        return state.isLive && System.currentTimeMillis() < end
    }

    fun isStrictModeActive(context: Context): Boolean =
        isSessionCurrentlyActive(context) && getSessionMode(context) == SessionMode.STRICT

    fun isLockModeActive(context: Context): Boolean =
        isSessionCurrentlyActive(context) && getSessionMode(context) == SessionMode.LOCK

    /** Lock Mode gates exits behind this PIN. Delegated to SecurityManager for salted hashing & rate limiting. */
    fun hasLockPin(context: Context): Boolean =
        com.focusvault.app.manager.SecurityManager.hasPin(context)

    fun setLockPin(context: Context, pin: String) {
        com.focusvault.app.manager.SecurityManager.setPin(context, pin)
    }

    fun verifyLockPin(context: Context, attempt: String): Boolean =
        com.focusvault.app.manager.SecurityManager.verifyPin(context, attempt).isSuccess

    fun hasSecurityAnswer(context: Context): Boolean =
        com.focusvault.app.manager.SecurityManager.hasSecurityAnswer(context)

    fun setSecurityAnswer(context: Context, questionIndex: Int, answer: String) {
        com.focusvault.app.manager.SecurityManager.setSecurityAnswer(context, questionIndex, answer)
    }

    fun getSecurityQuestionIndex(context: Context): Int =
        com.focusvault.app.manager.SecurityManager.getSecurityQuestionIndex(context)

    fun verifySecurityAnswer(context: Context, answer: String): Boolean =
        com.focusvault.app.manager.SecurityManager.verifySecurityAnswer(context, answer)

    /** Emergency unlock is ONLY ever consulted for Normal mode. Strict mode never calls this. */
    fun canUseEmergencyUnlockToday(context: Context): Boolean {
        val today = todayString()
        val storedDay = prefs(context).getString(KEY_EMERGENCY_DAY, "")
        val used = if (storedDay == today) prefs(context).getInt(KEY_EMERGENCY_USED, 0) else 0
        return used < MAX_EMERGENCY_PER_DAY
    }

    fun consumeEmergencyUnlock(context: Context) {
        val today = todayString()
        val storedDay = prefs(context).getString(KEY_EMERGENCY_DAY, "")
        val used = if (storedDay == today) prefs(context).getInt(KEY_EMERGENCY_USED, 0) else 0
        prefs(context).edit()
            .putString(KEY_EMERGENCY_DAY, today)
            .putInt(KEY_EMERGENCY_USED, used + 1)
            .apply()
    }

    /** Ends the current session immediately - used by Normal mode's "Stop early" button. Never called in Strict mode. */
    fun forceEndSession(context: Context) {
        prefs(context).edit()
            .putString(KEY_SESSION_STATE, SessionState.STOPPED.name)
            .putLong(KEY_SESSION_END, 0L)
            .remove(KEY_SESSION_START)
            .apply()
    }

    /** The Emergency Mode System (Real Emergency / Important Call / Travel Mode) - distinct
     * from the emergency UNLOCK above. That ends the session outright, with a strict 1/day
     * limit. This instead just PAUSES blocking for a bounded window while the session (and its
     * timer) keeps running underneath - see AppBlockAccessibilityService, which skips blocking
     * entirely while [isEmergencyPauseActive] is true. No explicit "resume" write is needed
     * for the pause to end: every check compares against [KEY_EMERGENCY_PAUSE_UNTIL] and a
     * plain timestamp, so it lapses on its own the instant that time passes. "Resume Now"
     * just moves that timestamp into the past early. */
    fun setEmergencyPause(context: Context, untilMillis: Long, label: String) {
        prefs(context).edit()
            .putLong(KEY_EMERGENCY_PAUSE_UNTIL, untilMillis)
            .putString(KEY_EMERGENCY_PAUSE_LABEL, label)
            .apply()
    }

    /** "Resume Now" - ends the pause immediately instead of waiting for it to lapse. */
    fun clearEmergencyPause(context: Context) {
        prefs(context).edit().putLong(KEY_EMERGENCY_PAUSE_UNTIL, 0L).apply()
    }

    fun getEmergencyPauseUntil(context: Context): Long =
        prefs(context).getLong(KEY_EMERGENCY_PAUSE_UNTIL, 0L)

    /** Which of the three emergency types is/was active - "Real Emergency", "Important Call",
     * or "Travel Mode" - shown on the paused-state banner. */
    fun getEmergencyPauseLabel(context: Context): String =
        prefs(context).getString(KEY_EMERGENCY_PAUSE_LABEL, "") ?: ""

    fun isEmergencyPauseActive(context: Context): Boolean =
        isSessionCurrentlyActive(context) && getEmergencyPauseUntil(context) > System.currentTimeMillis()

    /** Theme choice: THEME_SYSTEM (follow the device), THEME_LIGHT, or THEME_DARK. Applied via
     * AppCompatDelegate.setDefaultNightMode - see StayFocusedApp.onCreate(). */
    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    /** Whether fingerprint/face unlock should be offered as a faster alternative to typing
     * the PIN. Only meaningful (and only ever surfaced in the UI) once a PIN already exists -
     * biometric unlock is a shortcut on top of the PIN, never a replacement for having one. */
    fun isBiometricEnabled(context: Context): Boolean =
        hasLockPin(context) && prefs(context).getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getThemePalette(context: Context): String =
        prefs(context).getString(KEY_THEME_PALETTE, PALETTE_AURORA) ?: PALETTE_AURORA

    fun setThemePalette(context: Context, palette: String) {
        prefs(context).edit().putString(KEY_THEME_PALETTE, palette).apply()
    }

    fun isAmoledMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AMOLED_MODE, false)

    fun setAmoledMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AMOLED_MODE, enabled).apply()
    }

    fun isReduceMotion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REDUCE_MOTION, false)

    fun setReduceMotion(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REDUCE_MOTION, enabled).apply()
    }

    fun isHapticFeedbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean("haptic_feedback_enabled", true)

    fun setHapticFeedbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("haptic_feedback_enabled", enabled).apply()
    }

    fun isHapticsEnabled(context: Context): Boolean = isHapticFeedbackEnabled(context)
    fun setHapticsEnabled(context: Context, enabled: Boolean) = setHapticFeedbackEnabled(context, enabled)

    fun isSoundEffectsEnabled(context: Context): Boolean =
        prefs(context).getBoolean("sound_effects_enabled", false)

    fun setSoundEffectsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("sound_effects_enabled", enabled).apply()
    }

    fun isSoundEnabled(context: Context): Boolean = isSoundEffectsEnabled(context)
    fun setSoundEnabled(context: Context, enabled: Boolean) = setSoundEffectsEnabled(context, enabled)

    fun isAuraAnimationEnabled(context: Context): Boolean =
        prefs(context).getBoolean("aura_animation_enabled", true) && !isReduceMotion(context)

    fun setAuraAnimationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("aura_animation_enabled", enabled).apply()
    }

    fun isStartCountdownAnimationEnabled(context: Context): Boolean =
        prefs(context).getBoolean("countdown_animation_enabled", true)

    fun setStartCountdownAnimationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("countdown_animation_enabled", enabled).apply()
    }

    fun getLastChosenDurationMillis(context: Context): Long =
        prefs(context).getLong("last_chosen_duration_millis", 25 * 60_000L)

    fun setLastChosenDurationMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong("last_chosen_duration_millis", millis.coerceAtLeast(60_000L)).apply()
    }

    fun getWeeklyGoalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_WEEKLY_GOAL_MINUTES, 720)

    fun setWeeklyGoalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_WEEKLY_GOAL_MINUTES, minutes.coerceIn(60, 4800)).apply()
    }

    fun getMonthlyGoalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_MONTHLY_GOAL_MINUTES, 3000)

    fun setMonthlyGoalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MONTHLY_GOAL_MINUTES, minutes.coerceIn(300, 20000)).apply()
    }

    /** Daily focus-time goal, in minutes, shown on the dashboard's progress ring and weekly
     * chart. Defaults to 2 hours; editable by tapping the goal on the dashboard. */
    fun getDailyGoalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_GOAL_MINUTES, DEFAULT_DAILY_GOAL_MINUTES)

    fun setDailyGoalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_DAILY_GOAL_MINUTES, minutes.coerceIn(15, 960)).apply()
    }

    // Per-Widget Configuration (by AppWidgetId)
    const val WIDGET_MODE_SMART = "smart"
    const val WIDGET_MODE_FOCUS = "focus"
    const val WIDGET_MODE_PROGRESS = "progress"
    const val WIDGET_MODE_MINIMAL = "minimal"

    fun getWidgetMode(context: Context, appWidgetId: Int): String =
        prefs(context).getString("widget_mode_$appWidgetId", WIDGET_MODE_SMART) ?: WIDGET_MODE_SMART

    fun setWidgetMode(context: Context, appWidgetId: Int, mode: String) {
        prefs(context).edit().putString("widget_mode_$appWidgetId", mode).apply()
    }

    fun removeWidgetConfig(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove("widget_mode_$appWidgetId").remove("widget_action_$appWidgetId").apply()
    }

    fun isVpnSessionOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VPN_SESSION_ONLY, true)

    fun setVpnSessionOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VPN_SESSION_ONLY, value).apply()
    }

    fun isVpnManuallyStopped(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VPN_MANUALLY_STOPPED, false)

    fun setVpnManuallyStopped(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VPN_MANUALLY_STOPPED, value).apply()
    }

    fun setPermanentBlockedDomains(context: Context, domains: Set<String>) {
        prefs(context).edit().putStringSet("permanent_blocked_domains", domains).apply()
    }

    fun getPermanentBlockedDomains(context: Context): Set<String> {
        return prefs(context).getStringSet("permanent_blocked_domains", emptySet()) ?: emptySet()
    }

    fun isPermanentVaultLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean("permanent_vault_lock", false)

    fun setPermanentVaultLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("permanent_vault_lock", enabled).apply()
    }

    /** Today's date as yyyy-MM-dd in the device's local time zone - same format/definition of
     * "day" used for the emergency-unlock daily counter above, reused by the streak tracker so
     * the two features agree about when a day rolls over. */
    fun currentDateString(): String = todayString()

    // ── 24/7 Permanent Block Pause / Resume ──────────────────────────────────────────────────────
    // Works identically to the session-level emergency pause: we store a millisecond timestamp
    // in the future. Every check compares now() against it. When now() passes it the pause is
    // over automatically. "Resume Now" just sets the timestamp to 0 to expire it early.

    /** Pause 24/7 permanent website blocking until [untilMillis]. */
    fun setPermanentBlockPause(context: Context, untilMillis: Long) {
        prefs(context).edit().putLong(KEY_PERMANENT_PAUSE_UNTIL, untilMillis).apply()
    }

    /** End the pause immediately (or ensure there is no active pause). */
    fun clearPermanentBlockPause(context: Context) {
        prefs(context).edit().putLong(KEY_PERMANENT_PAUSE_UNTIL, 0L).apply()
    }

    fun getPermanentBlockPauseUntil(context: Context): Long =
        prefs(context).getLong(KEY_PERMANENT_PAUSE_UNTIL, 0L)

    /** Returns true when 24/7 permanent blocking is currently paused. */
    fun isPermanentBlockPaused(context: Context): Boolean =
        getPermanentBlockPauseUntil(context) > System.currentTimeMillis()

    private fun todayString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date())
    }

    // --- Individual Site Pausing (Task 3) ---
    private const val KEY_PAUSED_SITES = "paused_sites_json"

    fun pauseIndividualSite(context: android.content.Context, domain: String, durationMillis: Long) {
        val prefs = prefs(context)
        val currentJson = prefs.getString(KEY_PAUSED_SITES, "{}") ?: "{}"
        try {
            val jsonObject = org.json.JSONObject(currentJson)
            jsonObject.put(domain, System.currentTimeMillis() + durationMillis)
            prefs.edit().putString(KEY_PAUSED_SITES, jsonObject.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isIndividualSitePaused(context: android.content.Context, domain: String): Boolean {
        val prefs = prefs(context)
        val currentJson = prefs.getString(KEY_PAUSED_SITES, "{}") ?: "{}"
        return try {
            val jsonObject = org.json.JSONObject(currentJson)
            if (jsonObject.has(domain)) {
                val expiry = jsonObject.getLong(domain)
                if (System.currentTimeMillis() < expiry) {
                    true
                } else {
                    jsonObject.remove(domain)
                    prefs.edit().putString(KEY_PAUSED_SITES, jsonObject.toString()).apply()
                    false
                }
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
