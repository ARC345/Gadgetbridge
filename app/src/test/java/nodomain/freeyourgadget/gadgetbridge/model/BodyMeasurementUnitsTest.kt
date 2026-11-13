package nodomain.freeyourgadget.gadgetbridge.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BodyMeasurementUnitsTest {
    @Test
    fun `type codes are unique and can be found again`() {
        val codes = BodyMeasurementType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        BodyMeasurementType.entries.forEach { assertEquals(it, BodyMeasurementType.fromCode(it.code)) }
        assertNull(BodyMeasurementType.fromCode(999))
    }

    @Test
    fun `default enabled types follow the spec`() {
        val defaults = BodyMeasurementType.defaultEnabled()
        assertTrue(BodyMeasurementType.WEIGHT in defaults)
        assertTrue(BodyMeasurementType.WAIST_NAVEL in defaults)
        assertTrue(BodyMeasurementType.UPPER_ARM_FLEXED_RIGHT in defaults)
        assertTrue(BodyMeasurementType.WAIST_NARROWEST !in defaults)
        assertTrue(BodyMeasurementType.BODY_FAT !in defaults)
        assertTrue(BodyMeasurementType.entries.filter { it.category == BodyMeasurementType.Category.SKINFOLD }.none { it in defaults })
    }

    @Test
    fun `inches and centimetres convert exactly`() {
        assertEquals(32.25, LengthUnit.INCH.fromCm(81.915), 1e-9)
        assertEquals(81.915, LengthUnit.INCH.toCm(32.25), 1e-9)
        assertEquals(81.915, LengthUnit.CENTIMETER.fromCm(81.915), 0.0)
        assertEquals(LengthUnit.INCH, LengthUnit.fromPrefValue("in"))
        assertNull(LengthUnit.fromPrefValue("feet"))
    }

    @Test
    fun `toKg is the inverse of convertWeight for every unit`() {
        for (unit in WeightUnit.entries) {
            assertEquals(unit.name, 80.0, WeightUnit.toKg(WeightUnit.convertWeight(80.0, unit), unit), 1e-9)
        }
        assertEquals(80.0, WeightUnit.toKg(176.37, WeightUnit.POUND), 0.001)
    }

    @Test
    fun `stone is typed as pounds`() {
        val units = BodyMeasurementUnits(LengthUnit.CENTIMETER, WeightUnit.STONE)
        assertEquals(WeightUnit.POUND, units.inputWeightUnit)
        assertEquals("176.37", units.formatInput(BodyMeasurementType.WEIGHT, 80.0, Locale.US))
        assertEquals(80.0, units.fromInput(BodyMeasurementType.WEIGHT, 176.36980974790205), 1e-9)
    }

    @Test
    fun `circumferences use the length unit and its decimals`() {
        val inches = BodyMeasurementUnits(LengthUnit.INCH, WeightUnit.KILOGRAM)
        assertEquals("32.25", inches.formatInput(BodyMeasurementType.WAIST_NAVEL, 81.915, Locale.US))
        assertEquals("32,25", inches.formatInput(BodyMeasurementType.WAIST_NAVEL, 81.915, Locale.GERMANY))
        assertEquals(81.915, inches.fromInput(BodyMeasurementType.WAIST_NAVEL, 32.25), 1e-9)
        val cm = BodyMeasurementUnits(LengthUnit.CENTIMETER, WeightUnit.KILOGRAM)
        assertEquals("81.9", cm.formatInput(BodyMeasurementType.WAIST_NAVEL, 81.915, Locale.US))
    }

    @Test
    fun `skinfolds and body fat do not convert`() {
        val units = BodyMeasurementUnits(LengthUnit.INCH, WeightUnit.POUND)
        assertEquals(12.5, units.fromInput(BodyMeasurementType.TRICEPS_SKINFOLD, 12.5), 0.0)
        assertEquals("12.5", units.formatInput(BodyMeasurementType.TRICEPS_SKINFOLD, 12.5, Locale.US))
        assertEquals("18.0", units.formatInput(BodyMeasurementType.BODY_FAT, 18.0, Locale.US))
    }
}
