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
import android.util.Log
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
 * Local DNS-filtering VPN — complete rewrite for reliability.
 *
 * Key design principles (learned from 15 iterations of debugging):
 *  1. Capture the REAL upstream DNS server BEFORE the VPN is established,
 *     because once the VPN is up, ConnectivityManager may report the VPN's
 *     own address (10.0.0.2) as the DNS server → forwarding loop.
 *  2. ALWAYS check protect() return value. If it fails, the forwarding
 *     socket's traffic goes back into the VPN → infinite loop → ALL DNS dies.
 *  3. Log every step so failures are visible in `adb logcat -s FocusVPN`.
 *  4. Never swallow exceptions silently.
 */
class FocusVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var running = false
    private val outputMutex = Mutex()

    /** The REAL upstream DNS server, captured BEFORE the VPN is established. */
    @Volatile
    private var capturedUpstreamDns: String = "8.8.8.8"

    companion object {
        private const val TAG = "FocusVPN"
        const val CHANNEL_ID = "focus_vpn_channel"
        const val NOTIF_ID = 1002
        const val REVOKED_CHANNEL_ID = "focus_vpn_revoked_channel"
        const val REVOKED_NOTIF_ID = 1004
        const val UPSTREAM_DNS = "8.8.8.8"
        const val ACTION_STOP = "com.focusvault.app.service.ACTION_STOP_VPN"

        /** DNS servers to try if the captured one fails. */
        private val FALLBACK_DNS = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "9.9.9.9")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpnInternal()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        if (running && vpnInterface != null) {
            return START_STICKY
        }
        try {
            // *** CRITICAL: Capture DNS BEFORE establishing VPN ***
            capturedUpstreamDns = discoverRealDnsServer()
            Log.i(TAG, "✅ Captured real DNS before VPN: $capturedUpstreamDns")

            establishVpn()
            if (vpnInterface == null) {
                Log.e(TAG, "❌ VPN interface is null after establish()")
                com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to establish VPN", e)
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
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    /**
     * Discovers the device's REAL DNS server BEFORE the VPN is established.
     * After the VPN starts, ConnectivityManager may return 10.0.0.2 (our tunnel),
     * which would create a forwarding loop that kills ALL DNS resolution.
     */
    private fun discoverRealDnsServer(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val lp = cm.getLinkProperties(activeNetwork)
                val servers = lp?.dnsServers
                if (!servers.isNullOrEmpty()) {
                    Log.d(TAG, "System DNS servers: ${servers.map { it.hostAddress }}")
                    // Pick the first IPv4 DNS that isn't our tunnel
                    val ipv4 = servers.firstOrNull {
                        it is java.net.Inet4Address &&
                        it.hostAddress != "10.0.0.2" &&
                        !it.hostAddress.isNullOrEmpty()
                    }
                    if (ipv4 != null) {
                        Log.d(TAG, "Using system IPv4 DNS: ${ipv4.hostAddress}")
                        return ipv4.hostAddress!!
                    }
                    // Fallback: any non-tunnel address
                    val any = servers.firstOrNull {
                        it.hostAddress != "10.0.0.2" && !it.hostAddress.isNullOrEmpty()
                    }
                    if (any != null) {
                        Log.d(TAG, "Using system DNS (non-IPv4): ${any.hostAddress}")
                        return any.hostAddress!!
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to discover DNS server", e)
        }
        Log.w(TAG, "No system DNS found, falling back to $UPSTREAM_DNS")
        return UPSTREAM_DNS
    }

    /**
     * Forwards a DNS query to the given server. Returns the response or null.
     * CRITICAL: Checks protect() return value to prevent routing loops.
     */
    private fun forwardDnsQuery(dnsPayload: ByteArray, serverIp: String): java.net.DatagramPacket? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()

            // *** CRITICAL: Check protect() return value ***
            val protected = protect(socket)
            if (!protected) {
                Log.e(TAG, "❌ protect() FAILED for $serverIp — aborting to prevent routing loop")
                socket.close()
                return null
            }

            val fwd = java.net.DatagramPacket(
                dnsPayload, dnsPayload.size,
                InetSocketAddress(serverIp, 53)
            )
            socket.soTimeout = 5000 // 5 seconds (was 3, too aggressive for slow networks)
            socket.send(fwd)

            val replyBuf = ByteArray(4096)
            val reply = java.net.DatagramPacket(replyBuf, replyBuf.size)
            socket.receive(reply)
            socket.close()
            return reply
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ DNS forward to $serverIp failed: ${e.javaClass.simpleName}: ${e.message}")
            try { socket?.close() } catch (_: Exception) {}
            return null
        }
    }

    /**
     * Tries forwarding a DNS query through multiple servers until one succeeds.
     * Uses the pre-captured DNS server first, then fallbacks.
     */
    private fun resolveWithFallback(dnsPayload: ByteArray): java.net.DatagramPacket? {
        // Build ordered list: captured DNS first, then fallbacks (deduplicated)
        val servers = mutableListOf(capturedUpstreamDns)
        FALLBACK_DNS.forEach { if (it != capturedUpstreamDns) servers.add(it) }

        for (server in servers) {
            val reply = forwardDnsQuery(dnsPayload, server)
            if (reply != null) return reply
        }

        Log.e(TAG, "❌ ALL DNS servers failed! Tried: $servers")
        return null
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession("Focus Vault")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.2")
            .addRoute("10.0.0.0", 8)
            .setMtu(1500)

        vpnInterface = builder.establish()
        Log.i(TAG, "VPN established: fd=${vpnInterface?.fd}")
    }

    private suspend fun runTunnelLoop() {
        val fd = vpnInterface ?: run {
            Log.e(TAG, "TUN fd is null, aborting tunnel loop")
            com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
            return
        }

        Log.i(TAG, "🚀 Tunnel loop started. Upstream DNS: $capturedUpstreamDns")

        try {
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(32767)

            while (running) {
                val length = try { input.read(buffer) } catch (e: Exception) {
                    Log.w(TAG, "TUN read error: ${e.message}")
                    break
                }
                if (length <= 0) continue

                val rawPacket = buffer.copyOf(length)
                val packet = ByteBuffer.wrap(rawPacket, 0, length)
                val udpDnsQuery = DnsPacketParser.extractDnsQuery(packet)

                if (udpDnsQuery == null) {
                    // Not a standard UDP/port-53 DNS query.
                    // Try extracting as any-port UDP (handles DoT redirect, etc.)
                    val fallback = DnsPacketParser.extractDnsQueryAnyPort(
                        ByteBuffer.wrap(rawPacket, 0, length)
                    )
                    if (fallback != null) {
                        Log.d(TAG, "📡 Non-port-53 DNS for: ${fallback.queryName} (port ${fallback.destPort})")
                        val payload = fallback.rawDnsPayload
                        val raw = rawPacket.copyOf(length)
                        val len = length
                        scope.launch(Dispatchers.IO) {
                            val reply = resolveWithFallback(payload)
                            if (reply != null) {
                                try {
                                    val fullReply = DnsPacketParser.wrapDnsReplyIntoIpPacket(
                                        originalRequestPacket = raw,
                                        originalLength = len,
                                        dnsAnswer = reply.data.copyOf(reply.length)
                                    )
                                    outputMutex.withLock { output.write(fullReply) }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to write non-53 DNS reply", e)
                                }
                            }
                        }
                    }
                    continue
                }

                // --- Standard UDP/53 DNS query ---
                val queryDomain = udpDnsQuery.queryName

                // Build the set of currently blocked domains
                val sessionBlocked = if (PrefsManager.isSessionCurrentlyActive(this))
                    PrefsManager.getBlockedDomains(this) else emptySet()
                val permanentBlocked = if (!PrefsManager.isPermanentBlockPaused(this))
                    PrefsManager.getPermanentBlockedDomains(this) else emptySet()
                val allBlockedDomains = sessionBlocked + permanentBlocked

                val isBlocked = !PrefsManager.isEmergencyPauseActive(this) &&
                    allBlockedDomains.any { rawBlocked ->
                        val blocked = rawBlocked.removePrefix("*.").removePrefix("www.").lowercase()
                        val qName = queryDomain.removePrefix("www.").lowercase()
                        val matches = qName == blocked || qName.endsWith(".$blocked")
                        matches && !PrefsManager.isIndividualSitePaused(this@FocusVpnService, blocked)
                    }

                if (isBlocked) {
                    Log.d(TAG, "🛑 BLOCKED: $queryDomain")
                    com.focusvault.app.manager.SessionStateManager.recordDistractionAttempt(applicationContext)
                    val nxResponse = DnsPacketParser.buildNxDomainResponse(rawPacket, length)
                    outputMutex.withLock { output.write(nxResponse) }
                } else {
                    Log.d(TAG, "✅ ALLOWED: $queryDomain")
                    if (PrefsManager.isSessionCurrentlyActive(this)) {
                        PrefsManager.recordQueriedDomain(this, queryDomain)
                    }

                    // Forward asynchronously so we don't block the read loop
                    val dnsPayload = udpDnsQuery.rawDnsPayload
                    val rawCopy = rawPacket.copyOf(length)
                    val lenCopy = length
                    scope.launch(Dispatchers.IO) {
                        val reply = resolveWithFallback(dnsPayload)
                        if (reply != null) {
                            try {
                                val fullReply = DnsPacketParser.wrapDnsReplyIntoIpPacket(
                                    originalRequestPacket = rawCopy,
                                    originalLength = lenCopy,
                                    dnsAnswer = reply.data.copyOf(reply.length)
                                )
                                outputMutex.withLock { output.write(fullReply) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to write DNS reply for $queryDomain", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel loop crashed", e)
        } finally {
            running = false
            com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
            Log.i(TAG, "Tunnel loop ended")
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
                    Log.w(TAG, "Failed to start with SPECIAL_USE type, retrying without", e)
                    startForeground(NOTIF_ID, notification)
                    startedSuccessfully = true
                }
            } else {
                startForeground(NOTIF_ID, notification)
                startedSuccessfully = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification", e)
        }
        if (!startedSuccessfully) {
            Log.e(TAG, "Could not start foreground notification; stopping")
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
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onRevoke() {
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
            .setContentText("VPN permission was revoked. Open Focus Vault to restore site blocking.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent)
            .build()
        try { nm.notify(REVOKED_NOTIF_ID, notification) } catch (_: SecurityException) {}
    }
}
