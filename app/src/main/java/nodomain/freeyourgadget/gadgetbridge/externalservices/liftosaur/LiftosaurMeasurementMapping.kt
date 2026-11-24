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

/**
 * Maps Gadgetbridge body measurement types to Liftosaur measurement keys and value units.
 *
 * Types that Liftosaur has no key for are not sent. Those are the narrowest waist, the flexed arms
 * and the skinfolds.
 */
internal object LiftosaurMeasurementMapping {

    /** The Liftosaur key for [type], or null when Liftosaur has no key for it. */
    fun keyFor(type: BodyMeasurementType): String? = when (type) {
        BodyMeasurementType.WEIGHT -> "weight"
        BodyMeasurementType.BODY_FAT -> "bodyfat"
        BodyMeasurementType.NECK -> "neck"
        BodyMeasurementType.SHOULDERS -> "shoulders"
        BodyMeasurementType.CHEST -> "chest"
        // Liftosaur has one waist key. The navel measurement is the usual one.
        BodyMeasurementType.WAIST_NAVEL -> "waist"
        BodyMeasurementType.HIPS -> "hips"
        // Liftosaur has one key per arm, so the relaxed arm is used.
        BodyMeasurementType.UPPER_ARM_RELAXED_LEFT -> "bicepLeft"
        BodyMeasurementType.UPPER_ARM_RELAXED_RIGHT -> "bicepRight"
        BodyMeasurementType.FOREARM_LEFT -> "forearmLeft"
        BodyMeasurementType.FOREARM_RIGHT -> "forearmRight"
        BodyMeasurementType.THIGH_LEFT -> "thighLeft"
        BodyMeasurementType.THIGH_RIGHT -> "thighRight"
        BodyMeasurementType.CALF_LEFT -> "calfLeft"
        BodyMeasurementType.CALF_RIGHT -> "calfRight"
        else -> null
    }

    /**
     * The value text for [type] and its stored [value], with the unit suffix Liftosaur expects, such
     * as `82.5kg`, `18%` or `37cm`. Null when the type has no Liftosaur unit.
     */
    fun formatValue(type: BodyMeasurementType, value: Float): String? {
        val unit = when (type.category) {
            BodyMeasurementType.Category.WEIGHT -> "kg"
            BodyMeasurementType.Category.BODY_FAT -> "%"
            BodyMeasurementType.Category.CIRCUMFERENCE -> "cm"
            BodyMeasurementType.Category.SKINFOLD -> null
        } ?: return null
        return LiftosaurApiClient.formatValue(value, unit)
    }
}
