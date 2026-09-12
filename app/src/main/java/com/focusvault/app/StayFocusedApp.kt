package com.focusvault.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.focusvault.app.util.AppLockGate
import com.focusvault.app.util.PrefsManager

/**
 * Tracks two separate signals for when the App Lock PIN should re-lock:
 *
 * 1. Whole-app backgrounding: counts started activities so we know the moment every one of
 *    our screens has stopped (home button, app switcher fully leaving, task killed) - not
 *    individual navigation between the app's own screens.
 * 2. Screen off: turning the phone's screen off does NOT reliably stop the foreground
 *    activity on every device/launcher (the app can still be the active task the whole
 *    time), so without this, locking the phone and unlocking it again could skip the PIN
 *    entirely. Screen-off always re-locks regardless of activity lifecycle.
 */
class StayFocusedApp : Application() {

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()

        AppCompatDelegate.setDefaultNightMode(
            when (PrefsManager.getThemeMode(this)) {
                PrefsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                PrefsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        // Material You: on Android 12+, this overlays colors sampled from the user's
        // wallpaper onto every activity's Material3 theme. On older versions it's a no-op,
        // and the static "Aurora" palette in colors.xml is what everyone sees.
        DynamicColors.applyToActivitiesIfAvailable(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    AppLockGate.isUnlocked = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // SCREEN_OFF is a protected system broadcast - it can only ever be registered at
        // runtime like this, never declared in the manifest, regardless of target SDK.
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        AppLockGate.isUnlocked = false
                    }
                },
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            // Safe fallback on older or custom OEM Android builds
        }
    }
}
