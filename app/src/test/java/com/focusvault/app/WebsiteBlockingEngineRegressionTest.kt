package com.focusvault.app

import com.focusvault.app.service.DnsPacketParser
import com.focusvault.app.service.DomainMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WebsiteBlockingEngineRegressionTest {

    // =========================================================================
    // 1. One blocked domain + unrelated domains
    // =========================================================================
    @Test
    fun testSingleBlockedDomainAllowsUnrelatedDomains() {
        val blocked = setOf("youtube.com")

        // Blocked targets
        assertTrue("youtube.com must be blocked", DomainMatcher.isDomainBlocked("youtube.com", blocked))
        assertTrue("www.youtube.com must be blocked", DomainMatcher.isDomainBlocked("www.youtube.com", blocked))
        assertTrue("m.youtube.com must be blocked", DomainMatcher.isDomainBlocked("m.youtube.com", blocked))
        assertTrue("music.youtube.com must be blocked", DomainMatcher.isDomainBlocked("music.youtube.com", blocked))

        // Allowed unrelated domains
        assertFalse("google.com must be allowed", DomainMatcher.isDomainBlocked("google.com", blocked))
        assertFalse("github.com must be allowed", DomainMatcher.isDomainBlocked("github.com", blocked))
        assertFalse("wikipedia.org must be allowed", DomainMatcher.isDomainBlocked("wikipedia.org", blocked))
        assertFalse("example.com must be allowed", DomainMatcher.isDomainBlocked("example.com", blocked))
        assertFalse("android.com must be allowed", DomainMatcher.isDomainBlocked("android.com", blocked))
    }

    // =========================================================================
    // 2. Multiple blocked domains
    // =========================================================================
    @Test
    fun testMultipleBlockedDomains() {
        val blocked = setOf("youtube.com", "instagram.com", "reddit.com")

        assertTrue(DomainMatcher.isDomainBlocked("youtube.com", blocked))
        assertTrue(DomainMatcher.isDomainBlocked("instagram.com", blocked))
        assertTrue(DomainMatcher.isDomainBlocked("api.instagram.com", blocked))
        assertTrue(DomainMatcher.isDomainBlocked("reddit.com", blocked))
        assertTrue(DomainMatcher.isDomainBlocked("old.reddit.com", blocked))

        assertFalse(DomainMatcher.isDomainBlocked("google.com", blocked))
        assertFalse(DomainMatcher.isDomainBlocked("twitter.com", blocked))
        assertFalse(DomainMatcher.isDomainBlocked("stackoverflow.com", blocked))
    }

    // =========================================================================
    // 3. Subdomains matching
    // =========================================================================
    @Test
    fun testDeepSubdomainMatching() {
        val blocked = setOf("youtube.com")

        assertTrue(DomainMatcher.isDomainBlocked("s.youtube.com", blocked))
        assertTrue(DomainMatcher.isDomainBlocked("sub.deep.youtube.com", blocked))
        assertTrue(DomainMatcher.isDomainBlocked("video.i.v.youtube.com", blocked))
    }

    // =========================================================================
    // 4. Similar-looking domains (Strict Boundary Verification)
    // =========================================================================
    @Test
    fun testSimilarLookingDomainsDoNotMatch() {
        val blocked = setOf("youtube.com")

        // Must NOT match: prefixes that are not subdomain boundaries
        assertFalse("notyoutube.com must NOT match youtube.com",
            DomainMatcher.isDomainBlocked("notyoutube.com", blocked))
        assertFalse("myoutube.com must NOT match youtube.com",
            DomainMatcher.isDomainBlocked("myoutube.com", blocked))
        assertFalse("fakeyoutube.com must NOT match youtube.com",
            DomainMatcher.isDomainBlocked("fakeyoutube.com", blocked))

        // Must NOT match: domain is a subdomain of another domain
        assertFalse("youtube.com.example.com must NOT match youtube.com",
            DomainMatcher.isDomainBlocked("youtube.com.example.com", blocked))
        assertFalse("youtube.com.evil.org must NOT match youtube.com",
            DomainMatcher.isDomainBlocked("youtube.com.evil.org", blocked))
    }

    // =========================================================================
    // 5. URL Normalization
    // =========================================================================
    @Test
    fun testUrlNormalizationRules() {
        assertEquals("youtube.com",
            DomainMatcher.normalizeBlockedDomain("https://www.youtube.com/watch?v=123"))
        assertEquals("youtube.com",
            DomainMatcher.normalizeBlockedDomain("http://youtube.com/feed/explore"))
        assertEquals("youtube.com",
            DomainMatcher.normalizeBlockedDomain("https://youtube.com:8443/settings#profile"))
        assertEquals("youtube.com",
            DomainMatcher.normalizeBlockedDomain("user:pass@youtube.com/index.html"))
        assertEquals("instagram.com",
            DomainMatcher.normalizeBlockedDomain("*.instagram.com"))
        assertEquals("facebook.com",
            DomainMatcher.normalizeBlockedDomain("https://www.facebook.com:443/"))
        assertEquals("m.facebook.com",
            DomainMatcher.normalizeBlockedDomain("https://m.facebook.com/messages"))
    }

    // =========================================================================
    // 6. Empty block list
    // =========================================================================
    @Test
    fun testEmptyBlockListAllowsAll() {
        val empty = emptySet<String>()

        assertFalse(DomainMatcher.isDomainBlocked("youtube.com", empty))
        assertFalse(DomainMatcher.isDomainBlocked("google.com", empty))
        assertFalse(DomainMatcher.isDomainBlocked("facebook.com", empty))
    }

    // =========================================================================
    // 7. Emergency pause behavior simulation
    // =========================================================================
    @Test
    fun testEmergencyPauseBypassesAllBlocking() {
        val blocked = setOf("youtube.com", "reddit.com")
        val isEmergencyPauseActive = true

        val activeList = if (isEmergencyPauseActive) emptySet() else blocked

        assertFalse(DomainMatcher.isDomainBlocked("youtube.com", activeList))
        assertFalse(DomainMatcher.isDomainBlocked("reddit.com", activeList))
        assertFalse(DomainMatcher.isDomainBlocked("google.com", activeList))
    }

    // =========================================================================
    // 8. Individual-site pause behavior simulation
    // =========================================================================
    @Test
    fun testIndividualSitePause() {
        val blocked = setOf("youtube.com", "instagram.com")
        val pausedSites = setOf("youtube.com")

        val effectiveBlocked = blocked.filterNot { pausedSites.contains(DomainMatcher.normalizeBlockedDomain(it)) }.toSet()

        // youtube.com was paused -> should be allowed
        assertFalse("youtube.com should be allowed when individually paused",
            DomainMatcher.isDomainBlocked("youtube.com", effectiveBlocked))
        assertFalse("m.youtube.com should be allowed when youtube.com is paused",
            DomainMatcher.isDomainBlocked("m.youtube.com", effectiveBlocked))

        // instagram.com was NOT paused -> should remain blocked
        assertTrue("instagram.com must remain blocked",
            DomainMatcher.isDomainBlocked("instagram.com", effectiveBlocked))
    }

    // =========================================================================
    // 9. Permanent blocking vs Session blocking simulation
    // =========================================================================
    @Test
    fun testPermanentAndSessionBlockingComposition() {
        val permanentBlocked = setOf("reddit.com")
        val sessionBlocked = setOf("youtube.com")

        // Case A: Session is active, permanent blocking active -> both apply
        var isSessionActive = true
        var isPermanentPaused = false
        var activeBlocked = (if (isSessionActive) sessionBlocked else emptySet()) +
                (if (!isPermanentPaused) permanentBlocked else emptySet())

        assertTrue(DomainMatcher.isDomainBlocked("youtube.com", activeBlocked))
        assertTrue(DomainMatcher.isDomainBlocked("reddit.com", activeBlocked))
        assertFalse(DomainMatcher.isDomainBlocked("google.com", activeBlocked))

        // Case B: Session ended, permanent blocking still active
        isSessionActive = false
        activeBlocked = (if (isSessionActive) sessionBlocked else emptySet()) +
                (if (!isPermanentPaused) permanentBlocked else emptySet())

        assertFalse("youtube.com should be allowed after session ends",
            DomainMatcher.isDomainBlocked("youtube.com", activeBlocked))
        assertTrue("reddit.com should remain blocked under permanent block",
            DomainMatcher.isDomainBlocked("reddit.com", activeBlocked))

        // Case C: Permanent blocking paused, session ended -> all allowed
        isPermanentPaused = true
        activeBlocked = (if (isSessionActive) sessionBlocked else emptySet()) +
                (if (!isPermanentPaused) permanentBlocked else emptySet())

        assertFalse(DomainMatcher.isDomainBlocked("youtube.com", activeBlocked))
        assertFalse(DomainMatcher.isDomainBlocked("reddit.com", activeBlocked))
    }

    // =========================================================================
    // 10. Malformed DNS packet handling
    // =========================================================================
    @Test
    fun testMalformedDnsPacketsReturnNullGracefully() {
        // Too short (< 28 bytes)
        assertNull(DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(ByteArray(10))))

        // Empty buffer
        assertNull(DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(ByteArray(0))))

        // Random garbage bytes
        val garbage = ByteArray(64) { it.toByte() }
        assertNull(DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(garbage)))
    }

    // =========================================================================
    // 11. Unsupported packet handling (TCP, IPv6, non-port-53)
    // =========================================================================
    @Test
    fun testUnsupportedPacketsReturnNull() {
        // TCP packet on port 53 (protocol = 6 instead of 17)
        val tcpPacket = createMockPacket(protocol = 6, dstPort = 53, queryName = "google.com")
        assertNull("TCP packet must return null (not handled as UDP DNS)",
            DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(tcpPacket)))

        // UDP packet on port 443 (e.g. QUIC / HTTP3)
        val quicPacket = createMockPacket(protocol = 17, dstPort = 443, queryName = "google.com")
        assertNull("Non-port-53 UDP must return null",
            DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(quicPacket)))

        // UDP packet on port 853 (DoT)
        val dotPacket = createMockPacket(protocol = 17, dstPort = 853, queryName = "google.com")
        assertNull("Port 853 DoT must return null",
            DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(dotPacket)))

        // IPv6 packet (version = 6)
        val ipv6Packet = ByteArray(40)
        ipv6Packet[0] = 0x60.toByte() // Version 6
        assertNull("IPv6 packet must return null",
            DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(ipv6Packet)))
    }

    // =========================================================================
    // 12. Valid IPv4 UDP DNS query parsing
    // =========================================================================
    @Test
    fun testValidDnsQueryExtraction() {
        val packet = createMockPacket(protocol = 17, dstPort = 53, queryName = "youtube.com", dnsId = 0x1234)
        val query = DnsPacketParser.extractDnsQuery(ByteBuffer.wrap(packet))

        assertNotNull("Valid DNS query must be parsed", query)
        assertEquals("youtube.com", query!!.queryName)
        assertEquals(0x1234, query.dnsId)
        assertEquals(53, query.destPort)
    }

    // =========================================================================
    // 13. NXDOMAIN packet generation
    // =========================================================================
    @Test
    fun testNxDomainResponseGeneration() {
        val originalPacket = createMockPacket(protocol = 17, dstPort = 53, queryName = "youtube.com", dnsId = 0x4321)
        val nxDomain = DnsPacketParser.buildNxDomainResponse(originalPacket, originalPacket.size)

        assertNotNull(nxDomain)
        assertTrue("NXDOMAIN response must be at least header size", nxDomain.size >= 28)

        // Verify IP checksum of generated response
        val ipChecksum = DnsPacketParser.computeChecksum(nxDomain, 0, 20)
        assertEquals("IP checksum of NXDOMAIN response must be 0", 0, ipChecksum)

        // Verify DNS flags in response: RCODE must be 3 (NXDOMAIN)
        val dnsFlagsHigh = nxDomain[28 + 2].toInt() and 0xFF
        val dnsFlagsLow = nxDomain[28 + 3].toInt() and 0xFF
        assertEquals("QR flag must be 1 (response)", 0x81, dnsFlagsHigh)
        assertEquals("RCODE must be 3 (NXDOMAIN)", 0x83, dnsFlagsLow)

        // Verify transaction ID matches
        val id = ((nxDomain[28].toInt() and 0xFF) shl 8) or (nxDomain[29].toInt() and 0xFF)
        assertEquals("DNS transaction ID must match request", 0x4321, id)
    }

    // =========================================================================
    // 14. Key Scenario: Block ONLY youtube.com, verify unrelated sites resolve
    // =========================================================================
    @Test
    fun testKeyScenarioBlockOnlyYouTubeAllOtherSitesAllowed() {
        // Step 1: User adds "youtube.com" to the block list
        val blockedList = setOf("youtube.com")

        // Step 2: YouTube variations are ALL blocked
        assertTrue(DomainMatcher.isDomainBlocked("youtube.com", blockedList))
        assertTrue(DomainMatcher.isDomainBlocked("www.youtube.com", blockedList))
        assertTrue(DomainMatcher.isDomainBlocked("m.youtube.com", blockedList))
        assertTrue(DomainMatcher.isDomainBlocked("music.youtube.com", blockedList))
        assertTrue(DomainMatcher.isDomainBlocked("s.youtube.com", blockedList))

        // Step 3: Unrelated sites MUST be allowed
        val allowedSites = listOf(
            "google.com", "www.google.com", "mail.google.com",
            "github.com", "api.github.com",
            "wikipedia.org", "en.wikipedia.org",
            "example.com", "stackoverflow.com",
            "amazon.com", "microsoft.com", "apple.com"
        )
        for (site in allowedSites) {
            assertFalse("Site '$site' must be ALLOWED when only youtube.com is blocked",
                DomainMatcher.isDomainBlocked(site, blockedList))
        }

        // Step 4: Disabling the blocker (empty block list or session stopped) restores all sites immediately
        val blockerDisabled = emptySet<String>()
        assertTrue("Disabling blocker immediately allows youtube.com",
            !DomainMatcher.isDomainBlocked("youtube.com", blockerDisabled))
        for (site in allowedSites) {
            assertFalse("Site '$site' remains allowed when blocker is disabled",
                DomainMatcher.isDomainBlocked(site, blockerDisabled))
        }
    }

    // =========================================================================
    // Helper: Creates a raw mock IPv4 UDP/TCP DNS packet
    // =========================================================================
    private fun createMockPacket(
        protocol: Int,
        dstPort: Int,
        queryName: String,
        srcPort: Int = 45678,
        dnsId: Int = 0x1234
    ): ByteArray {
        // DNS query payload
        val dnsPayload = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN)
        dnsPayload.putShort(dnsId.toShort()) // ID
        dnsPayload.putShort(0x0100.toShort()) // Flags: standard query, RD=1
        dnsPayload.putShort(1.toShort()) // QDCOUNT = 1
        dnsPayload.putShort(0.toShort()) // ANCOUNT = 0
        dnsPayload.putShort(0.toShort()) // NSCOUNT = 0
        dnsPayload.putShort(0.toShort()) // ARCOUNT = 0

        // Encode QNAME: labels length-prefixed
        for (label in queryName.split('.')) {
            dnsPayload.put(label.length.toByte())
            for (ch in label) dnsPayload.put(ch.code.toByte())
        }
        dnsPayload.put(0.toByte()) // null terminator
        dnsPayload.putShort(1.toShort()) // QTYPE = A (1)
        dnsPayload.putShort(1.toShort()) // QCLASS = IN (1)

        val dnsLen = dnsPayload.position()
        val dnsBytes = ByteArray(dnsLen)
        dnsPayload.rewind()
        dnsPayload.get(dnsBytes)

        // UDP Header (8 bytes)
        val udpLen = 8 + dnsLen
        val udpHeader = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        udpHeader.putShort(srcPort.toShort())
        udpHeader.putShort(dstPort.toShort())
        udpHeader.putShort(udpLen.toShort())
        udpHeader.putShort(0.toShort()) // Checksum
        val udpBytes = udpHeader.array()

        // IPv4 Header (20 bytes)
        val totalLen = 20 + udpLen
        val ipHeader = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        ipHeader.put(0x45.toByte()) // Version 4, IHL 5 (20 bytes)
        ipHeader.put(0.toByte()) // DSCP / ECN
        ipHeader.putShort(totalLen.toShort())
        ipHeader.putShort(100.toShort()) // Identification
        ipHeader.putShort(0.toShort()) // Flags & Fragment offset
        ipHeader.put(64.toByte()) // TTL
        ipHeader.put(protocol.toByte()) // Protocol (17 = UDP, 6 = TCP)
        ipHeader.putShort(0.toShort()) // Checksum (computed below)
        ipHeader.put(byteArrayOf(10, 0, 0, 2)) // Src: 10.0.0.2
        ipHeader.put(byteArrayOf(10, 0, 0, 2)) // Dst: 10.0.0.2

        val ipBytes = ipHeader.array()
        val ck = DnsPacketParser.computeChecksum(ipBytes, 0, 20)
        ipBytes[10] = (ck shr 8).toByte()
        ipBytes[11] = (ck and 0xFF).toByte()

        return ipBytes + udpBytes + dnsBytes
    }
}
