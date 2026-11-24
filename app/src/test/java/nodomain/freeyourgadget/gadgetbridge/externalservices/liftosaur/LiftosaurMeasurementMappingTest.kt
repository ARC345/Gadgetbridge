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

import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiftosaurMeasurementMappingTest {

    @Test
    fun mapsWeightAndBodyFat() {
        assertEquals("weight", LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.WEIGHT))
        assertEquals("bodyfat", LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.BODY_FAT))
    }

    @Test
    fun mapsCircumferencesToLiftosaurKeys() {
        assertEquals("chest", LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.CHEST))
        assertEquals("waist", LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.WAIST_NAVEL))
        assertEquals("bicepLeft", LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.UPPER_ARM_RELAXED_LEFT))
        assertEquals("calfRight", LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.CALF_RIGHT))
    }

    @Test
    fun skipsTypesWithoutLiftosaurKey() {
        assertNull(LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.WAIST_NARROWEST))
        assertNull(LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.UPPER_ARM_FLEXED_LEFT))
        assertNull(LiftosaurMeasurementMapping.keyFor(BodyMeasurementType.CHEST_SKINFOLD))
    }

    @Test
    fun formatsValuesWithUnitSuffix() {
        assertEquals("82.5kg", LiftosaurMeasurementMapping.formatValue(BodyMeasurementType.WEIGHT, 82.5f))
        assertEquals("18.5%", LiftosaurMeasurementMapping.formatValue(BodyMeasurementType.BODY_FAT, 18.5f))
        assertEquals("37cm", LiftosaurMeasurementMapping.formatValue(BodyMeasurementType.CHEST, 37f))
    }

    @Test
    fun skinfoldsHaveNoLiftosaurUnit() {
        assertNull(LiftosaurMeasurementMapping.formatValue(BodyMeasurementType.CHEST_SKINFOLD, 8f))
    }
}
