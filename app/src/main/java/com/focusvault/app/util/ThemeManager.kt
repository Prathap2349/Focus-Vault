package com.focusvault.app.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.focusvault.app.R

data class ThemeColors(
    val primary: Int,
    val primaryDark: Int,
    val accent: Int,
    val background: Int,
    val cardBackground: Int,
    val isDark: Boolean
)

object ThemeManager {

    enum class Palette(val key: String, val displayName: String, val previewColor: String) {
        AURORA("aurora", "Focus Indigo", "#6366F1"),
        MIDNIGHT("midnight", "Midnight Violet", "#7C5CFF"),
        OCEAN("ocean", "Ocean Blue", "#00B4D8"),
        FOREST("forest", "Forest Emerald", "#14B8A6"),
        SUNSET("sunset", "Sunset Amber", "#F59E0B"),
        MINIMAL("minimal", "Minimal Slate", "#64748B"),
        AMOLED_BLACK("amoled_black", "AMOLED Black", "#000000"),
        DYNAMIC("dynamic", "Dynamic (Material You)", "#6366F1"),
        CYBERPUNK("cyberpunk", "Cyberpunk Neon", "#00F2FE"),
        EMERALD("emerald", "Emerald Zen", "#00E676");

        companion object {
            fun fromKey(key: String): Palette =
                values().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AURORA
        }
    }

    fun applyTheme(activity: AppCompatActivity) {
        val mode = PrefsManager.getThemeMode(activity)
        when (mode) {
            PrefsManager.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            PrefsManager.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun getThemeColors(context: Context): ThemeColors {
        val paletteKey = PrefsManager.getThemePalette(context)
        val palette = Palette.fromKey(paletteKey)
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val isAmoled = isNight && (palette == Palette.AMOLED_BLACK || PrefsManager.isAmoledMode(context))

        val baseBg = if (isAmoled) Color.BLACK else if (isNight) Color.parseColor("#0B0C10") else Color.parseColor("#F8FAFC")
        val baseCard = if (isAmoled) Color.parseColor("#08080A") else if (isNight) Color.parseColor("#13141C") else Color.WHITE

        return when (palette) {
            Palette.MIDNIGHT -> ThemeColors(
                primary = Color.parseColor("#7C5CFF"),
                primaryDark = Color.parseColor("#5236DB"),
                accent = Color.parseColor("#14B8A6"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.OCEAN -> ThemeColors(
                primary = Color.parseColor("#00B4D8"),
                primaryDark = Color.parseColor("#0077B6"),
                accent = Color.parseColor("#14B8A6"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.FOREST -> ThemeColors(
                primary = Color.parseColor("#14B8A6"),
                primaryDark = Color.parseColor("#0D9488"),
                accent = Color.parseColor("#22C55E"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.SUNSET -> ThemeColors(
                primary = Color.parseColor("#F59E0B"),
                primaryDark = Color.parseColor("#D97706"),
                accent = Color.parseColor("#6366F1"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.MINIMAL -> ThemeColors(
                primary = Color.parseColor("#64748B"),
                primaryDark = Color.parseColor("#475569"),
                accent = Color.parseColor("#94A3B8"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.AMOLED_BLACK -> ThemeColors(
                primary = Color.parseColor("#6366F1"),
                primaryDark = Color.parseColor("#4F46E5"),
                accent = Color.parseColor("#14B8A6"),
                background = Color.BLACK,
                cardBackground = Color.parseColor("#0A0A0C"),
                isDark = true
            )
            Palette.DYNAMIC -> {
                val primaryColor = ContextCompat.getColor(context, R.color.brand_primary)
                ThemeColors(
                    primary = primaryColor,
                    primaryDark = ContextCompat.getColor(context, R.color.brand_primary_dark),
                    accent = ContextCompat.getColor(context, R.color.secondary_teal),
                    background = baseBg,
                    cardBackground = baseCard,
                    isDark = isNight
                )
            }
            Palette.CYBERPUNK -> ThemeColors(
                primary = Color.parseColor("#00F2FE"),
                primaryDark = Color.parseColor("#00C2CE"),
                accent = Color.parseColor("#FF0844"),
                background = if (isNight) Color.parseColor("#0D0221") else Color.parseColor("#F5F3FF"),
                cardBackground = if (isNight) Color.parseColor("#190A38") else Color.WHITE,
                isDark = isNight
            )
            Palette.EMERALD -> ThemeColors(
                primary = Color.parseColor("#00E676"),
                primaryDark = Color.parseColor("#00B359"),
                accent = Color.parseColor("#10B981"),
                background = if (isNight) Color.parseColor("#051F14") else Color.parseColor("#F0FDF4"),
                cardBackground = if (isNight) Color.parseColor("#0A3322") else Color.WHITE,
                isDark = isNight
            )
            Palette.AURORA -> ThemeColors(
                primary = Color.parseColor("#6366F1"),
                primaryDark = Color.parseColor("#4F46E5"),
                accent = Color.parseColor("#14B8A6"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
        }
    }
}
