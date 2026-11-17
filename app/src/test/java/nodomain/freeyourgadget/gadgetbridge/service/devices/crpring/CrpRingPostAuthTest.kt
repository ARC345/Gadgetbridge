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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrpRingPostAuthTest {
    private var sends = 0
    private val gate = CrpRingPostAuth { sends++ }

    @Test
    fun acceptedPinSendsOnce() {
        gate.onAuthResult(true)
        gate.onAuthResult(true)
        assertEquals(1, sends)
        assertFalse(gate.rejected)
    }

    @Test
    fun rejectedPinSendsNothing() {
        gate.onAuthResult(false)
        gate.onTimeout()
        assertEquals(0, sends)
        assertTrue(gate.rejected)
    }

    @Test
    fun noReplySendsAfterTimeout() {
        gate.onTimeout()
        gate.onAuthResult(true)
        assertEquals(1, sends)
    }

    @Test
    fun resetAllowsANewConnection() {
        gate.onAuthResult(true)
        gate.reset()
        gate.onAuthResult(true)
        assertEquals(2, sends)
    }
}
