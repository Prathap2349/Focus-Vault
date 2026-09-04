package com.stayfocused.app.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.stayfocused.app.R

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
        AURORA("aurora", "Aurora Teal", "#14E0B4"),
        MIDNIGHT("midnight", "Midnight Violet", "#7C5CFF"),
        OCEAN("ocean", "Ocean Blue", "#00B4D8"),
        FOREST("forest", "Forest Emerald", "#2EC4B6"),
        SUNSET("sunset", "Sunset Coral", "#FF6B6B"),
        MINIMAL("minimal", "Minimal Slate", "#94A3B8"),
        AMOLED_BLACK("amoled_black", "AMOLED Black", "#000000"),
        DYNAMIC("dynamic", "Dynamic (Material You)", "#3F51B5");

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

        val baseBg = if (isAmoled) Color.BLACK else if (isNight) Color.parseColor("#0D0E14") else Color.parseColor("#F5F7FA")
        val baseCard = if (isAmoled) Color.parseColor("#08080A") else if (isNight) Color.parseColor("#171923") else Color.WHITE

        return when (palette) {
            Palette.MIDNIGHT -> ThemeColors(
                primary = Color.parseColor("#7C5CFF"),
                primaryDark = Color.parseColor("#5236DB"),
                accent = Color.parseColor("#00F5D4"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.OCEAN -> ThemeColors(
                primary = Color.parseColor("#00B4D8"),
                primaryDark = Color.parseColor("#0077B6"),
                accent = Color.parseColor("#90E0EF"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.FOREST -> ThemeColors(
                primary = Color.parseColor("#2EC4B6"),
                primaryDark = Color.parseColor("#1A936F"),
                accent = Color.parseColor("#88D49E"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
            Palette.SUNSET -> ThemeColors(
                primary = Color.parseColor("#FF6B6B"),
                primaryDark = Color.parseColor("#EE5253"),
                accent = Color.parseColor("#FFD166"),
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
                primary = Color.parseColor("#14E0B4"),
                primaryDark = Color.parseColor("#0A9E82"),
                accent = Color.parseColor("#FF9F43"),
                background = Color.BLACK,
                cardBackground = Color.parseColor("#0A0A0C"),
                isDark = true
            )
            Palette.DYNAMIC -> {
                val primaryColor = ContextCompat.getColor(context, R.color.brand_primary)
                ThemeColors(
                    primary = primaryColor,
                    primaryDark = ContextCompat.getColor(context, R.color.brand_primary_dark),
                    accent = ContextCompat.getColor(context, R.color.accent_streak),
                    background = baseBg,
                    cardBackground = baseCard,
                    isDark = isNight
                )
            }
            Palette.AURORA -> ThemeColors(
                primary = Color.parseColor("#14E0B4"),
                primaryDark = Color.parseColor("#0A9E82"),
                accent = Color.parseColor("#FF9F43"),
                background = baseBg,
                cardBackground = baseCard,
                isDark = isNight
            )
        }
    }
}
