/*  Copyright (C) 2026 Gadgetbridge contributors

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

/**
 * The expected frames are the ones the Vox app sent on the band, captured in a Bluetooth log.
 */
class CrpRingWorkoutCommandsTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    @Test
    fun startSendsWeightLiftingType() {
        assertEquals(
            "fd da 10 0c 01 0c 4f ff 00 00 00 00",
            hex(CrpRingCommands.startWorkout(CrpRingCommands.WORKOUT_WEIGHT_LIFTING))
        )
    }

    @Test
    fun pauseSendsPauseState() {
        assertEquals("fd da 10 0c 01 0c fe ff 00 00 00 00", hex(CrpRingCommands.pauseWorkout()))
    }

    @Test
    fun endSendsEndState() {
        assertEquals("fd da 10 0c 01 0c ff ff 00 00 00 00", hex(CrpRingCommands.endWorkout()))
    }
}
