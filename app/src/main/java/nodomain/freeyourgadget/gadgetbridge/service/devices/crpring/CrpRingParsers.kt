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

import java.time.Instant
import java.time.ZoneId

data class CrpRingDayValues(val day: Int, val values: List<Int>)
data class CrpRingPage(val day: Int, val page: Int, val values: List<Int>)
data class CrpRingTimedValue(val deviceSeconds: Long, val value: Int)
data class CrpRingSleepEntry(val stage: Int, val hour: Int, val minute: Int)
data class CrpRingSleep(val day: Int, val entries: List<CrpRingSleepEntry>)
data class CrpRingSleepStage(val startMillis: Long, val durationMinutes: Int, val stage: Int)

data class CrpRingAuthResult(val operation: Int, val result: Int) {
    val isOk: Boolean get() = result == 0
}

/** Parsers for reply payloads (the frame header is already removed). */
object CrpRingParsers {
    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    /** 2,18: [day, u16 * 48], steps for each 30 minutes. */
    fun stepsDetails(payload: ByteArray): CrpRingDayValues? {
        if (payload.isEmpty() || payload.size % 2 != 1) return null
        return CrpRingDayValues(u8(payload, 0), (1 until payload.size step 2).map { u16(payload, it) })
    }

    /** 2,15 and 2,17: [day, page, u8 * n], one value for each 5 minutes. */
    fun page(payload: ByteArray): CrpRingPage? {
        if (payload.size < 2) return null
        return CrpRingPage(u8(payload, 0), u8(payload, 1), (2 until payload.size).map { u8(payload, it) })
    }

    /** 2,9 and 2,11: [x, (value u8, device timestamp u32) * n]. */
    fun timedValues(payload: ByteArray): List<CrpRingTimedValue>? {
        if (payload.isEmpty() || payload.size % 5 != 1) return null
        return (1 until payload.size step 5).map { CrpRingTimedValue(u32(payload, it + 1), u8(payload, it)) }
    }

    /** 2,14: [day, (stage, hour, minute) * n]. */
    fun sleep(payload: ByteArray): CrpRingSleep? {
        if (payload.isEmpty() || payload.size % 3 != 1) return null
        val entries = (1 until payload.size step 3).map {
            CrpRingSleepEntry(u8(payload, it), u8(payload, it + 1), u8(payload, it + 2))
        }
        return CrpRingSleep(u8(payload, 0), entries)
    }

    /**
     * Each entry starts a stage that ends at the next entry. The last entry has no end, so it
     * is not returned. A clock time before the previous one is on the next date. The first
     * entry is on the date before the day if its hour is 12 or more.
     */
    fun sleepStages(sleep: CrpRingSleep, nowMillis: Long, zone: ZoneId): List<CrpRingSleepStage> {
        val entries = sleep.entries
        if (entries.size < 2) return emptyList()
        if (entries.any { it.hour > 23 || it.minute > 59 }) return emptyList()
        val anchor = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().minusDays(sleep.day.toLong())
        var date = if (entries[0].hour >= 12) anchor.minusDays(1) else anchor
        var previousMinuteOfDay = -1
        val starts = entries.map { entry ->
            val minuteOfDay = entry.hour * 60 + entry.minute
            if (previousMinuteOfDay >= 0 && minuteOfDay < previousMinuteOfDay) date = date.plusDays(1)
            previousMinuteOfDay = minuteOfDay
            date.atTime(entry.hour, entry.minute).atZone(zone).toInstant().toEpochMilli()
        }
        val stages = ArrayList<CrpRingSleepStage>()
        for (i in 0 until entries.size - 1) {
            val duration = ((starts[i + 1] - starts[i]) / 60_000L).toInt()
            if (duration > 0) stages.add(CrpRingSleepStage(starts[i], duration, entries[i].stage))
        }
        return stages
    }

    fun text(payload: ByteArray): String = String(payload, Charsets.UTF_8).trimEnd('\u0000').trim()

    /** 1,41: [operation, result]. The operation byte is signed (-1 = delete). */
    fun authResult(payload: ByteArray): CrpRingAuthResult? {
        if (payload.size < 2) return null
        return CrpRingAuthResult(payload[0].toInt(), u8(payload, 1))
    }

    fun firstByte(payload: ByteArray): Int? = if (payload.isEmpty()) null else u8(payload, 0)
}
