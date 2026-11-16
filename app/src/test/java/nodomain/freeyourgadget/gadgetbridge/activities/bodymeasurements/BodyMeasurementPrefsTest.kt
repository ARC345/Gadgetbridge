package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.LengthUnit
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyMeasurementPrefsTest : TestBase() {
    private val prefs get() = GBApplication.getPrefs()

    @After
    fun clearPrefs() {
        prefs.preferences.edit {
            remove(BodyMeasurementPrefs.PREF_LENGTH_UNIT)
            remove(BodyMeasurementPrefs.PREF_ENABLED_TYPES)
            remove(SettingsActivity.PREF_UNIT_DISTANCE)
        }
    }

    @Test
    fun `the default length unit follows the distance unit`() {
        prefs.preferences.edit { putString(SettingsActivity.PREF_UNIT_DISTANCE, "metric") }
        assertEquals(LengthUnit.CENTIMETER, BodyMeasurementPrefs.lengthUnit(prefs))
        prefs.preferences.edit { putString(SettingsActivity.PREF_UNIT_DISTANCE, "imperial") }
        assertEquals(LengthUnit.INCH, BodyMeasurementPrefs.lengthUnit(prefs))
    }

    @Test
    fun `a stored length unit wins over the distance unit`() {
        prefs.preferences.edit {
            putString(SettingsActivity.PREF_UNIT_DISTANCE, "metric")
            putString(BodyMeasurementPrefs.PREF_LENGTH_UNIT, "in")
        }
        assertEquals(LengthUnit.INCH, BodyMeasurementPrefs.lengthUnit(prefs))
    }

    @Test
    fun `enabled types default to the spec defaults`() {
        assertEquals(BodyMeasurementType.defaultEnabled(), BodyMeasurementPrefs.enabledTypes(prefs))
    }

    @Test
    fun `an empty stored set means nothing is enabled`() {
        prefs.preferences.edit { putStringSet(BodyMeasurementPrefs.PREF_ENABLED_TYPES, emptySet()) }
        assertEquals(emptySet<BodyMeasurementType>(), BodyMeasurementPrefs.enabledTypes(prefs))
    }

    @Test
    fun `unknown and broken codes in the stored set are ignored`() {
        prefs.preferences.edit { putStringSet(BodyMeasurementPrefs.PREF_ENABLED_TYPES, setOf("104", "999", "abc")) }
        assertEquals(setOf(BodyMeasurementType.WAIST_NAVEL), BodyMeasurementPrefs.enabledTypes(prefs))
    }
}
