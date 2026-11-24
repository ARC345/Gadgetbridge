package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits
import nodomain.freeyourgadget.gadgetbridge.model.LengthUnit
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class BodyMeasurementFormTest {
    private val inches = BodyMeasurementUnits(LengthUnit.INCH, WeightUnit.KILOGRAM)
    private val cm = BodyMeasurementUnits(LengthUnit.CENTIMETER, WeightUnit.KILOGRAM)
    private val waist = BodyMeasurementType.WAIST_NAVEL
    private val hips = BodyMeasurementType.HIPS

    @Test
    fun `parses the digits and the decimal sign of other scripts`() {
        assertEquals(32.25, BodyMeasurementForm.parse("\u0663\u0662\u066B\u0662\u0665")!!, 0.0)
        assertEquals(32.25, BodyMeasurementForm.parse("\u06F3\u06F2.\u06F2\u06F5")!!, 0.0)
        assertEquals(32.25, BodyMeasurementForm.parse("32\u066B25")!!, 0.0)
    }

    @Test
    fun `parses a comma or a full stop`() {
        assertEquals(32.25, BodyMeasurementForm.parse(" 32,25 ")!!, 0.0)
        assertEquals(32.25, BodyMeasurementForm.parse("32.25")!!, 0.0)
        assertNull(BodyMeasurementForm.parse("abc"))
    }

    @Test
    fun `rejects text that is not a finite number`() {
        for (text in listOf("NaN", "Infinity", "-Infinity", "1e400")) {
            val field = FieldState(waist.code, null, "", text)
            assertEquals(text, FieldOutcome.Invalid(R.string.body_measurements_error_number), BodyMeasurementForm.outcome(field, inches))
        }
    }

    @Test
    fun `a new field starts empty and an existing field shows its stored value`() {
        assertEquals(FieldState(waist.code, null, "", ""), BodyMeasurementForm.newField(waist, null, inches, Locale.US))
        assertEquals(FieldState(waist.code, 81.915f, "32.25", "32.25"), BodyMeasurementForm.newField(waist, 81.915f, inches, Locale.US))
    }

    @Test
    fun `an unchanged field keeps its exact stored value after a unit change`() {
        // Saved in cm as 81.9; now shown in inches. Saving without a change must not touch it.
        val field = BodyMeasurementForm.newField(waist, 81.9f, inches, Locale.US)
        assertEquals(FieldOutcome.Unchanged, BodyMeasurementForm.outcome(field, inches))
        val result = BodyMeasurementForm.validate(listOf(field), setOf(waist.code), "", inches)
        assertEquals(BodyMeasurementForm.Validation.Ok(emptyMap(), emptySet()), result)
    }

    @Test
    fun `a changed field converts from the display unit`() {
        val field = FieldState(waist.code, null, "", "32.25")
        val outcome = BodyMeasurementForm.outcome(field, inches) as FieldOutcome.Changed
        assertEquals(81.915f, outcome.value, 1e-4f)
    }

    @Test
    fun `emptying a stored field clears it, emptying a new field does nothing`() {
        assertEquals(FieldOutcome.Cleared, BodyMeasurementForm.outcome(FieldState(waist.code, 80f, "80.0", " "), cm))
        assertEquals(FieldOutcome.Unchanged, BodyMeasurementForm.outcome(FieldState(waist.code, null, "", " "), cm))
    }

    @Test
    fun `values must be more than 0 and body fat not more than 100`() {
        assertEquals(FieldOutcome.Invalid(R.string.body_measurements_error_positive), BodyMeasurementForm.outcome(FieldState(waist.code, null, "", "0"), cm))
        assertEquals(FieldOutcome.Invalid(R.string.body_measurements_error_positive), BodyMeasurementForm.outcome(FieldState(waist.code, null, "", "-3"), cm))
        val fat = BodyMeasurementType.BODY_FAT.code
        assertEquals(FieldOutcome.Invalid(R.string.body_measurements_error_body_fat), BodyMeasurementForm.outcome(FieldState(fat, null, "", "100.5"), cm))
        assertEquals(FieldOutcome.Changed(100f), BodyMeasurementForm.outcome(FieldState(fat, null, "", "100"), cm))
    }

    @Test
    fun `validation collects upserts, cleared types and errors`() {
        val fields = listOf(
            FieldState(waist.code, 80f, "80.0", "81.0"),
            FieldState(hips.code, 98f, "98.0", ""),
        )
        assertEquals(
            BodyMeasurementForm.Validation.Ok(mapOf(waist.code to 81f), setOf(hips.code)),
            BodyMeasurementForm.validate(fields, setOf(waist.code, hips.code), "", cm),
        )
        val bad = listOf(FieldState(waist.code, null, "", "x"))
        assertEquals(
            BodyMeasurementForm.Validation.FieldErrors(mapOf(waist.code to R.string.body_measurements_error_number)),
            BodyMeasurementForm.validate(bad, emptySet(), "", cm),
        )
    }

    @Test
    fun `clearing all visible fields is permitted when hidden values remain`() {
        val fields = listOf(FieldState(waist.code, 80f, "80.0", ""))
        val thighCode = BodyMeasurementType.THIGH_LEFT.code
        assertEquals(
            BodyMeasurementForm.Validation.Ok(emptyMap(), setOf(waist.code)),
            BodyMeasurementForm.validate(fields, setOf(waist.code, thighCode), "", cm),
        )
    }

    @Test
    fun `an entry with no values and no note is refused`() {
        val fields = listOf(FieldState(waist.code, 80f, "80.0", ""))
        assertEquals(BodyMeasurementForm.Validation.Empty, BodyMeasurementForm.validate(fields, setOf(waist.code), "  ", cm))
        assertEquals(BodyMeasurementForm.Validation.Empty, BodyMeasurementForm.validate(emptyList(), emptySet(), "", cm))
        assertTrue(BodyMeasurementForm.validate(emptyList(), emptySet(), "only a note", cm) is BodyMeasurementForm.Validation.Ok)
    }

    @Test
    fun `current values use valid typed text, else the stored values`() {
        val fields = listOf(
            FieldState(waist.code, 80f, "80.0", "82.0"),
            FieldState(hips.code, 98f, "98.0", "abc"),
        )
        val stored = mapOf(waist.code to 80f, hips.code to 98f, BodyMeasurementType.NECK.code to 40f, 999 to 1f)
        assertEquals(
            mapOf(waist to 82.0, BodyMeasurementType.NECK to 40.0),
            BodyMeasurementForm.currentValues(fields, stored, cm),
        )
    }

    @Test
    fun `joins a local date and time in the zone`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val millis = BodyMeasurementForm.timestampOf(LocalDate.of(2026, 11, 4), LocalTime.of(23, 30), zone)
        assertEquals(ZonedDateTime.of(2026, 11, 4, 23, 30, 0, 0, zone).toInstant().toEpochMilli(), millis)
    }

    @Test
    fun `a time in the daylight saving gap moves forward`() {
        val zone = ZoneId.of("Europe/Berlin")
        val millis = BodyMeasurementForm.timestampOf(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), zone)
        assertEquals(ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, zone).toInstant().toEpochMilli(), millis)
    }
}
