/*  Copyright (C) 2026 ARC345

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
package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits
import nodomain.freeyourgadget.gadgetbridge.model.DistanceUnit
import nodomain.freeyourgadget.gadgetbridge.model.LengthUnit
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs

/**
 * The body measurement settings, with their defaults in code. All screens read them here, so no
 * preferences migrator is necessary.
 */
object BodyMeasurementPrefs {
    const val PREF_LENGTH_UNIT = "body_measurements_length_unit"
    const val PREF_ENABLED_TYPES = "body_measurements_enabled_types"

    fun lengthUnit(prefs: GBPrefs = GBApplication.getPrefs()): LengthUnit =
        LengthUnit.fromPrefValue(prefs.preferences.getString(PREF_LENGTH_UNIT, null))
            ?: if (prefs.distanceUnit == DistanceUnit.IMPERIAL) LengthUnit.INCH else LengthUnit.CENTIMETER

    fun enabledTypes(prefs: GBPrefs = GBApplication.getPrefs()): Set<BodyMeasurementType> {
        // Prefs.getStringSet returns the default for an empty set, but an empty set is a valid
        // choice here, so read the raw preferences.
        if (!prefs.contains(PREF_ENABLED_TYPES)) {
            return BodyMeasurementType.defaultEnabled()
        }
        val stored = prefs.preferences.getStringSet(PREF_ENABLED_TYPES, null) ?: return BodyMeasurementType.defaultEnabled()
        return stored.mapNotNullTo(LinkedHashSet()) { code -> code.toIntOrNull()?.let { BodyMeasurementType.fromCode(it) } }
    }

    fun units(prefs: GBPrefs = GBApplication.getPrefs()): BodyMeasurementUnits =
        BodyMeasurementUnits(lengthUnit(prefs), prefs.weightUnit)
}
