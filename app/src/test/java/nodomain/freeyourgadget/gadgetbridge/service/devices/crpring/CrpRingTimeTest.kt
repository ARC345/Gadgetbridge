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

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CrpRingTimeTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun toDeviceWritesLocalClockAsGmt8() {
        // 06:30Z is 12:00 in Kolkata. 12:00 at +08:00 is 04:00Z.
        val expected = Instant.parse("2026-11-16T04:00:00Z").epochSecond
        assertEquals(expected, CrpRingTime.toDeviceSeconds(ms("2026-11-16T06:30:00Z"), kolkata))
    }

    @Test
    fun fromDeviceIsTheInverse() {
        val device = Instant.parse("2026-11-16T04:00:00Z").epochSecond
        assertEquals(ms("2026-11-16T06:30:00Z"), CrpRingTime.fromDeviceSeconds(device, kolkata))
    }

    @Test
    fun roundTripInBerlinSummerAndWinter() {
        for (text in listOf("2026-07-01T10:15:00Z", "2026-12-01T10:15:00Z")) {
            val utc = ms(text)
            assertEquals(utc, CrpRingTime.fromDeviceSeconds(CrpRingTime.toDeviceSeconds(utc, berlin), berlin))
        }
    }

    @Test
    fun startOfDayUsesLocalMidnight() {
        // 2026-03-30 10:00 in Berlin (summer time). One day ago is 2026-03-29, which starts at +01:00.
        val now = ms("2026-03-30T08:00:00Z")
        assertEquals(ms("2026-03-28T23:00:00Z"), CrpRingTime.startOfDay(1, now, berlin))
        assertEquals(ms("2026-03-29T22:00:00Z"), CrpRingTime.startOfDay(0, now, berlin))
    }
}
