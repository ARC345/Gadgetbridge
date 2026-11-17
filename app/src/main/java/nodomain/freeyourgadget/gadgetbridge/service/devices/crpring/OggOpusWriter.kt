/*  Copyright (C) 2026 Arnav Rastogi

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import java.io.ByteArrayOutputStream

/**
 * Wraps raw Opus packets (no container, as the band stores them) in an Ogg Opus file
 * (RFC 7845), so that normal players can play it. The audio is not decoded.
 */
object OggOpusWriter {
    private const val SERIAL = 0x43525052 // "CRPR"
    private const val PACKETS_PER_PAGE = 50 // 1 s of 20 ms packets; a page holds at most 255 segments
    private const val SAMPLES_PER_PACKET_48K = 960 // 20 ms at 48 kHz, the Opus granule rate
    private const val VENDOR = "Gadgetbridge"

    private val CRC_TABLE = LongArray(256) { i ->
        var r = i.toLong() shl 24
        repeat(8) { r = if (r and 0x80000000L != 0L) (r shl 1) xor 0x04C11DB7L else r shl 1 }
        r and 0xFFFFFFFFL
    }

    /** The Ogg page CRC-32: polynomial 0x04C11DB7, start 0, no reflection, no final XOR. */
    fun crc32(data: ByteArray): Long {
        var crc = 0L
        for (b in data) {
            crc = ((crc shl 8) xor CRC_TABLE[(((crc ushr 24) xor (b.toLong() and 0xFF)) and 0xFF).toInt()]) and 0xFFFFFFFFL
        }
        return crc
    }

    /**
     * [raw] holds packets of exactly [packetSize] bytes, each 20 ms long. An incomplete last
     * packet is dropped. The pre-skip is 0, so no audio is cut off.
     */
    fun fromRawPackets(raw: ByteArray, packetSize: Int, inputRate: Int, channels: Int): ByteArray {
        require(packetSize in 1..255) { "Packet size must fit in one Ogg segment: $packetSize" }
        val out = ByteArrayOutputStream()
        var sequence = 0L

        fun page(type: Int, granule: Long, packets: List<ByteArray>) {
            val lacing = ByteArrayOutputStream()
            for (packet in packets) {
                var n = packet.size
                while (n >= 255) { lacing.write(255); n -= 255 }
                lacing.write(n)
            }
            val segments = lacing.toByteArray()
            val header = ByteArrayOutputStream()
            header.write("OggS".toByteArray())
            header.write(0)
            header.write(type)
            writeLe(header, granule, 8)
            writeLe(header, SERIAL.toLong(), 4)
            writeLe(header, sequence++, 4)
            writeLe(header, 0, 4) // CRC, set below
            header.write(segments.size)
            header.write(segments)
            for (packet in packets) header.write(packet)
            val bytes = header.toByteArray()
            val crc = crc32(bytes)
            for (i in 0 until 4) bytes[22 + i] = (crc shr (8 * i)).toByte()
            out.write(bytes)
        }

        val head = ByteArrayOutputStream().apply {
            write("OpusHead".toByteArray())
            write(1) // version
            write(channels)
            writeLe(this, 0, 2) // pre-skip
            writeLe(this, inputRate.toLong(), 4)
            writeLe(this, 0, 2) // output gain
            write(0) // channel mapping family 0 (mono or stereo)
        }.toByteArray()
        page(2, 0, listOf(head))

        val vendor = VENDOR.toByteArray()
        val tags = ByteArrayOutputStream().apply {
            write("OpusTags".toByteArray())
            writeLe(this, vendor.size.toLong(), 4)
            write(vendor)
            writeLe(this, 0, 4) // no comments
        }.toByteArray()
        page(0, 0, listOf(tags))

        val packets = (0 until raw.size / packetSize).map { raw.copyOfRange(it * packetSize, (it + 1) * packetSize) }
        if (packets.isEmpty()) {
            page(4, 0, emptyList())
            return out.toByteArray()
        }
        val pages = packets.chunked(PACKETS_PER_PAGE)
        var count = 0L
        pages.forEachIndexed { i, pagePackets ->
            count += pagePackets.size
            page(if (i == pages.size - 1) 4 else 0, count * SAMPLES_PER_PACKET_48K, pagePackets)
        }
        return out.toByteArray()
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Long, bytes: Int) {
        for (i in 0 until bytes) out.write(((value shr (8 * i)) and 0xFF).toInt())
    }
}
