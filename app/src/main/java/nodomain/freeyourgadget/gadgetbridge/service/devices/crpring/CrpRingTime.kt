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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The band keeps the local wall-clock time as if the zone is GMT+8. A device timestamp is
 * that wall-clock time in seconds.
 */
object CrpRingTime {
    private val DEVICE_OFFSET: ZoneOffset = ZoneOffset.ofHours(8)

    fun toDeviceSeconds(utcMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDateTime().toEpochSecond(DEVICE_OFFSET)

    fun fromDeviceSeconds(deviceSeconds: Long, zone: ZoneId): Long =
        LocalDateTime.ofEpochSecond(deviceSeconds, 0, DEVICE_OFFSET).atZone(zone).toInstant().toEpochMilli()

    fun startOfDay(daysAgo: Int, nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .minusDays(daysAgo.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
