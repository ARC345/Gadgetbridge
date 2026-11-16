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

import nodomain.freeyourgadget.gadgetbridge.devices.crpring.CrpRingConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CrpRingParsersTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun stepsDetailsReadsUint16Values() {
        val result = CrpRingParsers.stepsDetails(bytes(1, 0x10, 0x00, 0x00, 0x01))!!
        assertEquals(1, result.day)
        assertEquals(listOf(16, 256), result.values)
    }

    @Test
    fun stepsDetailsRejectsEvenLength() {
        assertNull(CrpRingParsers.stepsDetails(bytes(1, 0x10)))
    }

    @Test
    fun pageReadsDayPageAndValues() {
        val result = CrpRingParsers.page(bytes(2, 1, 70, 0, 72))!!
        assertEquals(CrpRingPage(2, 1, listOf(70, 0, 72)), result)
    }

    @Test
    fun timedValuesReadValueAndTimestamp() {
        val result = CrpRingParsers.timedValues(bytes(0, 72, 0x78, 0x56, 0x34, 0x12))!!
        assertEquals(listOf(CrpRingTimedValue(0x12345678L, 72)), result)
        assertNull(CrpRingParsers.timedValues(bytes(0, 72, 1)))
    }

    @Test
    fun authResult() {
        assertTrue(CrpRingParsers.authResult(bytes(1, 0))!!.isOk)
        assertFalse(CrpRingParsers.authResult(bytes(1, 1))!!.isOk)
        assertEquals(CrpRingConstants.AUTH_DELETE, CrpRingParsers.authResult(bytes(0xFF, 0))!!.operation)
        assertNull(CrpRingParsers.authResult(bytes(1)))
    }

    @Test
    fun textDropsTrailingZeros() {
        assertEquals("MOY-ABC1-1.2.3", CrpRingParsers.text("MOY-ABC1-1.2.3".toByteArray() + bytes(0, 0)))
    }

    @Test
    fun sleepAcrossMidnight() {
        val now = ms("2026-11-16T04:30:00Z") // 10:00 in Kolkata
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 23, 0, 2, 0, 30, 3, 2, 0, 0, 6, 0))!!
        val stages = CrpRingParsers.sleepStages(sleep, now, kolkata)
        assertEquals(
            listOf(
                CrpRingSleepStage(ms("2026-11-15T17:30:00Z"), 90, CrpRingConstants.SLEEP_LIGHT),
                CrpRingSleepStage(ms("2026-11-15T19:00:00Z"), 90, CrpRingConstants.SLEEP_DEEP),
                CrpRingSleepStage(ms("2026-11-15T20:30:00Z"), 240, CrpRingConstants.SLEEP_REM),
            ),
            stages
        )
    }

    @Test
    fun sleepAcrossDstChange() {
        // Night of 2026-10-24/25 in Berlin: clocks go back at 03:00 to 02:00.
        val berlin = ZoneId.of("Europe/Berlin")
        val now = ms("2026-10-25T09:00:00Z")
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 23, 0, 0, 7, 0))!!
        val stages = CrpRingParsers.sleepStages(sleep, now, berlin)
        assertEquals(listOf(CrpRingSleepStage(ms("2026-10-24T21:00:00Z"), 540, CrpRingConstants.SLEEP_LIGHT)), stages)
    }

    @Test
    fun sleepStartingAfterMidnightStaysOnTheDay() {
        val now = ms("2026-11-16T04:30:00Z")
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 1, 0, 0, 5, 0))!!
        assertEquals(
            listOf(CrpRingSleepStage(ms("2026-11-15T19:30:00Z"), 240, CrpRingConstants.SLEEP_LIGHT)),
            CrpRingParsers.sleepStages(sleep, now, kolkata)
        )
    }

    @Test
    fun sleepWithBadClockValuesIsEmpty() {
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 25, 0, 0, 6, 0))!!
        assertTrue(CrpRingParsers.sleepStages(sleep, ms("2026-11-16T04:30:00Z"), kolkata).isEmpty())
    }

    @Test
    fun sleepRejectsBadLength() {
        assertNull(CrpRingParsers.sleep(bytes(0, 1, 23)))
    }

    @Test
    fun commandsSetTime() {
        val frame = CrpRingCommands.setTime(ms("2026-11-16T06:30:00Z"), kolkata)
        val seconds = Instant.parse("2026-11-16T04:00:00Z").epochSecond
        val le = bytes(
            (seconds and 0xFF).toInt(), ((seconds shr 8) and 0xFF).toInt(),
            ((seconds shr 16) and 0xFF).toInt(), ((seconds shr 24) and 0xFF).toInt()
        )
        assertArrayEquals(CrpRingFrame.build(1, 1, le + bytes(8)), frame)
    }

    @Test
    fun commandsSendPin() {
        assertArrayEquals(
            CrpRingFrame.build(1, 41, bytes(1, 8) + "12345678".toByteArray()),
            CrpRingCommands.sendPin("12345678")
        )
    }

    @Test
    fun commandsUserInfoClampsValues() {
        assertArrayEquals(
            CrpRingFrame.build(1, 0, bytes(180, 255, 30, 1, 75)),
            CrpRingCommands.userInfo(180, 300, 30, CrpRingConstants.GENDER_FEMALE, 75)
        )
    }

    @Test
    fun commandsQueries() {
        assertArrayEquals(CrpRingFrame.build(2, 18, bytes(3)), CrpRingCommands.stepsDetails(3))
        assertArrayEquals(CrpRingFrame.build(2, 14, bytes(3)), CrpRingCommands.sleep(3))
        assertArrayEquals(CrpRingFrame.build(2, 15, bytes(3, 1)), CrpRingCommands.timedHeartRate(3, 1))
        assertArrayEquals(CrpRingFrame.build(2, 17, bytes(3, 0)), CrpRingCommands.timedSpo2(3, 0))
        assertArrayEquals(CrpRingFrame.build(2, 9), CrpRingCommands.manualHeartRate())
        assertArrayEquals(CrpRingFrame.build(2, 11), CrpRingCommands.manualSpo2())
        assertArrayEquals(CrpRingFrame.build(9, 2, bytes(1)), CrpRingCommands.findDevice(true))
        assertArrayEquals(CrpRingFrame.build(1, 9, bytes(0)), CrpRingCommands.measureHeartRate(false))
        assertArrayEquals(CrpRingFrame.build(3, 3), CrpRingCommands.firmwareVersion())
    }
}
