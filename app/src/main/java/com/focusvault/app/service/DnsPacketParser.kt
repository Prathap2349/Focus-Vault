package com.focusvault.app.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4 + UDP + DNS packet parser/builder. Handles the standard case (no IP options,
 * IPv4 only) which covers the overwhelming majority of on-device DNS traffic. IPv6 DNS
 * queries are passed through unmodified by the caller (extractDnsQuery returns null for them,
 * so they're just dropped from the filter loop - see note in FocusVpnService).
 */
object DnsPacketParser {

    data class DnsQuery(
        val queryName: String,
        val rawDnsPayload: ByteArray,
        val sourceIp: ByteArray,
        val destIp: ByteArray,
        val sourcePort: Int,
        val destPort: Int,
        val dnsId: Int
    )

    fun extractDnsQuery(packet: ByteBuffer): DnsQuery? {
        try {
            packet.order(ByteOrder.BIG_ENDIAN)
            if (packet.remaining() < 20) return null
            val versionAndIhl = packet.get(0).toInt()
            val version = (versionAndIhl shr 4) and 0xF
            if (version != 4) return null // only IPv4 handled
            val ihl = (versionAndIhl and 0xF) * 4
            val protocol = packet.get(9).toInt() and 0xFF
            if (protocol != 17) return null // only UDP

            val sourceIp = ByteArray(4)
            val destIp = ByteArray(4)
            packet.position(12)
            packet.get(sourceIp)
            packet.get(destIp)

            packet.position(ihl)
            val srcPort = packet.short.toInt() and 0xFFFF
            val dstPort = packet.short.toInt() and 0xFFFF
            packet.short // length
            packet.short // checksum
            if (dstPort != 53) return null // only outbound DNS queries

            val dnsStart = ihl + 8
            val dnsId = ((packet.get(dnsStart).toInt() and 0xFF) shl 8) or (packet.get(dnsStart + 1).toInt() and 0xFF)
            val qdCount = ((packet.get(dnsStart + 4).toInt() and 0xFF) shl 8) or (packet.get(dnsStart + 5).toInt() and 0xFF)
            if (qdCount < 1) return null

            var pos = dnsStart + 12
            val nameBuilder = StringBuilder()
            var jumps = 0
            while (true) {
                val len = packet.get(pos).toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) {
                    // DNS name compression pointer: rare in the question section, but legal.
                    // The pointer is 2 bytes; the low 14 bits give the offset (from the start
                    // of the DNS message) to jump to and keep reading the name from there.
                    // (The raw payload forwarded below is copied independently of this parse,
                    // so we don't need to track where the name "ends" in the original bytes.)
                    if (jumps++ > 16) throw IllegalStateException("DNS compression pointer loop")
                    val offsetHigh = len and 0x3F
                    val offsetLow = packet.get(pos + 1).toInt() and 0xFF
                    pos = dnsStart + (offsetHigh shl 8 or offsetLow)
                    continue
                }
                pos += 1
                for (i in 0 until len) {
                    nameBuilder.append(packet.get(pos + i).toInt().toChar())
                }
                pos += len
                nameBuilder.append('.')
            }
            val queryName = nameBuilder.toString().removeSuffix(".").lowercase()

            val dnsPayloadLength = packet.limit() - dnsStart
            val dnsPayload = ByteArray(dnsPayloadLength)
            packet.position(dnsStart)
            packet.get(dnsPayload)

            return DnsQuery(queryName, dnsPayload, sourceIp, destIp, srcPort, dstPort, dnsId)
        } catch (e: Exception) {
            return null
        }
    }

    /** Builds a synthetic NXDOMAIN DNS response wrapped back in IP+UDP, swapping src/dst. */
    fun buildNxDomainResponse(originalPacket: ByteArray, length: Int): ByteArray {
        val buf = ByteBuffer.wrap(originalPacket, 0, length).duplicate()
        buf.order(ByteOrder.BIG_ENDIAN)
        val ihl = (originalPacket[0].toInt() and 0xF) * 4
        val dnsStart = ihl + 8

        // Copy the question section as-is, then append minimal NXDOMAIN header flags
        val questionLength = length - dnsStart
        val response = ByteArray(dnsStart + questionLength)
        System.arraycopy(originalPacket, 0, response, 0, dnsStart + questionLength)

        // DNS flags: QR=1 (response), Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
        response[dnsStart + 2] = 0x81.toByte()
        response[dnsStart + 3] = 0x83.toByte()
        // ANCOUNT = 0 (no answers)
        response[dnsStart + 6] = 0
        response[dnsStart + 7] = 0

        return wrapAsIpUdpPacket(originalPacket, response, dnsStart, swap = true)
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
            out[ihl] = templatePacket[ihl + 2]; out[ihl + 1] = templatePacket[ihl + 3]     // src port = original dst port (53)
            out[ihl + 2] = templatePacket[ihl]; out[ihl + 3] = templatePacket[ihl + 1]     // dst port = original src port
        }
        val udpLength = 8 + dnsPayload.size
        val udpLenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(udpLength.toShort())
        out[ihl + 4] = udpLenBuf.get(0); out[ihl + 5] = udpLenBuf.get(1)
        out[ihl + 6] = 0; out[ihl + 7] = 0 // UDP checksum left as 0 (optional for IPv4)

        // --- DNS payload ---
        System.arraycopy(dnsPayload, 0, out, dnsStart, dnsPayload.size)

        return out
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
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
