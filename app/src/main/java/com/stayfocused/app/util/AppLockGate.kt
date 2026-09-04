package com.stayfocused.app.util

/**
 * In-memory-only "has the App Lock PIN already been entered while the app has been
 * continuously in the foreground" flag.
 *
 * - Starts false on every fresh process (covers: force-stopped and reopened, phone
 *   rebooted, app killed by the OS in the background).
 * - Reset back to false by StayFocusedApp the moment the *whole app* leaves the
 *   foreground (home button, app switcher, screen lock) - not on internal navigation
 *   between the app's own screens.
 */
object AppLockGate {
    @Volatile
    var isUnlocked: Boolean = false
}
