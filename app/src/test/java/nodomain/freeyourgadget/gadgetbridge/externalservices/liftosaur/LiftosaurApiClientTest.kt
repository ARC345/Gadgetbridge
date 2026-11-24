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
package nodomain.freeyourgadget.gadgetbridge.externalservices.liftosaur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftosaurApiClientTest {

    @Test
    fun formatsWholeKilogramsWithoutDecimals() {
        assertEquals("80kg", LiftosaurApiClient.formatWeightKg(80f))
        assertEquals("100kg", LiftosaurApiClient.formatWeightKg(100f))
    }

    @Test
    fun formatsOneDecimal() {
        assertEquals("82.5kg", LiftosaurApiClient.formatWeightKg(82.5f))
    }

    @Test
    fun formatsTwoDecimals() {
        assertEquals("78.12kg", LiftosaurApiClient.formatWeightKg(78.12f))
    }

    @Test
    fun createdIsStored() {
        assertEquals(LiftosaurResult.Stored, LiftosaurApiClient.outcomeFor(201, null))
    }

    @Test
    fun conflictMeansAlreadyStored() {
        assertEquals(LiftosaurResult.AlreadyStored, LiftosaurApiClient.outcomeFor(409, null))
    }

    @Test
    fun unauthorizedAndForbiddenStopSending() {
        assertEquals(LiftosaurResult.Unauthorized, LiftosaurApiClient.outcomeFor(401, null))
        assertEquals(LiftosaurResult.Unauthorized, LiftosaurApiClient.outcomeFor(403, null))
    }

    @Test
    fun otherClientErrorsAreRejected() {
        assertTrue(LiftosaurApiClient.outcomeFor(422, null) is LiftosaurResult.Rejected)
    }

    @Test
    fun serverErrorsAreRetried() {
        assertTrue(LiftosaurApiClient.outcomeFor(500, null) is LiftosaurResult.Unavailable)
    }

    @Test
    fun noResponseIsRetried() {
        val result = LiftosaurApiClient.outcomeFor(null, "could not resolve host")
        assertTrue(result is LiftosaurResult.Unavailable)
        assertEquals("could not resolve host", (result as LiftosaurResult.Unavailable).reason)
    }
}
