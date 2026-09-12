package com.focusvault.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.focusvault.app.ui.MainActivity
import com.focusvault.app.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Local DNS-filtering VPN. It creates a TUN interface, routes only DNS (port 53) traffic
 * through it, inspects each outgoing DNS query's requested domain name, and:
 *  - if the domain (or its parent domain) is on the block list -> replies with NXDOMAIN
 *    locally, so the site never resolves and never loads.
 *  - otherwise -> forwards the query untouched to a real upstream DNS resolver and relays
 *    the real response back asynchronously without blocking the packet read loop.
 *
 * All other traffic (non-DNS) is NOT routed through the tunnel, so normal browsing speed
 * and non-web apps are unaffected. No browsing data ever leaves the device to any third
 * party server other than the DNS resolver the user's network already trusts.
 */
class FocusVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var running = false
    private val outputMutex = Mutex()

    companion object {
        const val CHANNEL_ID = "focus_vpn_channel"
        const val NOTIF_ID = 1002
        const val REVOKED_CHANNEL_ID = "focus_vpn_revoked_channel"
        const val REVOKED_NOTIF_ID = 1004
        const val UPSTREAM_DNS = "8.8.8.8"
        const val ACTION_STOP = "com.focusvault.app.service.ACTION_STOP_VPN"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpnInternal()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (running && vpnInterface != null) {
            return START_STICKY // Avoid duplicate establishment
        }
        try {
            establishVpn()
            if (vpnInterface == null) {
                com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(true)
        scope.launch { runTunnelLoop() }
        return START_STICKY
    }

    fun stopVpnInternal() {
        running = false
        com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
        try {
            vpnInterface?.close()
        } catch (e: Exception) {}
        vpnInterface = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (e: Exception) {}
        stopSelf()
    }

    /** A handful of well-known DNS-over-HTTPS/DNS-over-TLS resolver IPs that browsers (Chrome,
     * Firefox, etc.) hardcode as bootstrap addresses for "automatic" secure DNS. Queries sent
     * there go straight from the app to that IP over HTTPS/TLS and never touch the system DNS
     * server we configure below - so without this, a blocked domain could still resolve via
     * DoH and silently bypass site blocking entirely. This list is not exhaustive (custom or
     * self-hosted DoH resolvers aren't covered), but it catches the resolvers most browsers
     * ship with by default. */
    private val KNOWN_DOH_RESOLVER_IPS = listOf(
        "1.1.1.1", "1.0.0.1",             // Cloudflare
        "9.9.9.9", "149.112.112.112",     // Quad9
        "208.67.222.222", "208.67.220.220" // OpenDNS
    )

    private fun getActiveDnsServer(context: Context): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = cm.getLinkProperties(activeNetwork)
                val dnsServers = linkProperties?.dnsServers
                if (!dnsServers.isNullOrEmpty()) {
                    // Filter out 10.0.0.2 (our own tunnel) and any DoH IP we route into the tunnel
                    // to completely guarantee we never cause an infinite routing loop if protect() fails.
                    val ipv4Dns = dnsServers.firstOrNull { 
                        it is java.net.Inet4Address && it.hostAddress != "10.0.0.2" && !KNOWN_DOH_RESOLVER_IPS.contains(it.hostAddress)
                    }
                    val fallbackDns = dnsServers.firstOrNull { 
                        it.hostAddress != "10.0.0.2" && !KNOWN_DOH_RESOLVER_IPS.contains(it.hostAddress)
                    }
                    return ipv4Dns?.hostAddress ?: fallbackDns?.hostAddress ?: UPSTREAM_DNS
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return UPSTREAM_DNS
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession("Stay Focused")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.2") // force all DNS through us
            .addRoute("10.0.0.0", 8) // narrow route; we only actually care about DNS
            .setMtu(1500)

        // We previously routed traffic to known DoH/DoT bootstrap IPs here.
        // However, this caused all Private DNS traffic (e.g. DoT on port 853) to be routed
        // into the tunnel, where it was silently dropped by extractDnsQuery (which only handles
        // plain UDP/port 53). Dropping Private DNS breaks system-wide internet resolution.
        // By removing these /32 routes, DoT/DoH traffic explicitly passes through natively.
        // KNOWN_DOH_RESOLVER_IPS.forEach { ip -> builder.addRoute(ip, 32) }

        vpnInterface = builder.establish()
    }

    private suspend fun runTunnelLoop() {
        val fd = vpnInterface ?: run {
            com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
            return
        }
        try {
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(32767)

            while (running) {
                val length = try { input.read(buffer) } catch (e: Exception) { break }
                if (length <= 0) continue

                val rawPacket = buffer.copyOf(length)
                val packet = ByteBuffer.wrap(rawPacket, 0, length)
                val udpDnsQuery = DnsPacketParser.extractDnsQuery(packet) ?: continue

                val sessionBlocked = if (PrefsManager.isSessionCurrentlyActive(this)) PrefsManager.getBlockedDomains(this) else emptySet()
                val permanentBlocked = if (!PrefsManager.isPermanentBlockPaused(this)) PrefsManager.getPermanentBlockedDomains(this) else emptySet()
                val allBlockedDomains = sessionBlocked + permanentBlocked

                val isBlocked = !PrefsManager.isEmergencyPauseActive(this) && allBlockedDomains.any { rawBlocked ->
                    val blocked = rawBlocked.removePrefix("*.").removePrefix("www.").lowercase()
                    val qName = udpDnsQuery.queryName.removePrefix("www.").lowercase()
                    qName == blocked || qName.endsWith(".$blocked")
                }

                if (isBlocked) {
                    android.util.Log.d("FocusVaultVPN", "🛑 BLOCKED: ${udpDnsQuery.queryName}")
                    com.focusvault.app.manager.SessionStateManager.recordDistractionAttempt(applicationContext)
                    val nxDomainResponse = DnsPacketParser.buildNxDomainResponse(rawPacket, length)
                    outputMutex.withLock {
                        output.write(nxDomainResponse)
                    }
                } else {
                    val queryDomain = udpDnsQuery.queryName
                    android.util.Log.d("FocusVaultVPN", "✅ ALLOWED: $queryDomain")
                    if (PrefsManager.isSessionCurrentlyActive(this)) {
                        PrefsManager.recordQueriedDomain(this, queryDomain)
                    }

                    // Forward to upstream resolver asynchronously so the TUN read loop is never blocked
                    val dnsPayload = udpDnsQuery.rawDnsPayload
                    scope.launch(Dispatchers.IO) {
                        try {
                            val upstreamSocket = DatagramSocket()
                            protect(upstreamSocket) // exclude this socket from the VPN to avoid a loop
                            val activeDns = getActiveDnsServer(applicationContext)
                            val forwardPacket = java.net.DatagramPacket(
                                dnsPayload, dnsPayload.size, InetSocketAddress(activeDns, 53)
                            )
                            upstreamSocket.send(forwardPacket)

                            val replyBuf = ByteArray(4096)
                            val replyPacket = java.net.DatagramPacket(replyBuf, replyBuf.size)
                            upstreamSocket.soTimeout = 3000
                            upstreamSocket.receive(replyPacket)
                            upstreamSocket.close()

                            val fullReply = DnsPacketParser.wrapDnsReplyIntoIpPacket(
                                originalRequestPacket = rawPacket,
                                originalLength = length,
                                dnsAnswer = replyPacket.data.copyOf(replyPacket.length)
                            )
                            outputMutex.withLock {
                                output.write(fullReply)
                            }
                        } catch (e: Exception) {
                            // Upstream failure: drop silently, browser/app will just retry or time out
                        }
                    }
                }
            }
        } finally {
            running = false
            com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Focus Website Blocking", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Vault is blocking distracting sites")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        var startedSuccessfully = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    startedSuccessfully = true
                } catch (e: Exception) {
                    android.util.Log.w("FocusVpnService", "Failed to start with SPECIAL_USE type, retrying without type", e)
                    startForeground(NOTIF_ID, notification)
                    startedSuccessfully = true
                }
            } else {
                startForeground(NOTIF_ID, notification)
                startedSuccessfully = true
            }
        } catch (e: Exception) {
            android.util.Log.e("FocusVpnService", "Failed to start foreground notification", e)
        }

        if (!startedSuccessfully) {
            android.util.Log.e("FocusVpnService", "VPN Service could not start foreground notification; stopping self")
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
        serviceJob.cancel()
        vpnInterface?.close()
        vpnInterface = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onRevoke() {
        // User revoked VPN permission from system settings
        com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
        if (PrefsManager.isSessionCurrentlyActive(this)) {
            notifyVpnRevoked()
        }
        running = false
        stopSelf()
        super.onRevoke()
    }

    private fun notifyVpnRevoked() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(REVOKED_CHANNEL_ID, "Website Blocking Interrupted", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, REVOKED_CHANNEL_ID)
            .setContentTitle("Website blocking has stopped")
            .setContentText("VPN permission was revoked. Open Stay Focused to restore site blocking for this session.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent)
            .build()
        try {
            nm.notify(REVOKED_NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // No notification permission - nothing more we can do to surface this proactively.
        }
    }
}
