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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrpRingFrameTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun buildAddsHeaderAndLength() {
        assertArrayEquals(
            bytes(0xFD, 0xDA, 0x10, 0x07, 0x09, 0x02, 0x01),
            CrpRingFrame.build(9, 2, bytes(1))
        )
    }

    @Test
    fun buildSetsBitEightForLongFrames() {
        val frame = CrpRingFrame.build(2, 14, ByteArray(300))
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        assertEquals(306 and 0xFF, frame[3].toInt() and 0xFF)
        assertEquals(306, CrpRingFrame.frameLength(frame))
    }

    @Test
    fun frameLengthRejectsBadFlags() {
        assertNull(CrpRingFrame.frameLength(bytes(0xFD, 0xDA, 0x40, 0x07, 0x09, 0x02, 0x01)))
        assertNull(CrpRingFrame.frameLength(bytes(0xFE, 0xEA, 0x10, 0x07, 0x09, 0x02, 0x01)))
    }

    @Test
    fun assemblerReturnsSinglePartFrame() {
        val message = CrpRingFrameAssembler().add(bytes(0xFD, 0xDA, 0x10, 0x08, 0x01, 0x29, 0x01, 0x00), 0)
        assertNotNull(message)
        assertEquals(1, message!!.group)
        assertEquals(41, message.command)
        assertArrayEquals(bytes(1, 0), message.payload)
    }

    @Test
    fun assemblerJoinsParts() {
        val payload = ByteArray(44) { it.toByte() }
        val parts = CrpRingFrame.split(CrpRingFrame.build(2, 18, payload), 20)
        assertEquals(3, parts.size)
        val assembler = CrpRingFrameAssembler()
        assertNull(assembler.add(parts[0], 0))
        assertNull(assembler.add(parts[1], 10))
        val message = assembler.add(parts[2], 20)
        assertNotNull(message)
        assertEquals(18, message!!.command)
        assertArrayEquals(payload, message.payload)
    }

    @Test
    fun assemblerDropsContinuationWithoutStart() {
        assertNull(CrpRingFrameAssembler().add(bytes(0x01, 0x02, 0x03), 0))
    }

    @Test
    fun assemblerDropsStalePartialFrame() {
        val parts = CrpRingFrame.split(CrpRingFrame.build(2, 18, ByteArray(30)), 20)
        val assembler = CrpRingFrameAssembler()
        assertNull(assembler.add(parts[0], 0))
        assertNull(assembler.add(parts[1], 3000))
    }

    @Test
    fun newStartReplacesPartialFrame() {
        val longParts = CrpRingFrame.split(CrpRingFrame.build(2, 18, ByteArray(30)), 20)
        val assembler = CrpRingFrameAssembler()
        assertNull(assembler.add(longParts[0], 0))
        val message = assembler.add(CrpRingFrame.build(3, 6, bytes(80)), 10)
        assertNotNull(message)
        assertEquals(6, message!!.command)
    }

    @Test
    fun assemblerReadsCommand255() {
        val message = CrpRingFrameAssembler().add(bytes(0xFD, 0xDA, 0x10, 0x06, 0x07, 0xFF), 0)
        assertEquals(255, message!!.command)
    }
}
