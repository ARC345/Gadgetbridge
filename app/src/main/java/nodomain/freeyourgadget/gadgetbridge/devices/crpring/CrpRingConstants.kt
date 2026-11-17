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
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import java.util.UUID

/**
 * Constants of the MoYoung "CRP ring" BLE protocol (SDK com.crrepa.ring), as used by the
 * Pebble Qore Vox. Group 1 sets values, group 2 queries data.
 */
object CrpRingConstants {
    private fun uuid16(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

    val UUID_SERVICE: UUID = uuid16("fdda")
    val UUID_CHARACTERISTIC_STEPS: UUID = uuid16("fdd1")
    val UUID_CHARACTERISTIC_WRITE: UUID = uuid16("fdd2")
    val UUID_CHARACTERISTIC_NOTIFY: UUID = uuid16("fdd3")
    /** Raw file bytes (voice notes); no frame header. */
    val UUID_CHARACTERISTIC_DATA: UUID = uuid16("fdd6")

    const val GROUP_SET = 1
    const val GROUP_QUERY = 2
    const val GROUP_SYSTEM = 3
    const val GROUP_SLEEP = 7
    const val GROUP_ACTION = 9

    // Group 1
    const val CMD_SET_USER_INFO = 0
    const val CMD_SET_TIME = 1
    const val CMD_MEASURE_HEART_RATE = 9
    const val CMD_MEASURE_SPO2 = 11
    const val CMD_AUTH = 41

    // Group 2
    const val CMD_QUERY_MANUAL_HEART_RATE = 9
    const val CMD_QUERY_MANUAL_SPO2 = 11
    const val CMD_QUERY_SLEEP = 14
    const val CMD_QUERY_TIMED_HEART_RATE = 15
    const val CMD_QUERY_TIMED_SPO2 = 17
    const val CMD_QUERY_STEPS_DETAILS = 18

    // Group 3
    const val CMD_FIRMWARE_VERSION = 3
    const val CMD_BATTERY = 6

    // Group 7: sleep sessions (used when the band runs the GoMore algorithm; 2,14 is then not answered)
    const val CMD_SLEEP_SESSIONS = 4
    const val CMD_SLEEP_DETAILS = 5
    const val CMD_SLEEP_SESSION_STAGES = 6

    // Group 9
    const val CMD_FIND_DEVICE = 2

    // First payload byte of CMD_AUTH
    const val AUTH_QUERY = 0
    const val AUTH_SEND = 1
    const val AUTH_DELETE = -1

    const val SLEEP_AWAKE = 0
    const val SLEEP_LIGHT = 1
    const val SLEEP_DEEP = 2
    const val SLEEP_REM = 3

    const val SLEEP_TYPE_LONG = 1
    const val SLEEP_TYPE_NAP = 2

    const val GENDER_MALE = 0
    const val GENDER_FEMALE = 1

    const val TIMED_INTERVAL_MINUTES = 5
    const val STEPS_SLOT_MINUTES = 30
    const val HISTORY_DAYS = 7
    const val MTU = 512

    val HEART_RATE_RANGE = 40..200
    val SPO2_RANGE = 1..100
}
