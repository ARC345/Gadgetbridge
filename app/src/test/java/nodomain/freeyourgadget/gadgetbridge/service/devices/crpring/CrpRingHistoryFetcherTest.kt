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
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()
    private fun le32(value: Long) = bytes(
        (value and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(),
        ((value shr 16) and 0xFF).toInt(), ((value shr 24) and 0xFF).toInt()
    )
    private fun device(utcText: String) = CrpRingTime.toDeviceSeconds(ms(utcText), zone)
    private fun reply(group: Int, command: Int, payload: ByteArray) = CrpRingMessage(group, command, payload)

    /** Replies for the steps of a day up to SpO2: steps, HR page 0 and 1, SpO2 page 0 and 1. */
    private fun repliesUntilHrv(day: Int = 0) = listOf(
        reply(2, 18, bytes(day)),
        reply(2, 15, bytes(day, 0)), reply(2, 15, bytes(day, 1)),
        reply(2, 17, bytes(day, 0)), reply(2, 17, bytes(day, 1)),
    )

    private fun hrvReplies(day: Int = 0) = (0..3).map { reply(2, 16, bytes(day, it)) }
    private fun stressReplies(day: Int = 0) = (0..1).map { reply(2, 47, bytes(day, it)) }

    /** Replies for the steps of a day: [repliesUntilHrv], HRV pages 0 to 3, stress pages 0 and 1. */
    private fun dayReplies(day: Int = 0) = repliesUntilHrv(day) + hrvReplies(day) + stressReplies(day)

    private fun answer(replies: List<CrpRingMessage>, target: CrpRingHistoryFetcher = fetcher) =
        replies.forEach { assertTrue(target.handle(it)) }

    private fun answerDay() = answer(dayReplies())

    @Test
    fun sendsRequestsInOrderAndFinishes() {
        fetcher.start()
        answerDay()
        assertTrue(fetcher.handle(reply(2, 9, bytes(0))))
        assertTrue(fetcher.handle(reply(2, 11, bytes(0))))
        assertFalse(fetcher.isFinished)
        assertTrue(fetcher.handle(reply(7, 4, ByteArray(0))))
        assertTrue(fetcher.isFinished)
        val expected = listOf(
            CrpRingCommands.stepsDetails(0),
            CrpRingCommands.timedHeartRate(0, 0), CrpRingCommands.timedHeartRate(0, 1),
            CrpRingCommands.timedSpo2(0, 0), CrpRingCommands.timedSpo2(0, 1),
            CrpRingCommands.timedHrv(0, 0), CrpRingCommands.timedHrv(0, 1),
            CrpRingCommands.timedHrv(0, 2), CrpRingCommands.timedHrv(0, 3),
            CrpRingCommands.timedStress(0, 0), CrpRingCommands.timedStress(0, 1),
            CrpRingCommands.manualHeartRate(), CrpRingCommands.manualSpo2(),
            CrpRingCommands.sleepSessions(),
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
        fetcher.handle(reply(2, 15, bytes(0, 0, 30, 210, 60)))
        assertEquals(listOf(CrpRingValueSample(midnight + 2 * 5 * 60_000L, 60)), fetcher.result.heartRate)
    }

    @Test
    fun pageOneWithoutPageZeroIsSkipped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.onTimeout()
        assertTrue(fetcher.handle(reply(2, 15, bytes(0, 1, 80))))
        assertTrue(fetcher.result.heartRate.isEmpty())
    }

    @Test
    fun timeoutSkipsStep() {
        fetcher.start()
        fetcher.onTimeout()
        assertArrayEquals(CrpRingCommands.timedHeartRate(0, 0), sent.last())
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
        answerDay()
        val nineLocal = ms("2026-11-16T03:30:00Z")
        fetcher.handle(reply(2, 9, bytes(0, 75) + le32(CrpRingTime.toDeviceSeconds(nineLocal, zone))))
        assertEquals(listOf(CrpRingValueSample(nineLocal, 75)), fetcher.result.heartRate)
    }

    private fun answerUntilSleep() {
        answerDay()
        assertTrue(fetcher.handle(reply(2, 9, bytes(0))))
        assertTrue(fetcher.handle(reply(2, 11, bytes(0))))
    }

    @Test
    fun sleepSessionAddsDetailsAndStageSteps() {
        fetcher.start()
        answerUntilSleep()
        // Session 0 is invalid (time 0); session 1 started 2026-11-15 23:00 local.
        val start = device("2026-11-15T17:30:00Z")
        val end = device("2026-11-16T00:30:00Z")
        assertTrue(fetcher.handle(reply(7, 4, le32(0) + le32(start))))
        assertArrayEquals(CrpRingCommands.sleepDetails(1), sent.last())
        val details = bytes(1, CrpRingConstants.SLEEP_TYPE_LONG) + le32(start) + le32(end) + ByteArray(12)
        assertTrue(fetcher.handle(reply(7, 5, details)))
        assertArrayEquals(CrpRingCommands.sleepSessionStages(1), sent.last())
        assertTrue(fetcher.handle(reply(7, 6, bytes(1, 1, 23, 0, 2, 1, 0, 0, 6, 0))))
        assertEquals(
            listOf(
                CrpRingSleepStage(ms("2026-11-15T17:30:00Z"), 120, CrpRingConstants.SLEEP_LIGHT),
                CrpRingSleepStage(ms("2026-11-15T19:30:00Z"), 300, CrpRingConstants.SLEEP_DEEP),
            ),
            fetcher.result.sleep
        )
        assertTrue(fetcher.isFinished)
    }

    @Test
    fun napSessionHasNoStageStep() {
        fetcher.start()
        answerUntilSleep()
        val start = device("2026-11-16T08:30:00Z")
        fetcher.handle(reply(7, 4, le32(start)))
        val details = bytes(0, CrpRingConstants.SLEEP_TYPE_NAP) + le32(start) + le32(start + 1800) + ByteArray(12)
        assertTrue(fetcher.handle(reply(7, 5, details)))
        assertTrue(fetcher.isFinished)
    }

    @Test
    fun sleepSessionsTimeoutFallsBackToDailySleep() {
        fetcher.start()
        answerUntilSleep()
        fetcher.onTimeout()
        assertArrayEquals(CrpRingCommands.sleep(0), sent.last())
        assertTrue(fetcher.handle(reply(2, 14, bytes(0, 1, 23, 0, 0, 6, 0))))
        assertEquals(1, fetcher.result.sleep.size)
        assertEquals(420, fetcher.result.sleep[0].durationMinutes)
        assertTrue(fetcher.isFinished)
    }

    @Test
    fun messagesAfterFinishAreNotConsumed() {
        fetcher.start()
        answerUntilSleep()
        fetcher.handle(reply(7, 4, ByteArray(0)))
        assertFalse(fetcher.handle(reply(7, 4, ByteArray(0))))
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
        assertTrue(fetcher.handle(reply(2, 15, bytes(0, 1))))
        fetcher.onTimeout()
        fetcher.onTimeout()
        assertFalse(fetcher.isFinished)
        assertFalse(fetcher.abortedAfterTimeouts)
    }

    @Test
    fun detailsReplyWithAnotherIdIsAcceptedAndItsIdIsUsed() {
        // The vendor asks 7,5 by list index and then 7,6 with the id from the 7,5 reply.
        fetcher.start()
        answerUntilSleep()
        val start = device("2026-11-15T17:30:00Z")
        val end = device("2026-11-16T00:30:00Z")
        fetcher.handle(reply(7, 4, le32(start)))
        assertArrayEquals(CrpRingCommands.sleepDetails(0), sent.last())
        val details = bytes(5, CrpRingConstants.SLEEP_TYPE_LONG) + le32(start) + le32(end) + ByteArray(12)
        assertTrue(fetcher.handle(reply(7, 5, details)))
        assertArrayEquals(CrpRingCommands.sleepSessionStages(5), sent.last())
        assertTrue(fetcher.handle(reply(7, 6, bytes(5, 1, 23, 0, 0, 6, 0))))
        assertEquals(1, fetcher.result.sleep.size)
    }

    @Test
    fun timedHrvUsesTheOffsetOfAllEarlierPages() {
        fetcher.start()
        answer(repliesUntilHrv())
        assertTrue(fetcher.handle(reply(2, 16, bytes(0, 0, 40, 0, 0, 0))))
        assertTrue(fetcher.handle(reply(2, 16, bytes(0, 1, 50, 0))))
        assertTrue(fetcher.handle(reply(2, 16, bytes(0, 2, 0x2C, 0x01))))
        assertTrue(fetcher.handle(reply(2, 16, bytes(0, 3))))
        assertEquals(
            listOf(
                CrpRingValueSample(midnight, 40),
                CrpRingValueSample(midnight + 2 * 5 * 60_000L, 50),
                CrpRingValueSample(midnight + 3 * 5 * 60_000L, 300),
            ),
            fetcher.result.hrv
        )
    }

    @Test
    fun invalidHrvValuesAreDropped() {
        fetcher.start()
        answer(repliesUntilHrv())
        fetcher.handle(reply(2, 16, bytes(0, 0, 0, 0, 0xFF, 0xFF, 35, 0)))
        assertEquals(listOf(CrpRingValueSample(midnight + 2 * 5 * 60_000L, 35)), fetcher.result.hrv)
    }

    @Test
    fun futureHrvSlotsAreDropped() {
        fetcher.start()
        answer(repliesUntilHrv())
        val page0 = ByteArray(2 + 146 * 2)
        for (i in 0 until 146) page0[2 + i * 2] = 40
        fetcher.handle(reply(2, 16, page0))
        // Slots 0..144 start at or before 12:00.
        assertEquals(145, fetcher.result.hrv.size)
        assertEquals(CrpRingValueSample(now, 40), fetcher.result.hrv.last())
    }

    @Test
    fun timedStressIsStoredAndInvalidValuesAreDropped() {
        fetcher.start()
        answer(repliesUntilHrv() + hrvReplies())
        assertTrue(fetcher.handle(reply(2, 47, bytes(0, 0, 20, 0, 101, 55))))
        assertTrue(fetcher.handle(reply(2, 47, bytes(0, 1, 30))))
        assertEquals(
            listOf(
                CrpRingValueSample(midnight, 20),
                CrpRingValueSample(midnight + 3 * 5 * 60_000L, 55),
                CrpRingValueSample(midnight + 4 * 5 * 60_000L, 30),
            ),
            fetcher.result.stress
        )
    }

    @Test
    fun unansweredHrvDropsTheOtherHrvSteps() {
        val twoDays = CrpRingHistoryFetcher(now, zone, 2) { sent.add(it) }
        twoDays.start()
        answer(repliesUntilHrv(1), twoDays)
        twoDays.onTimeout() // the band does not know 2,16
        assertArrayEquals(CrpRingCommands.timedStress(1, 0), sent.last())
        answer(stressReplies(1) + repliesUntilHrv(0), twoDays)
        assertArrayEquals(CrpRingCommands.timedStress(0, 0), sent.last())
    }

    @Test
    fun unansweredStressDropsTheOtherStressSteps() {
        val twoDays = CrpRingHistoryFetcher(now, zone, 2) { sent.add(it) }
        twoDays.start()
        answer(repliesUntilHrv(1) + hrvReplies(1), twoDays)
        twoDays.onTimeout() // the band does not know 2,47
        assertArrayEquals(CrpRingCommands.stepsDetails(0), sent.last())
        answer(repliesUntilHrv(0) + hrvReplies(0), twoDays)
        assertArrayEquals(CrpRingCommands.manualHeartRate(), sent.last())
    }

    @Test
    fun hrvTimeoutAfterAReplyOnlySkipsThatPage() {
        val twoDays = CrpRingHistoryFetcher(now, zone, 2) { sent.add(it) }
        twoDays.start()
        answer(repliesUntilHrv(1) + hrvReplies(1).take(1), twoDays)
        twoDays.onTimeout()
        assertArrayEquals(CrpRingCommands.timedHrv(1, 2), sent.last())
    }

    @Test
    fun sleepFallbackDoesNotLoseManualValues() {
        fetcher.start()
        answerDay()
        val nineLocal = ms("2026-11-16T03:30:00Z")
        fetcher.handle(reply(2, 9, bytes(0, 75) + le32(CrpRingTime.toDeviceSeconds(nineLocal, zone))))
        fetcher.handle(reply(2, 11, bytes(0)))
        repeat(3) { fetcher.onTimeout() } // 7,4 and then 2,14 are not answered
        assertTrue(fetcher.isFinished)
        assertEquals(listOf(CrpRingValueSample(nineLocal, 75)), fetcher.result.heartRate)
    }
}
