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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CrpRingHistoryFetcherTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-16T06:30:00Z").toEpochMilli() // 12:00 local
    private val midnight = Instant.parse("2026-11-15T18:30:00Z").toEpochMilli() // 00:00 local
    private val sent = mutableListOf<ByteArray>()
    private val fetcher = CrpRingHistoryFetcher(now, zone, 1) { sent.add(it) }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun reply(group: Int, command: Int, payload: ByteArray) = CrpRingMessage(group, command, payload)
    private fun emptyReplies() = listOf(
        reply(2, 18, bytes(0)), reply(2, 14, bytes(0)),
        reply(2, 15, bytes(0, 0)), reply(2, 15, bytes(0, 1)),
        reply(2, 17, bytes(0, 0)), reply(2, 17, bytes(0, 1)),
        reply(2, 9, bytes(0)), reply(2, 11, bytes(0)),
    )

    @Test
    fun sendsRequestsInOrderAndFinishes() {
        fetcher.start()
        for (message in emptyReplies()) {
            assertFalse(fetcher.isFinished)
            assertTrue(fetcher.handle(message))
        }
        assertTrue(fetcher.isFinished)
        val expected = listOf(
            CrpRingCommands.stepsDetails(0), CrpRingCommands.sleep(0),
            CrpRingCommands.timedHeartRate(0, 0), CrpRingCommands.timedHeartRate(0, 1),
            CrpRingCommands.timedSpo2(0, 0), CrpRingCommands.timedSpo2(0, 1),
            CrpRingCommands.manualHeartRate(), CrpRingCommands.manualSpo2(),
        )
        assertEquals(expected.size, sent.size)
        expected.forEachIndexed { i, frame -> assertArrayEquals(frame, sent[i]) }
    }

    @Test
    fun oldestDayComesFirst() {
        CrpRingHistoryFetcher(now, zone, 3) { sent.add(it) }.start()
        assertArrayEquals(CrpRingCommands.stepsDetails(2), sent[0])
    }

    @Test
    fun futureStepSlotsAreDropped() {
        fetcher.start()
        val payload = ByteArray(97)
        for (i in 0 until 48) payload[1 + i * 2] = 100
        fetcher.handle(reply(2, 18, payload))
        // Slots 0..24 start at or before 12:00.
        assertEquals(25, fetcher.result.steps.size)
        assertEquals(CrpRingValueSample(midnight, 100), fetcher.result.steps.first())
        assertEquals(CrpRingValueSample(now, 100), fetcher.result.steps.last())
    }

    @Test
    fun zeroStepSlotsAreDropped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0, 0, 0, 5, 0)))
        assertEquals(listOf(CrpRingValueSample(midnight + 30 * 60_000L, 5)), fetcher.result.steps)
    }

    @Test
    fun timedHeartRateUsesPageOffset() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0)))
        val page0 = ByteArray(146)
        page0[2] = 70
        fetcher.handle(reply(2, 15, page0))
        fetcher.handle(reply(2, 15, bytes(0, 1, 80)))
        assertEquals(
            listOf(CrpRingValueSample(midnight, 70), CrpRingValueSample(midnight + 144 * 5 * 60_000L, 80)),
            fetcher.result.heartRate
        )
    }

    @Test
    fun invalidHeartRateValuesAreDropped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0)))
        fetcher.handle(reply(2, 15, bytes(0, 0, 30, 210, 60)))
        assertEquals(listOf(CrpRingValueSample(midnight + 2 * 5 * 60_000L, 60)), fetcher.result.heartRate)
    }

    @Test
    fun pageOneWithoutPageZeroIsSkipped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0)))
        fetcher.onTimeout()
        assertTrue(fetcher.handle(reply(2, 15, bytes(0, 1, 80))))
        assertTrue(fetcher.result.heartRate.isEmpty())
    }

    @Test
    fun timeoutSkipsStep() {
        fetcher.start()
        fetcher.onTimeout()
        assertArrayEquals(CrpRingCommands.sleep(0), sent.last())
        assertEquals(1, fetcher.skipped.size)
    }

    @Test
    fun unrelatedMessageIsNotConsumed() {
        fetcher.start()
        assertFalse(fetcher.handle(reply(1, 9, bytes(72))))
        assertFalse(fetcher.handle(reply(2, 18, bytes(1))))
        assertEquals(1, sent.size)
    }

    @Test
    fun manualHeartRateUsesDeviceTimestamps() {
        fetcher.start()
        emptyReplies().take(6).forEach { fetcher.handle(it) }
        val nineLocal = Instant.parse("2026-11-16T03:30:00Z").toEpochMilli()
        val seconds = CrpRingTime.toDeviceSeconds(nineLocal, zone)
        val payload = bytes(0, 75, (seconds and 0xFF).toInt(), ((seconds shr 8) and 0xFF).toInt(),
            ((seconds shr 16) and 0xFF).toInt(), ((seconds shr 24) and 0xFF).toInt())
        fetcher.handle(reply(2, 9, payload))
        assertEquals(listOf(CrpRingValueSample(nineLocal, 75)), fetcher.result.heartRate)
    }

    @Test
    fun sleepStagesAreCollected() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0, 1, 23, 0, 0, 6, 0)))
        assertEquals(1, fetcher.result.sleep.size)
        assertEquals(420, fetcher.result.sleep[0].durationMinutes)
    }

    @Test
    fun messagesAfterFinishAreNotConsumed() {
        fetcher.start()
        emptyReplies().forEach { fetcher.handle(it) }
        assertFalse(fetcher.handle(reply(2, 11, bytes(0))))
    }

    @Test
    fun cancelStopsTheFetch() {
        fetcher.start()
        fetcher.cancel()
        assertTrue(fetcher.isFinished)
        fetcher.onTimeout()
        assertFalse(fetcher.handle(reply(2, 18, bytes(0))))
        assertEquals(1, sent.size)
    }

    @Test
    fun threeTimeoutsInARowStopTheFetch() {
        fetcher.start()
        repeat(3) { fetcher.onTimeout() }
        assertTrue(fetcher.isFinished)
        assertTrue(fetcher.abortedAfterTimeouts)
        assertEquals(3, sent.size)
    }

    @Test
    fun aReplyResetsTheTimeoutCount() {
        fetcher.start()
        fetcher.onTimeout()
        fetcher.onTimeout()
        assertTrue(fetcher.handle(reply(2, 15, bytes(0, 0))))
        fetcher.onTimeout()
        fetcher.onTimeout()
        assertFalse(fetcher.isFinished)
        assertFalse(fetcher.abortedAfterTimeouts)
    }
}
