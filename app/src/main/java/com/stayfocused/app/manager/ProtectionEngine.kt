package com.stayfocused.app.manager

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.stayfocused.app.service.AppBlockAccessibilityService
import com.stayfocused.app.service.StayFocusedDeviceAdminReceiver
import com.stayfocused.app.util.PrefsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class ProtectionStatus {
    PROTECTION_ACTIVE,
    PROTECTION_DEGRADED,
    PROTECTION_FAILED
}

data class HealthItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val isHealthy: Boolean,
    val isRequired: Boolean,
    val fixActionTitle: String = "Fix",
    val fixIntent: Intent? = null
)

data class ProtectionReport(
    val status: ProtectionStatus,
    val scorePercentage: Int,
    val items: List<HealthItem>,
    val headlineMessage: String,
    val lastCheckedTimestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedLastChecked(): String {
        val secondsAgo = ((System.currentTimeMillis() - lastCheckedTimestamp) / 1000L).coerceAtLeast(0L)
        return when {
            secondsAgo < 5 -> "just now"
            secondsAgo < 60 -> "$secondsAgo seconds ago"
            else -> "${secondsAgo / 60}m ago"
        }
    }
}

object ProtectionEngine {

    val isAccessibilityBound = AtomicBoolean(false)
    val isVpnRunning = AtomicBoolean(false)
    val isTimerServiceRunning = AtomicBoolean(false)
    val lastTimerHeartbeat = java.util.concurrent.atomic.AtomicLong(0L)

    private val _reportFlow = MutableStateFlow<ProtectionReport?>(null)
    val reportFlow: StateFlow<ProtectionReport?> = _reportFlow.asStateFlow()

    fun evaluate(context: Context): ProtectionReport {
        val items = mutableListOf<HealthItem>()

        // 1. Accessibility Service check
        val a11ySettingsOn = isAccessibilitySettingsEnabled(context)
        val a11yActive = a11ySettingsOn && isAccessibilityBound.get()
        items.add(
            HealthItem(
                id = "accessibility",
                title = "App Blocking (Accessibility)",
                subtitle = if (a11yActive) "Active and blocking apps"
                else if (a11ySettingsOn) "Service enabled but awaiting connection"
                else "Disabled in Android Settings - apps cannot be blocked",
                isHealthy = a11yActive,
                isRequired = true,
                fixActionTitle = "Enable",
                fixIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        )

        // 2. Website Blocking (VPN) check
        val hasBlockedSites = PrefsManager.getBlockedDomains(context).isNotEmpty()
        val vpnPrepared = VpnService.prepare(context) == null
        val vpnHealthy = if (!hasBlockedSites) {
            true
        } else {
            vpnPrepared && isVpnRunning.get()
        }
        items.add(
            HealthItem(
                id = "vpn",
                title = "Website Blocking (DNS Filter)",
                subtitle = if (!hasBlockedSites) "No websites configured to block (VPN inactive)"
                else if (isVpnRunning.get()) "Active and filtering DNS queries"
                else if (!vpnPrepared) "VPN permission not yet granted"
                else "VPN service inactive",
                isHealthy = vpnHealthy,
                isRequired = hasBlockedSites,
                fixActionTitle = "Configure",
                fixIntent = VpnService.prepare(context)
            )
        )

        // 3. Focus Timer & Session Engine check
        val isSessionActive = PrefsManager.isSessionCurrentlyActive(context)
        val sessionStateValid = PrefsManager.getSessionState(context) != com.stayfocused.app.data.SessionState.PROTECTION_FAILED
        val now = System.currentTimeMillis()
        val heartbeatAge = now - lastTimerHeartbeat.get()
        val timerAlive = !isSessionActive || (isTimerServiceRunning.get() && heartbeatAge < 10000L)
        val sessionEngineHealthy = sessionStateValid && timerAlive

        val sessionSubtitle = if (!sessionStateValid) {
            "Session state failed - restart session"
        } else if (isSessionActive && !timerAlive) {
            "Timer service stopped unexpectedly mid-session"
        } else if (isSessionActive) {
            "Foreground countdown & protection active"
        } else {
            "Session engine ready and idle"
        }

        items.add(
            HealthItem(
                id = "session_engine",
                title = "Session Engine & Timer",
                subtitle = sessionSubtitle,
                isHealthy = sessionEngineHealthy,
                isRequired = true,
                fixActionTitle = "Diagnose",
                fixIntent = Intent(context, com.stayfocused.app.ui.DiagnosticsActivity::class.java)
            )
        )

        // 4. Notification permission check
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        items.add(
            HealthItem(
                id = "notifications",
                title = "Focus Notifications",
                subtitle = if (notificationsGranted) "Granted - countdown alerts active"
                else "Disabled - live countdown notification unavailable",
                isHealthy = notificationsGranted,
                isRequired = true,
                fixActionTitle = "Allow",
                fixIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        )

        // 5. Battery Optimization / Doze check
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryWhitelisted = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        items.add(
            HealthItem(
                id = "battery",
                title = "Background Execution (Doze)",
                subtitle = if (batteryWhitelisted) "Unrestricted background timer"
                else "Battery saver may throttle background countdown",
                isHealthy = batteryWhitelisted,
                isRequired = false,
                fixActionTitle = "Optimize",
                fixIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        )

        // 6. Device Admin check
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, StayFocusedDeviceAdminReceiver::class.java)
        val adminActive = dpm?.isAdminActive(adminComponent) ?: false
        items.add(
            HealthItem(
                id = "admin",
                title = "Uninstall Protection (Device Admin)",
                subtitle = if (adminActive) "Device admin active - blocks instant uninstall"
                else "Optional friction against accidental app removal",
                isHealthy = adminActive,
                isRequired = false,
                fixActionTitle = "Activate",
                fixIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                }
            )
        )

        // 7. Security / App Lock check
        val hasPin = PrefsManager.hasLockPin(context)
        items.add(
            HealthItem(
                id = "security_pin",
                title = "Security PIN & Lockout",
                subtitle = if (hasPin) "Salted SHA-256 PIN active with brute-force lockout"
                else "PIN protection optional - tap to set up",
                isHealthy = true, // Optional but healthy
                isRequired = false,
                fixActionTitle = "Set PIN",
                fixIntent = Intent(context, com.stayfocused.app.ui.SettingsActivity::class.java)
            )
        )

        // 8. Boot Recovery check
        val bootReceiverComponent = ComponentName(context, com.stayfocused.app.service.BootReceiver::class.java)
        val pmPackage = context.packageManager
        val bootEnabled = pmPackage.getComponentEnabledSetting(bootReceiverComponent) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        items.add(
            HealthItem(
                id = "boot_recovery",
                title = "Reboot Protection Recovery",
                subtitle = if (bootEnabled) "Active - restores focus sessions after phone restart"
                else "Disabled in package manager",
                isHealthy = bootEnabled,
                isRequired = true,
                fixActionTitle = "Enable",
                fixIntent = null
            )
        )

        // 9. Home-Screen Widget Provider check
        val widgetCount = com.stayfocused.app.appwidget.WidgetUpdater.getActiveWidgetCount(context)
        val lastUpdate = com.stayfocused.app.appwidget.WidgetUpdater.lastUpdateTimestamp.get()
        val updateSub = if (widgetCount > 0) {
            val syncText = if (lastUpdate > 0) "Synced" else "Ready"
            "$widgetCount active instance(s) · $syncText"
        } else {
            "Provider ready · 0 active home screen instances"
        }
        items.add(
            HealthItem(
                id = "widget_provider",
                title = "Home-Screen Widget Engine",
                subtitle = updateSub,
                isHealthy = true,
                isRequired = false,
                fixActionTitle = "Refresh",
                fixIntent = null
            )
        )

        val totalScore = items.count { it.isHealthy }
        val percentage = (totalScore * 100) / items.size

        val status = when {
            !a11yActive -> ProtectionStatus.PROTECTION_FAILED
            items.any { it.isRequired && !it.isHealthy } -> ProtectionStatus.PROTECTION_DEGRADED
            else -> ProtectionStatus.PROTECTION_ACTIVE
        }

        val headline = when (status) {
            ProtectionStatus.PROTECTION_ACTIVE -> "Protection 100% active · All systems operational"
            ProtectionStatus.PROTECTION_DEGRADED -> "Protection degraded · Some protections unavailable"
            ProtectionStatus.PROTECTION_FAILED -> "Protection failed · App blocking is disabled"
        }

        val report = ProtectionReport(status, percentage, items, headline)
        _reportFlow.value = report
        return report
    }

    /**
     * Checks if protection failed mid-session and sends an urgent notification
     */
    fun notifyFailureIfSessionActive(context: Context, reason: String) {
        if (!PrefsManager.isSessionCurrentlyActive(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        val channelId = "protection_failure_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Protection Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, com.stayfocused.app.ui.DiagnosticsActivity::class.java)
        val pi = android.app.PendingIntent.getActivity(
            context, 999, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setContentTitle("⚠️ Protection Degraded")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(9991, notif)
        } catch (e: SecurityException) { }
    }

    private fun isAccessibilitySettingsEnabled(context: Context): Boolean {
        val expected = ComponentName(context, AppBlockAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
        }
        return false
    }
}
