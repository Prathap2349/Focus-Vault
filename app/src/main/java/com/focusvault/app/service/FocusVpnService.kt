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
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Narrowly scoped on-device DNS-filtering VPN for Focus Vault.
 *
 * ARCHITECTURE:
 * 1. Narrow /32 route (10.0.0.2/32):
 *    Only packets destined for the dummy DNS server 10.0.0.2 enter the tunnel.
 *    All web traffic (TCP HTTP/HTTPS, QUIC, VoIP, streaming, local LAN) bypasses
 *    the tunnel completely at the kernel routing level.
 * 2. App exclusion: Focus Vault is excluded from the VPN via addDisallowedApplication(packageName)
 *    so its forwarding sockets use the underlying physical network without looping back.
 * 3. Physical Network Binding & Underlying Networks:
 *    Underlying physical networks (Wi-Fi/Cellular) are registered with Android via setUnderlyingNetworks().
 * 4. Fast Upstream Forwarding with Last-Working Cache:
 *    Upstream DNS queries are routed to physical carrier/router DNS first, with cached
 *    working server prioritization and public fallback (8.8.8.8, 1.1.1.1).
 * 5. Instant TCP RST:
 *    TCP probes to 10.0.0.2:853 (DoT) or 53 immediately receive TCP RST (Connection Refused),
 *    preventing Android Private DNS probes from stalling the network.
 * 6. SERVFAIL Fast-Failure:
 *    If upstream DNS times out, SERVFAIL is returned so apps fail fast & retry instead of hanging.
 */
class FocusVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var running = false
    private val outputMutex = Mutex()

    /** Remembers the last upstream DNS server that successfully answered, prioritizing it for speed. */
    @Volatile
    private var lastWorkingDnsAddress: String? = null

    companion object {
        private const val TAG = "FocusVPN"
        const val CHANNEL_ID = "focus_vpn_channel"
        const val NOTIF_ID = 1002
        const val REVOKED_CHANNEL_ID = "focus_vpn_revoked_channel"
        const val REVOKED_NOTIF_ID = 1004
        const val ACTION_STOP = "com.focusvault.app.service.ACTION_STOP_VPN"

        /** Tunnel configuration constants */
        private const val TUNNEL_IP = "10.0.0.2"
        private const val TUNNEL_PREFIX = 32
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
        Log.i(TAG, "🚀 VPN ESTABLISHED on $TUNNEL_IP/$TUNNEL_PREFIX")
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
        Log.i(TAG, "🛑 VPN STOPPED")
        stopSelf()
    }

    private fun establishVpn() {
        val builder = Builder()
            .setSession("Focus Vault")
            .addAddress(TUNNEL_IP, TUNNEL_PREFIX)
            .addDnsServer(TUNNEL_IP)
            .addRoute(TUNNEL_IP, TUNNEL_PREFIX) // Narrow /32 route
            .setMtu(TUNNEL_MTU)
            .setBlocking(true)

        // Exclude Focus Vault itself from the VPN to guarantee that forwarding
        // sockets always reach the physical network without looping back.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add disallowed application: ${e.message}")
        }

        // Inform Android of the underlying physical networks (Wi-Fi / Cellular)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val physicalNetworks = cm.allNetworks.filter { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                         caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                }
                if (physicalNetworks.isNotEmpty()) {
                    setUnderlyingNetworks(physicalNetworks.toTypedArray())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set underlying networks: ${e.message}")
            }
        }

        vpnInterface = builder.establish()
    }

    private data class DnsEndpoint(val address: String, val network: Network?)

    /**
     * Discovers active physical network DNS servers (Wi-Fi, Cellular) that are
     * NOT part of the VPN. The last known working DNS server is placed at index 0.
     */
    private fun discoverUpstreamDnsServers(): List<DnsEndpoint> {
        val endpoints = mutableListOf<DnsEndpoint>()
        val seenIps = mutableSetOf<String>()

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val allNetworks = cm.allNetworks
            for (network in allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                // Exclude any VPN transport
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val isPhysical = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!isPhysical) continue

                val lp = cm.getLinkProperties(network) ?: continue
                for (dns in lp.dnsServers) {
                    val ip = dns.hostAddress
                    if (!ip.isNullOrEmpty() && ip != TUNNEL_IP && !ip.startsWith("127.") && seenIps.add(ip)) {
                        endpoints.add(DnsEndpoint(ip, network))
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

        // Prioritize the last working DNS server if present
        val cached = lastWorkingDnsAddress
        if (cached != null) {
            val idx = endpoints.indexOfFirst { it.address == cached }
            if (idx > 0) {
                val working = endpoints.removeAt(idx)
                endpoints.add(0, working)
            }
        }

        return endpoints
    }

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

            // Protect socket from VPN routing
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
            socket.soTimeout = 1500 // 1.5s timeout per server
            socket.send(forwardPacket)

            val replyBuffer = ByteArray(4096)
            val replyPacket = DatagramPacket(replyBuffer, replyBuffer.size)
            socket.receive(replyPacket)
            socket.close()

            lastWorkingDnsAddress = endpoint.address
            return replyPacket
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            return null
        }
    }

    private fun resolveDns(dnsPayload: ByteArray): DatagramPacket? {
        val endpoints = discoverUpstreamDnsServers()
        for (endpoint in endpoints) {
            val reply = forwardDnsQuery(dnsPayload, endpoint)
            if (reply != null && reply.length > 12) {
                Log.d(TAG, "UPSTREAM SUCCESS: ${endpoint.address}")
                return reply
            }
        }
        Log.w(TAG, "UPSTREAM TIMEOUT: all servers failed")
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

                if (udpDnsQuery == null) {
                    // Check if incoming packet is TCP (e.g. DoT on port 853 or TCP DNS on port 53).
                    // Send an immediate TCP RST so the client gets Connection Refused and
                    // falls back to UDP 53 without hanging for 5-10 seconds!
                    val rst = DnsPacketParser.buildTcpRstResponse(rawPacket, length)
                    if (rst != null) {
                        outputMutex.withLock { output.write(rst) }
                    }
                    continue
                }

                val queryDomain = udpDnsQuery.queryName
                Log.d(TAG, "DNS QUERY: $queryDomain")

                // Fetch active blocked domains
                val sessionBlocked = if (PrefsManager.isSessionCurrentlyActive(this))
                    PrefsManager.getBlockedDomains(this) else emptySet()
                val permanentBlocked = if (!PrefsManager.isPermanentBlockPaused(this))
                    PrefsManager.getPermanentBlockedDomains(this) else emptySet()
                val allBlocked = sessionBlocked + permanentBlocked

                val isBlocked = !PrefsManager.isEmergencyPauseActive(this) &&
                    DomainMatcher.isDomainBlocked(
                        queryDomain = queryDomain,
                        blockedDomains = allBlocked.filterNot { raw ->
                            val b = DomainMatcher.normalizeBlockedDomain(raw)
                            PrefsManager.isIndividualSitePaused(this@FocusVpnService, b)
                        }.toSet()
                    )

                if (isBlocked) {
                    Log.i(TAG, "🛑 BLOCKED: $queryDomain")
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
                        } else {
                            // If all upstreams fail, send SERVFAIL so requesting app fails fast & retries
                            try {
                                val servFail = DnsPacketParser.buildServFailResponse(rawCopy, lenCopy)
                                outputMutex.withLock { output.write(servFail) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed writing SERVFAIL for $queryDomain", e)
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
