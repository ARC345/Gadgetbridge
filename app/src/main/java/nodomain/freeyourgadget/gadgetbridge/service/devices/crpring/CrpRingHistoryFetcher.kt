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
import java.time.ZoneId

data class CrpRingValueSample(val timestampMillis: Long, val value: Int)

class CrpRingHistoryResult {
    val steps = mutableListOf<CrpRingValueSample>()
    val heartRate = mutableListOf<CrpRingValueSample>()
    val spo2 = mutableListOf<CrpRingValueSample>()
    val sleep = mutableListOf<CrpRingSleepStage>()
}

/**
 * Sends one history request at a time and collects the replies. The caller gives each
 * reply to [handle] and calls [onTimeout] when a reply does not come. Not thread-safe.
 */
class CrpRingHistoryFetcher(
    private val nowMillis: Long,
    private val zone: ZoneId,
    private val days: Int = CrpRingConstants.HISTORY_DAYS,
    private val send: (ByteArray) -> Unit,
) {
    companion object {
        /** A band that does not answer this many steps in a row is not answering at all. */
        const val MAX_CONSECUTIVE_TIMEOUTS = 3
    }

    private class Step(val request: ByteArray, val command: Int, val day: Int?, val page: Int?) {
        val description: String get() = "command=$command day=$day page=$page"

        fun matches(message: CrpRingMessage): Boolean {
            if (message.group != CrpRingConstants.GROUP_QUERY || message.command != command) return false
            val payload = message.payload
            if (day != null && (payload.isEmpty() || (payload[0].toInt() and 0xFF) != day)) return false
            if (page != null && (payload.size < 2 || (payload[1].toInt() and 0xFF) != page)) return false
            return true
        }
    }

    val result = CrpRingHistoryResult()
    private val skippedSteps = mutableListOf<String>()
    val skipped: List<String> get() = skippedSteps

    private val steps: List<Step> = buildSteps()
    private var index = -1
    private val pageZeroSize = HashMap<Pair<Int, Int>, Int>()
    private var consecutiveTimeouts = 0

    /** True if the fetch stopped because the band did not answer [MAX_CONSECUTIVE_TIMEOUTS] steps in a row. */
    var abortedAfterTimeouts = false
        private set

    val isFinished: Boolean get() = index >= steps.size
    private val current: Step? get() = steps.getOrNull(index)

    fun start() {
        index = -1
        advance()
    }

    fun handle(message: CrpRingMessage): Boolean {
        val step = current ?: return false
        if (!step.matches(message)) return false
        consecutiveTimeouts = 0
        consume(step, message.payload)
        advance()
        return true
    }

    fun onTimeout() {
        val step = current ?: return
        skippedSteps.add(step.description)
        consecutiveTimeouts++
        if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
            abortedAfterTimeouts = true
            index = steps.size
            return
        }
        advance()
    }

    /** Stops the fetch, for example when the connection drops. Nothing more is sent. */
    fun cancel() {
        index = steps.size
    }

    private fun advance() {
        index++
        current?.let { send(it.request) }
    }

    private fun buildSteps(): List<Step> {
        val list = ArrayList<Step>()
        for (day in days - 1 downTo 0) {
            list.add(Step(CrpRingCommands.stepsDetails(day), CrpRingConstants.CMD_QUERY_STEPS_DETAILS, day, null))
            list.add(Step(CrpRingCommands.sleep(day), CrpRingConstants.CMD_QUERY_SLEEP, day, null))
            for (page in 0..1) {
                list.add(Step(CrpRingCommands.timedHeartRate(day, page), CrpRingConstants.CMD_QUERY_TIMED_HEART_RATE, day, page))
            }
            for (page in 0..1) {
                list.add(Step(CrpRingCommands.timedSpo2(day, page), CrpRingConstants.CMD_QUERY_TIMED_SPO2, day, page))
            }
        }
        list.add(Step(CrpRingCommands.manualHeartRate(), CrpRingConstants.CMD_QUERY_MANUAL_HEART_RATE, null, null))
        list.add(Step(CrpRingCommands.manualSpo2(), CrpRingConstants.CMD_QUERY_MANUAL_SPO2, null, null))
        return list
    }

    private fun consume(step: Step, payload: ByteArray) {
        when (step.command) {
            CrpRingConstants.CMD_QUERY_STEPS_DETAILS -> consumeSteps(payload)
            CrpRingConstants.CMD_QUERY_SLEEP -> consumeSleep(payload)
            CrpRingConstants.CMD_QUERY_TIMED_HEART_RATE ->
                consumePage(step.command, payload, CrpRingConstants.HEART_RATE_RANGE, result.heartRate)
            CrpRingConstants.CMD_QUERY_TIMED_SPO2 ->
                consumePage(step.command, payload, CrpRingConstants.SPO2_RANGE, result.spo2)
            CrpRingConstants.CMD_QUERY_MANUAL_HEART_RATE ->
                consumeTimed(payload, CrpRingConstants.HEART_RATE_RANGE, result.heartRate)
            CrpRingConstants.CMD_QUERY_MANUAL_SPO2 ->
                consumeTimed(payload, CrpRingConstants.SPO2_RANGE, result.spo2)
        }
    }

    private fun consumeSteps(payload: ByteArray) {
        val details = CrpRingParsers.stepsDetails(payload) ?: return
        val start = CrpRingTime.startOfDay(details.day, nowMillis, zone)
        details.values.forEachIndexed { slot, steps ->
            val timestamp = start + slot * CrpRingConstants.STEPS_SLOT_MINUTES * 60_000L
            if (steps > 0 && timestamp <= nowMillis) result.steps.add(CrpRingValueSample(timestamp, steps))
        }
    }

    private fun consumeSleep(payload: ByteArray) {
        val sleep = CrpRingParsers.sleep(payload) ?: return
        CrpRingParsers.sleepStages(sleep, nowMillis, zone)
            .filter { it.startMillis <= nowMillis }
            .forEach { result.sleep.add(it) }
    }

    private fun consumePage(command: Int, payload: ByteArray, valid: IntRange, target: MutableList<CrpRingValueSample>) {
        val page = CrpRingParsers.page(payload) ?: return
        val key = Pair(command, page.day)
        val offset = if (page.page == 0) {
            pageZeroSize[key] = page.values.size
            0
        } else {
            pageZeroSize[key] ?: return
        }
        val start = CrpRingTime.startOfDay(page.day, nowMillis, zone)
        page.values.forEachIndexed { i, value ->
            val timestamp = start + (offset + i) * CrpRingConstants.TIMED_INTERVAL_MINUTES * 60_000L
            if (value in valid && timestamp <= nowMillis) target.add(CrpRingValueSample(timestamp, value))
        }
    }

    private fun consumeTimed(payload: ByteArray, valid: IntRange, target: MutableList<CrpRingValueSample>) {
        val values = CrpRingParsers.timedValues(payload) ?: return
        for (value in values) {
            val timestamp = CrpRingTime.fromDeviceSeconds(value.deviceSeconds, zone)
            if (value.value in valid && timestamp <= nowMillis) target.add(CrpRingValueSample(timestamp, value.value))
        }
    }
}
