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
import org.junit.Assert.assertNull
import org.junit.Test

class CrpRingRecordingProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun name15(text: String) = text.toByteArray().copyOf(15)

    @Test
    fun crc16MatchesCcittCheckValue() {
        assertEquals(0x29B1, CrpRingCrc16.compute("123456789".toByteArray(), 0xFFFF))
    }

    @Test
    fun crc16DefaultStartIsFdda() {
        val data = "123456789".toByteArray()
        assertEquals(CrpRingCrc16.compute(data, 0xFDDA), CrpRingCrc16.compute(data))
    }

    @Test
    fun crc16CanRunInParts() {
        val data = ByteArray(5000) { (it * 7).toByte() }
        val whole = CrpRingCrc16.compute(data)
        val parts = CrpRingCrc16.compute(data.copyOfRange(4096, 5000), CrpRingCrc16.compute(data.copyOfRange(0, 4096)))
        assertEquals(whole, parts)
    }

    @Test
    fun commands() {
        assertArrayEquals(CrpRingFrame.build(10, 3), CrpRingRecordingCommands.listFiles())
        assertArrayEquals(CrpRingFrame.build(10, 5, bytes(1) + name15("REC001.opus")), CrpRingRecordingCommands.querySize("REC001.opus"))
        assertArrayEquals(
            CrpRingFrame.build(10, 5, bytes(2, 0x00, 0x10, 0, 0, 0x00, 0x10, 0, 0) + name15("A")),
            CrpRingRecordingCommands.requestData("A", 4096, 4096)
        )
        assertArrayEquals(CrpRingFrame.build(10, 5, bytes(3, 0x12, 0x34) + name15("A")), CrpRingRecordingCommands.sendCrc("A", 0x1234))
        assertArrayEquals(CrpRingFrame.build(10, 5, bytes(4, 0xFF) + name15("A")), CrpRingRecordingCommands.endTransfer("A", false))
        assertArrayEquals(CrpRingFrame.build(10, 11), CrpRingRecordingCommands.queryStorage())
    }

    @Test
    fun fileListPage() {
        val page = CrpRingRecordingParsers.fileListPage(bytes(2, 0, 3, 0) + name15("A.opus") + name15("B.opus"))!!
        assertEquals(listOf("A.opus", "B.opus"), page.names)
        assertEquals(3, page.remaining)
        assertNull(CrpRingRecordingParsers.fileListPage(bytes(1, 0)))
    }

    @Test
    fun transferReplies() {
        assertEquals(
            CrpRingTransferReply(1, 0x1234L, "A.opus"),
            CrpRingRecordingParsers.transferReply(bytes(1, 0x34, 0x12, 0, 0) + name15("A.opus"))
        )
        assertEquals(
            CrpRingTransferReply(3, 1L, "A.opus"),
            CrpRingRecordingParsers.transferReply(bytes(3, 1) + name15("A.opus"))
        )
        assertNull(CrpRingRecordingParsers.transferReply(bytes(1, 0)))
    }

    @Test
    fun transferResultIsSigned() {
        assertEquals(-1L, CrpRingRecordingParsers.transferReply(bytes(3, 0xFF) + name15("A"))!!.value)
    }

    @Test
    fun recordingState() {
        assertEquals(1, CrpRingRecordingParsers.recordingState(bytes(1, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(CrpRingRecordingParsers.recordingState(bytes(1, 0)))
        assertArrayEquals(CrpRingFrame.build(10, 0), CrpRingRecordingCommands.queryState())
    }

    @Test
    fun safeFileNameRemovesPathParts() {
        assertEquals("REC_0001.opus", CrpRingRecordingParsers.safeFileName("REC 0001.opus"))
        assertEquals("_.._x", CrpRingRecordingParsers.safeFileName("/../x"))
        assertEquals("voice-note", CrpRingRecordingParsers.safeFileName(".."))
        assertEquals("voice-note", CrpRingRecordingParsers.safeFileName(""))
    }

    @Test
    fun fileMappingPage() {
        val payload = bytes(5, 1, 0, 2, 0, 10, 0, 0, 0, 20, 0, 0, 0) + name15("REAL1")
        val page = CrpRingRecordingParsers.fileMappingPage(payload)!!
        assertEquals(listOf(CrpRingSegment(10, 20, "REAL1")), page.segments)
        assertEquals(2, page.remaining)
        assertEquals(false, page.notFound)
        assertEquals(true, CrpRingRecordingParsers.fileMappingPage(bytes(5, 0xFF, 0xFF, 0xFF, 0xFF))!!.notFound)
        assertNull(CrpRingRecordingParsers.fileMappingPage(bytes(5, 1)))
        assertArrayEquals(CrpRingFrame.build(10, 5, bytes(5) + name15("A")), CrpRingRecordingCommands.queryFileMapping("A"))
    }
}
