package com.focusvault.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Narrowly scoped on-device DNS-filtering VPN for Focus Vault.
 *
 * ARCHITECTURE GUARANTEES:
 * 1. NARROW TUNNEL ROUTE: We route ONLY the dummy DNS IP (10.0.0.2/32).
 *    Non-DNS traffic (HTTP, HTTPS, TCP, QUIC, VoIP, streaming, local LAN)
 *    does NOT match 10.0.0.2/32 and completely bypasses the tunnel at the
 *    kernel routing level. It is NEVER captured, diverted, or dropped.
 *
 * 2. APP EXCLUSION: Focus Vault itself is excluded from the VPN via
 *    addDisallowedApplication(packageName) so its own sockets automatically
 *    use the underlying physical network.
 *
 * 3. REAL UPSTREAM DNS RESOLUTION: Upstream queries are directed first to
 *    the device's real physical network DNS (Wi-Fi DHCP or Cellular carrier DNS),
 *    with protect(socket) and network.bindSocket(socket) to prevent routing loops.
 *    Public resolvers (8.8.8.8, 1.1.1.1) serve strictly as fallbacks.
 *
 * 4. STRICT DOMAIN MATCHING: Domain blocking uses exact boundary matching
 *    (DomainMatcher.isDomainBlocked) so blocking "youtube.com" blocks
 *    youtube.com, www.youtube.com, m.youtube.com, music.youtube.com,
 *    but NEVER blocks google.com, github.com, notyoutube.com, or youtube.com.example.com.
 */
class FocusVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var running = false
    private val outputMutex = Mutex()

    companion object {
        private const val TAG = "FocusVPN"
        const val CHANNEL_ID = "focus_vpn_channel"
        const val NOTIF_ID = 1002
        const val REVOKED_CHANNEL_ID = "focus_vpn_revoked_channel"
        const val REVOKED_NOTIF_ID = 1004
        const val ACTION_STOP = "com.focusvault.app.service.ACTION_STOP_VPN"

        /** Tunnel configuration constants */
        private const val TUNNEL_IP = "10.0.0.2"
        private const val TUNNEL_PREFIX = 32 // Exactly 1 IP: 10.0.0.2/32
        private const val TUNNEL_MTU = 1500

        /** Public DNS fallbacks used only if the physical carrier/Wi-Fi DNS fails */
        private val PUBLIC_FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1", "8.8.4.4", "9.9.9.9")
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
            establishVpn()
            if (vpnInterface == null) {
                Log.e(TAG, "❌ VPN interface could not be established; stopping service")
                com.focusvault.app.manager.ProtectionEngine.isVpnRunning.set(false)
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception establishing VPN", e)
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

    private fun establishVpn() {
        val builder = Builder()
            .setSession("Focus Vault")
            .addAddress(TUNNEL_IP, TUNNEL_PREFIX)
            .addDnsServer(TUNNEL_IP)
            // *** CRITICAL ARCHITECTURAL DECISION ***
            // Route ONLY the dummy DNS IP 10.0.0.2/32 into the tunnel.
            // DO NOT route 10.0.0.0/8 or 0.0.0.0/0. This guarantees non-DNS
            // traffic is never captured or dropped.
            .addRoute(TUNNEL_IP, TUNNEL_PREFIX)
            .setMtu(TUNNEL_MTU)
            .setBlocking(true)

        // Exclude Focus Vault itself from the VPN to guarantee that forwarding
        // sockets always reach the physical network without looping back.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add disallowed application: ${e.message}")
        }

        vpnInterface = builder.establish()
        Log.i(TAG, "✅ VPN established on $TUNNEL_IP/$TUNNEL_PREFIX")
    }

    /**
     * Discovers active physical network DNS servers (Wi-Fi, Cellular) that are
     * NOT part of the VPN. Returns an ordered list of DNS endpoints to try.
     */
    private data class DnsEndpoint(val address: String, val network: Network?)

    private fun discoverUpstreamDnsServers(): List<DnsEndpoint> {
        val endpoints = mutableListOf<DnsEndpoint>()
        val seenIps = mutableSetOf<String>()

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val allNetworks = cm.allNetworks
            for (network in allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                // Exclude any VPN transport to avoid loops
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

                val lp = cm.getLinkProperties(network) ?: continue
                for (dns in lp.dnsServers) {
                    val ip = dns.hostAddress
                    if (!ip.isNullOrEmpty() && ip != TUNNEL_IP && !ip.startsWith("127.") && seenIps.add(ip)) {
                        if (dns is Inet4Address) {
                            endpoints.add(DnsEndpoint(ip, network))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering physical network DNS", e)
        }

        // Add public DNS fallbacks
        for (fallback in PUBLIC_FALLBACK_DNS) {
            if (seenIps.add(fallback)) {
                endpoints.add(DnsEndpoint(fallback, null))
            }
        }

        return endpoints
    }

    /**
     * Forwards a raw DNS payload to an upstream DNS server via a protected socket.
     * Checks protect() return value; if protection fails, safely aborts to prevent loops.
     */
    private fun forwardDnsQuery(dnsPayload: ByteArray, endpoint: DnsEndpoint): DatagramPacket? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()

            // Bind to physical network if known
            if (endpoint.network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    endpoint.network.bindSocket(socket)
                } catch (e: Exception) {
                    Log.d(TAG, "Could not bind to network: ${e.message}")
                }
            }

            // Protect from VPN routing
            val isProtected = protect(socket)
            if (!isProtected) {
                Log.e(TAG, "❌ protect() returned false for ${endpoint.address} — aborting to avoid loop")
                socket.close()
                return null
            }

            val forwardPacket = DatagramPacket(
                dnsPayload, dnsPayload.size,
                InetSocketAddress(endpoint.address, 53)
            )
            socket.soTimeout = 2500 // 2.5s per server
            socket.send(forwardPacket)

            val replyBuffer = ByteArray(4096)
            val replyPacket = DatagramPacket(replyBuffer, replyBuffer.size)
            socket.receive(replyPacket)
            socket.close()
            return replyPacket
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            return null
        }
    }

    /**
     * Resolves a DNS query through the discovered upstream servers.
     */
    private fun resolveDns(dnsPayload: ByteArray): DatagramPacket? {
        val endpoints = discoverUpstreamDnsServers()
        for (endpoint in endpoints) {
            val reply = forwardDnsQuery(dnsPayload, endpoint)
            if (reply != null && reply.length > 12) {
                return reply
            }
        }
        return null
    }

    private suspend fun runTunnelLoop() {
        val fd = vpnInterface ?: run {
            Log.e(TAG, "TUN fd is null; aborting loop")
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
                val udpDnsQuery = DnsPacketParser.extractDnsQuery(packet)

                // If not standard IPv4 UDP port 53 DNS, safely skip.
                // Because our route is 10.0.0.2/32, nothing other than traffic to 10.0.0.2
                // ever reaches here, so non-DNS Internet traffic is never affected.
                if (udpDnsQuery == null) continue

                val queryDomain = udpDnsQuery.queryName

                // Fetch currently active blocked domains
                val sessionBlocked = if (PrefsManager.isSessionCurrentlyActive(this))
                    PrefsManager.getBlockedDomains(this) else emptySet()
                val permanentBlocked = if (!PrefsManager.isPermanentBlockPaused(this))
                    PrefsManager.getPermanentBlockedDomains(this) else emptySet()
                val allBlocked = sessionBlocked + permanentBlocked

                // Check emergency pause and individual pauses
                val isBlocked = !PrefsManager.isEmergencyPauseActive(this) &&
                    DomainMatcher.isDomainBlocked(
                        queryDomain = queryDomain,
                        blockedDomains = allBlocked.filterNot { raw ->
                            val b = DomainMatcher.normalizeBlockedDomain(raw)
                            PrefsManager.isIndividualSitePaused(this@FocusVpnService, b)
                        }.toSet()
                    )

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

                    val dnsPayload = udpDnsQuery.rawDnsPayload
                    val rawCopy = rawPacket.copyOf(length)
                    val lenCopy = length

                    scope.launch(Dispatchers.IO) {
                        val reply = resolveDns(dnsPayload)
                        if (reply != null) {
                            try {
                                val fullReply = DnsPacketParser.wrapDnsReplyIntoIpPacket(
                                    originalRequestPacket = rawCopy,
                                    originalLength = lenCopy,
                                    dnsAnswer = reply.data.copyOf(reply.length)
                                )
                                outputMutex.withLock { output.write(fullReply) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed writing reply for $queryDomain", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel loop terminated", e)
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
            Log.e(TAG, "Could not start foreground notification; stopping self")
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
