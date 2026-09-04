package com.stayfocused.app.util

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object EdgeToEdge {

    /** True if the device is *currently* rendering in dark mode - checked directly against
     * the active configuration rather than the saved theme preference, since "follow system"
     * means the preference itself doesn't tell you which one is actually showing right now. */
    fun isNightModeActive(activity: Activity): Boolean {
        val mode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Draws [activity]'s content behind the system status/navigation bars (so backgrounds and
     * gradients extend all the way to the screen edges) and adds the bars' own insets back as
     * padding on [contentView] - on top of whatever padding it already has in XML - so nothing
     * important ends up sitting under a notch or the gesture nav bar.
     *
     * @param useDarkIcons true if what's behind the status bar is light (needs dark icons for
     * contrast); false if it's dark or a colorful gradient (needs light/white icons).
     */
    fun apply(activity: Activity, contentView: View, useDarkIcons: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT

        val controller = WindowInsetsControllerCompat(activity.window, contentView)
        controller.isAppearanceLightStatusBars = useDarkIcons
        controller.isAppearanceLightNavigationBars = useDarkIcons

        val baseLeft = contentView.paddingLeft
        val baseTop = contentView.paddingTop
        val baseRight = contentView.paddingRight
        val baseBottom = contentView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }
}
