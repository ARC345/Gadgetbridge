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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OggOpusWriterTest {
    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)
    private fun u64(b: ByteArray, i: Int): Long = u32(b, i) or (u32(b, i + 4) shl 32)

    /** Splits an Ogg stream into pages: (header type, granule, sequence, segments payload). */
    private data class Page(val type: Int, val granule: Long, val sequence: Long, val body: ByteArray, val crcOk: Boolean)

    private fun pages(data: ByteArray): List<Page> {
        val list = ArrayList<Page>()
        var i = 0
        while (i < data.size) {
            assertEquals("OggS", String(data, i, 4))
            val count = data[i + 26].toInt() and 0xFF
            var bodySize = 0
            for (s in 0 until count) bodySize += data[i + 27 + s].toInt() and 0xFF
            val end = i + 27 + count + bodySize
            val page = data.copyOfRange(i, end)
            val stored = u32(page, 22)
            page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0
            list.add(Page(data[i + 5].toInt(), u64(data, i + 6), u32(data, i + 18),
                data.copyOfRange(i + 27 + count, end), OggOpusWriter.crc32(page) == stored))
            i = end
        }
        return list
    }

    @Test
    fun oggCrcMatchesCheckValue() {
        assertEquals(0x89A1897FL, OggOpusWriter.crc32("123456789".toByteArray()))
    }

    @Test
    fun writesHeadTagsAndAudioPages() {
        val packets = ByteArray(40 * 3) { it.toByte() }
        val ogg = OggOpusWriter.fromRawPackets(packets, 40, 16000, 1)
        val p = pages(ogg)
        assertEquals(3, p.size)
        assertEquals(true, p.all { it.crcOk })
        // OpusHead: version 1, 1 channel, input rate 16000.
        assertEquals("OpusHead", String(p[0].body, 0, 8))
        assertEquals(1, p[0].body[9].toInt())
        assertEquals(16000L, u32(p[0].body, 12))
        assertEquals(2, p[0].type) // beginning of stream
        assertEquals("OpusTags", String(p[1].body, 0, 8))
        // Audio: 3 packets of 20 ms = 3 * 960 samples at 48 kHz, last page has end-of-stream.
        assertArrayEquals(packets, p[2].body)
        assertEquals(3 * 960L, p[2].granule)
        assertEquals(4, p[2].type)
        assertEquals(listOf(0L, 1L, 2L), p.map { it.sequence })
    }

    @Test
    fun splitsLongAudioIntoSeveralPages() {
        val packets = ByteArray(40 * 300)
        val p = pages(OggOpusWriter.fromRawPackets(packets, 40, 16000, 1))
        val audio = p.drop(2)
        assertEquals(true, audio.size > 1)
        assertEquals(300 * 960L, audio.last().granule)
        assertEquals(40 * 300, audio.sumOf { it.body.size })
        assertEquals(4, audio.last().type)
        assertEquals(0, audio.first().type)
    }

    @Test
    fun dropsAnIncompleteLastPacket() {
        val p = pages(OggOpusWriter.fromRawPackets(ByteArray(40 * 2 + 7), 40, 16000, 1))
        assertEquals(80, p[2].body.size)
    }

    @Test
    fun streamingWriteMatchesTheByteArrayVersion() {
        val packets = ByteArray(40 * 120 + 5) { (it * 3).toByte() }
        val out = java.io.ByteArrayOutputStream()
        OggOpusWriter.write(packets, 40, 16000, 1, out)
        assertArrayEquals(OggOpusWriter.fromRawPackets(packets, 40, 16000, 1), out.toByteArray())
    }
}
