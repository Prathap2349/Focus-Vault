package com.focusvault.app.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4 + UDP + DNS packet parser/builder.
 *
 * Only processes outbound IPv4 UDP packets targeting DNS port 53.
 * Non-DNS traffic and IPv6 traffic are not captured by the VPN's narrow
 * 10.0.0.2/32 routing configuration.
 */
object DnsPacketParser {

    data class DnsQuery(
        val queryName: String,
        val rawDnsPayload: ByteArray,
        val sourceIp: ByteArray,
        val destIp: ByteArray,
        val sourcePort: Int,
        val destPort: Int,
        val dnsId: Int,
        val questionSectionLength: Int
    )

    /**
     * Parses an IPv4 UDP DNS query packet.
     * Returns null if:
     * - Packet is too short (< 28 bytes for IP + UDP header)
     * - Version != 4 (not IPv4)
     * - Protocol != 17 (not UDP)
     * - Destination port != 53 (not standard DNS)
     * - Packet is malformed or truncated
     */
    fun extractDnsQuery(packet: ByteBuffer): DnsQuery? {
        try {
            packet.order(ByteOrder.BIG_ENDIAN)
            if (packet.remaining() < 28) return null

            val versionAndIhl = packet.get(0).toInt()
            val version = (versionAndIhl shr 4) and 0xF
            if (version != 4) return null // IPv4 only

            val ihl = (versionAndIhl and 0xF) * 4
            if (ihl < 20 || packet.limit() < ihl + 8) return null

            val protocol = packet.get(9).toInt() and 0xFF
            if (protocol != 17) return null // UDP only

            val sourceIp = ByteArray(4)
            val destIp = ByteArray(4)
            packet.position(12)
            packet.get(sourceIp)
            packet.get(destIp)

            packet.position(ihl)
            val srcPort = packet.short.toInt() and 0xFFFF
            val dstPort = packet.short.toInt() and 0xFFFF
            val udpLength = packet.short.toInt() and 0xFFFF
            packet.short // checksum

            if (dstPort != 53) return null // only standard outbound DNS queries
            if (udpLength < 8) return null

            val dnsStart = ihl + 8
            if (packet.limit() < dnsStart + 12) return null // DNS header is 12 bytes

            val dnsId = ((packet.get(dnsStart).toInt() and 0xFF) shl 8) or (packet.get(dnsStart + 1).toInt() and 0xFF)
            val flags = ((packet.get(dnsStart + 2).toInt() and 0xFF) shl 8) or (packet.get(dnsStart + 3).toInt() and 0xFF)
            if ((flags and 0x8000) != 0) return null // Must be a Query (QR == 0)
            val qdCount = ((packet.get(dnsStart + 4).toInt() and 0xFF) shl 8) or (packet.get(dnsStart + 5).toInt() and 0xFF)
            if (qdCount < 1) return null

            var pos = dnsStart + 12
            val nameBuilder = StringBuilder()
            var jumps = 0
            while (pos < packet.limit()) {
                val len = packet.get(pos).toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) {
                    if (pos + 1 >= packet.limit()) return null
                    if (jumps++ > 16) return null // prevent pointer loops
                    val offsetHigh = len and 0x3F
                    val offsetLow = packet.get(pos + 1).toInt() and 0xFF
                    pos = dnsStart + (offsetHigh shl 8 or offsetLow)
                    continue
                }
                pos += 1
                if (pos + len > packet.limit()) return null
                for (i in 0 until len) {
                    nameBuilder.append(packet.get(pos + i).toInt().toChar())
                }
                pos += len
                nameBuilder.append('.')
            }
            val queryName = nameBuilder.toString().removeSuffix(".").lowercase()
            if (queryName.isEmpty()) return null

            pos += 1 // Skip terminating 0x00
            if (pos + 4 > packet.limit()) return null
            val questionSectionLength = (pos + 4) - dnsStart

            val dnsPayloadLength = packet.limit() - dnsStart
            if (dnsPayloadLength <= 0) return null
            val dnsPayload = ByteArray(dnsPayloadLength)
            packet.position(dnsStart)
            packet.get(dnsPayload)

            return DnsQuery(queryName, dnsPayload, sourceIp, destIp, srcPort, dstPort, dnsId, questionSectionLength)
        } catch (e: Exception) {
            return null
        }
    }

    /** Builds a synthetic NXDOMAIN DNS response (RCODE=3) wrapped back in IP+UDP, swapping src/dst. */
    fun buildNxDomainResponse(originalPacket: ByteArray, length: Int, questionLength: Int? = null): ByteArray {
        val ihl = (originalPacket[0].toInt() and 0xF) * 4
        val dnsStart = ihl + 8
        val dnsLength = if (questionLength != null && questionLength >= 16 && dnsStart + questionLength <= length) {
            questionLength
        } else {
            length - dnsStart
        }
        val dnsPayload = ByteArray(dnsLength)
        System.arraycopy(originalPacket, dnsStart, dnsPayload, 0, dnsLength)

        // DNS flags: QR=1 (response), Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
        val origRd = (originalPacket[dnsStart + 2].toInt() and 0x01)
        dnsPayload[2] = (0x80 or origRd).toByte()
        dnsPayload[3] = 0x83.toByte()
        // QDCOUNT = 1
        dnsPayload[4] = 0; dnsPayload[5] = 1
        // ANCOUNT = 0 (no answers)
        dnsPayload[6] = 0; dnsPayload[7] = 0
        // NSCOUNT = 0
        dnsPayload[8] = 0; dnsPayload[9] = 0
        // ARCOUNT = 0
        dnsPayload[10] = 0; dnsPayload[11] = 0

        return wrapAsIpUdpPacket(originalPacket, dnsPayload, dnsStart, swap = true)
    }

    /** Builds a synthetic SERVFAIL DNS response (RCODE=2) wrapped in IP+UDP. */
    fun buildServFailResponse(originalPacket: ByteArray, length: Int, questionLength: Int? = null): ByteArray {
        val ihl = (originalPacket[0].toInt() and 0xF) * 4
        val dnsStart = ihl + 8
        val dnsLength = if (questionLength != null && questionLength >= 16 && dnsStart + questionLength <= length) {
            questionLength
        } else {
            length - dnsStart
        }
        val dnsPayload = ByteArray(dnsLength)
        System.arraycopy(originalPacket, dnsStart, dnsPayload, 0, dnsLength)

        // DNS flags: QR=1 (response), Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE=2 (SERVFAIL)
        val origRd = (originalPacket[dnsStart + 2].toInt() and 0x01)
        dnsPayload[2] = (0x80 or origRd).toByte()
        dnsPayload[3] = 0x82.toByte()
        dnsPayload[4] = 0; dnsPayload[5] = 1
        dnsPayload[6] = 0; dnsPayload[7] = 0
        dnsPayload[8] = 0; dnsPayload[9] = 0
        dnsPayload[10] = 0; dnsPayload[11] = 0

        return wrapAsIpUdpPacket(originalPacket, dnsPayload, dnsStart, swap = true)
    }

    /**
     * Builds a TCP RST response for any incoming TCP packet (e.g. DoT on port 853 or TCP DNS on port 53).
     * This immediately refuses the TCP connection, preventing the client's resolver from hanging.
     */
    fun buildTcpRstResponse(originalPacket: ByteArray, length: Int): ByteArray? {
        if (length < 40) return null
        val versionAndIhl = originalPacket[0].toInt() and 0xFF
        val version = (versionAndIhl shr 4) and 0xF
        if (version != 4) return null
        val ihl = (versionAndIhl and 0xF) * 4
        if (ihl < 20 || length < ihl + 20) return null
        val protocol = originalPacket[9].toInt() and 0xFF
        if (protocol != 6) return null // TCP only

        val buf = ByteBuffer.wrap(originalPacket, ihl, 20).order(ByteOrder.BIG_ENDIAN)
        val srcPort = buf.short.toInt() and 0xFFFF
        val dstPort = buf.short.toInt() and 0xFFFF
        val seq = buf.int.toLong() and 0xFFFFFFFFL
        val ack = buf.int.toLong() and 0xFFFFFFFFL

        val out = ByteArray(40)
        // IP Header
        out[0] = 0x45.toByte()
        out[2] = 0.toByte(); out[3] = 40.toByte() // Total length 40
        out[6] = 0x40.toByte() // DF flag
        out[8] = 64.toByte() // TTL
        out[9] = 6.toByte() // Protocol TCP
        // Swap IPs
        System.arraycopy(originalPacket, 16, out, 12, 4) // new src = old dst
        System.arraycopy(originalPacket, 12, out, 16, 4) // new dst = old src
        val ipCk = computeChecksum(out, 0, 20)
        out[10] = (ipCk shr 8).toByte()
        out[11] = (ipCk and 0xFF).toByte()

        // TCP Header
        val tcpBuf = ByteBuffer.wrap(out, 20, 20).order(ByteOrder.BIG_ENDIAN)
        tcpBuf.putShort(dstPort.toShort()) // new src port = old dst port
        tcpBuf.putShort(srcPort.toShort()) // new dst port = old src port
        tcpBuf.putInt(if (ack != 0L) ack.toInt() else 0) // seq
        tcpBuf.putInt(((seq + 1) and 0xFFFFFFFFL).toInt()) // ack = incoming seq + 1
        out[32] = 0x50.toByte() // Data offset 5 (20 bytes)
        out[33] = 0x14.toByte() // RST (0x04) | ACK (0x10)

        // TCP Checksum with pseudo-header
        val pseudo = ByteArray(12 + 20)
        System.arraycopy(out, 12, pseudo, 0, 8) // src IP + dst IP
        pseudo[9] = 6.toByte() // protocol
        pseudo[10] = 0; pseudo[11] = 20.toByte() // TCP length
        System.arraycopy(out, 20, pseudo, 12, 20) // TCP header
        val tcpCk = computeChecksum(pseudo, 0, pseudo.size)
        out[36] = (tcpCk shr 8).toByte()
        out[37] = (tcpCk and 0xFF).toByte()

        return out
    }

    @Suppress("UNUSED_PARAMETER")
    fun wrapDnsReplyIntoIpPacket(originalRequestPacket: ByteArray, originalLength: Int, dnsAnswer: ByteArray): ByteArray {
        val ihl = (originalRequestPacket[0].toInt() and 0xF) * 4
        val dnsStart = ihl + 8
        return wrapAsIpUdpPacket(originalRequestPacket, dnsAnswer, dnsStart, swap = true)
    }

    private fun wrapAsIpUdpPacket(templatePacket: ByteArray, dnsPayload: ByteArray, dnsStart: Int, swap: Boolean): ByteArray {
        val ihl = dnsStart - 8
        val totalLength = dnsStart + dnsPayload.size
        val out = ByteArray(totalLength)

        // --- IP header ---
        System.arraycopy(templatePacket, 0, out, 0, ihl)
        if (swap) {
            // swap source/dest IP (response goes back to whoever asked)
            for (i in 0 until 4) {
                out[12 + i] = templatePacket[16 + i]
                out[16 + i] = templatePacket[12 + i]
            }
        }
        val totalLenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(totalLength.toShort())
        out[2] = totalLenBuf.get(0); out[3] = totalLenBuf.get(1)
        out[10] = 0; out[11] = 0 // checksum recomputed below
        val ipChecksum = computeChecksum(out, 0, ihl)
        out[10] = (ipChecksum shr 8).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // --- UDP header ---
        if (swap) {
            out[ihl] = templatePacket[ihl + 2]; out[ihl + 1] = templatePacket[ihl + 3] // src port = original dst port (53)
            out[ihl + 2] = templatePacket[ihl]; out[ihl + 3] = templatePacket[ihl + 1] // dst port = original src port
        }
        val udpLength = 8 + dnsPayload.size
        val udpLenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(udpLength.toShort())
        out[ihl + 4] = udpLenBuf.get(0); out[ihl + 5] = udpLenBuf.get(1)
        out[ihl + 6] = 0; out[ihl + 7] = 0 // UDP checksum 0 (valid in IPv4 UDP)

        // --- DNS payload ---
        System.arraycopy(dnsPayload, 0, out, dnsStart, dnsPayload.size)

        return out
    }

    fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
