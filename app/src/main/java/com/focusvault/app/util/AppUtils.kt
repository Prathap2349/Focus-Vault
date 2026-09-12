package com.focusvault.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

enum class AppCategory(val displayName: String) {
    ALL("All"),
    SOCIAL("Social"),
    ENTERTAINMENT("Entertainment"),
    GAMES("Games"),
    SHOPPING("Shopping"),
    PRODUCTIVITY("Productivity"),
    OTHER("Other")
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val category: AppCategory = AppCategory.OTHER
)

object AppUtils {

    /** Returns user-launchable apps categorised safely. Skips our own package. */
    fun getLaunchableApps(context: Context): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            emptyList()
        }

        return resolveInfos
            .filter { it.activityInfo?.packageName != null && it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { resolveInfo ->
                try {
                    val pkg = resolveInfo.activityInfo.packageName
                    val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }

                    val label = resolveInfo.loadLabel(pm)?.toString() ?: pkg
                    val icon = try { resolveInfo.loadIcon(pm) } catch (e: Exception) { null }
                    val category = classifyApp(pkg, appInfo)

                    InstalledAppInfo(
                        packageName = pkg,
                        label = label,
                        icon = icon,
                        category = category
                    )
                } catch (e: Exception) {
                    null // Skip uninstalled or invalid apps gracefully
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun classifyApp(packageName: String, appInfo: ApplicationInfo): AppCategory {
        val pkg = packageName.lowercase()

        // 1. Android API category matching
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAMES
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.ENTERTAINMENT
                ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
            }
        }

        // 2. Heuristic package matching for well-known apps
        return when {
            pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("twitter") ||
                pkg.contains("tiktok") || pkg.contains("snapchat") || pkg.contains("whatsapp") ||
                pkg.contains("telegram") || pkg.contains("reddit") || pkg.contains("discord") ||
                pkg.contains("threads") || pkg.contains("pinterest") || pkg.contains("linkedin") -> AppCategory.SOCIAL

            pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("spotify") ||
                pkg.contains("twitch") || pkg.contains("primevideo") || pkg.contains("hulu") ||
                pkg.contains("disney") || pkg.contains("hotstar") || pkg.contains("music") -> AppCategory.ENTERTAINMENT

            pkg.contains("game") || pkg.contains("pubg") || pkg.contains("roblox") ||
                pkg.contains("minecraft") || pkg.contains("supercell") || pkg.contains("candycrush") ||
                pkg.contains("epicgames") -> AppCategory.GAMES

            pkg.contains("amazon") || pkg.contains("ebay") || pkg.contains("flipkart") ||
                pkg.contains("walmart") || pkg.contains("aliexpress") || pkg.contains("target") ||
                pkg.contains("shein") || pkg.contains("temu") || pkg.contains("shopify") -> AppCategory.SHOPPING

            pkg.contains("office") || pkg.contains("docs") || pkg.contains("sheets") ||
                pkg.contains("notion") || pkg.contains("trello") || pkg.contains("slack") ||
                pkg.contains("asana") || pkg.contains("todoist") || pkg.contains("evernote") ||
                pkg.contains("zoom") || pkg.contains("teams") -> AppCategory.PRODUCTIVITY

            else -> AppCategory.OTHER
        }
    }
}
