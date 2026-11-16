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

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceFragmentCompat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.LengthUnit

class BodyMeasurementsSettingsActivity : AbstractSettingsActivityV2() {
    override fun newFragment(): PreferenceFragmentCompat = BodyMeasurementsSettingsFragment()

    class BodyMeasurementsSettingsFragment : AbstractPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.body_measurements_settings, rootKey)
            val prefs = GBApplication.getPrefs()

            findPreference<ListPreference>(BodyMeasurementPrefs.PREF_LENGTH_UNIT)?.let { unit ->
                unit.entries = arrayOf(
                    getString(R.string.body_measurements_unit_centimetres),
                    getString(R.string.body_measurements_unit_inches),
                )
                unit.entryValues = arrayOf(LengthUnit.CENTIMETER.prefValue, LengthUnit.INCH.prefValue)
                // A list with no stored value shows nothing selected: store the default now.
                if (!prefs.contains(BodyMeasurementPrefs.PREF_LENGTH_UNIT)) {
                    unit.value = BodyMeasurementPrefs.lengthUnit(prefs).prefValue
                }
            }

            findPreference<MultiSelectListPreference>(BodyMeasurementPrefs.PREF_ENABLED_TYPES)?.let { types ->
                types.entries = BodyMeasurementType.entries.map { getString(it.labelRes) }.toTypedArray()
                types.entryValues = BodyMeasurementType.entries.map { it.code.toString() }.toTypedArray()
                if (!prefs.contains(BodyMeasurementPrefs.PREF_ENABLED_TYPES)) {
                    types.values = BodyMeasurementPrefs.enabledTypes(prefs).map { it.code.toString() }.toSet()
                }
            }
        }
    }
}
