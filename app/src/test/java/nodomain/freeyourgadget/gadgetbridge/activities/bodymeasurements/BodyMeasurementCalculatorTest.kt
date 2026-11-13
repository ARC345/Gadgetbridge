package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.ABDOMEN_SKINFOLD
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.CHEST_SKINFOLD
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.HIPS
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.NECK
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.THIGH_SKINFOLD
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.WAIST_NARROWEST
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.WAIST_NAVEL
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType.WEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BodyMeasurementCalculatorTest {
    private val man = BodyProfile(ActivityUser.GENDER_MALE, 30, 180)
    private val woman = BodyProfile(ActivityUser.GENDER_FEMALE, 30, 165)
    private val other = BodyProfile(ActivityUser.GENDER_OTHER, 30, 170)

    private fun value(result: DerivedResult): Double = (result as DerivedResult.Value).value

    @Test
    fun `bmi uses the typed weight first, then the scale weight`() {
        val typed = BodyMeasurementCalculator.calculate(DerivedMetric.BMI, mapOf(WEIGHT to 80.0), man, 90.0)
        assertEquals(24.69, value(typed), 0.01)
        assertEquals(R.string.body_measurements_weight_typed, (typed as DerivedResult.Value).sourceRes)
        val scale = BodyMeasurementCalculator.calculate(DerivedMetric.BMI, emptyMap(), man, 80.0)
        assertEquals(R.string.body_measurements_weight_scale, (scale as DerivedResult.Value).sourceRes)
        assertEquals(DerivedResult.Missing(WEIGHT), BodyMeasurementCalculator.calculate(DerivedMetric.BMI, emptyMap(), man, null))
    }

    @Test
    fun `navy for men uses the waist at the navel only`() {
        val result = BodyMeasurementCalculator.calculate(DerivedMetric.NAVY_BODY_FAT, mapOf(NECK to 40.0, WAIST_NAVEL to 90.0), man, null)
        assertEquals(18.47, value(result), 0.01)
        assertEquals(R.string.body_measurements_formula_male, (result as DerivedResult.Value).sourceRes)
        // No fallback to the narrowest waist.
        assertEquals(
            DerivedResult.Missing(WAIST_NAVEL),
            BodyMeasurementCalculator.calculate(DerivedMetric.NAVY_BODY_FAT, mapOf(NECK to 40.0, WAIST_NARROWEST to 85.0), man, null),
        )
    }

    @Test
    fun `navy for women uses the narrowest waist and the hips`() {
        val values = mapOf(NECK to 33.0, WAIST_NARROWEST to 75.0, HIPS to 100.0)
        assertEquals(29.74, value(BodyMeasurementCalculator.calculate(DerivedMetric.NAVY_BODY_FAT, values, woman, null)), 0.01)
        assertEquals(
            DerivedResult.Missing(WAIST_NARROWEST),
            BodyMeasurementCalculator.calculate(DerivedMetric.NAVY_BODY_FAT, mapOf(NECK to 33.0, WAIST_NAVEL to 75.0, HIPS to 100.0), woman, null),
        )
    }

    @Test
    fun `body fat formulas need male or female`() {
        assertEquals(DerivedResult.NeedsSex, BodyMeasurementCalculator.calculate(DerivedMetric.NAVY_BODY_FAT, mapOf(NECK to 40.0, WAIST_NAVEL to 90.0), other, null))
        assertEquals(DerivedResult.NeedsSex, BodyMeasurementCalculator.calculate(DerivedMetric.SKINFOLD_BODY_FAT, emptyMap(), other, null))
    }

    @Test
    fun `skinfold for men sums chest, abdomen and thigh`() {
        val values = mapOf(CHEST_SKINFOLD to 20.0, ABDOMEN_SKINFOLD to 25.0, THIGH_SKINFOLD to 15.0)
        assertEquals(17.95, value(BodyMeasurementCalculator.calculate(DerivedMetric.SKINFOLD_BODY_FAT, values, man, null)), 0.01)
    }

    @Test
    fun `an implausible body fat result is out of range`() {
        val values = mapOf(CHEST_SKINFOLD to 1.0, ABDOMEN_SKINFOLD to 1.0, THIGH_SKINFOLD to 1.0)
        val young = man.copy(ageYears = 20)
        assertEquals(DerivedResult.OutOfRange, BodyMeasurementCalculator.calculate(DerivedMetric.SKINFOLD_BODY_FAT, values, young, null))
        // Waist not more than neck: the log argument is not positive.
        assertEquals(DerivedResult.OutOfRange, BodyMeasurementCalculator.calculate(DerivedMetric.NAVY_BODY_FAT, mapOf(NECK to 40.0, WAIST_NAVEL to 40.0), man, null))
    }

    @Test
    fun `ratios use the waist at the navel`() {
        val values = mapOf(WAIST_NAVEL to 90.0, HIPS to 100.0)
        assertEquals(0.9, value(BodyMeasurementCalculator.calculate(DerivedMetric.WAIST_TO_HIP, values, man, null)), 1e-9)
        assertEquals(0.5, value(BodyMeasurementCalculator.calculate(DerivedMetric.WAIST_TO_HEIGHT, values, man, null)), 1e-9)
        assertEquals(DerivedResult.Missing(HIPS), BodyMeasurementCalculator.calculate(DerivedMetric.WAIST_TO_HIP, mapOf(WAIST_NAVEL to 90.0), man, null))
    }

    @Test
    fun `visibility follows the enabled inputs and the sex`() {
        val defaults = BodyMeasurementType.defaultEnabled()
        assertFalse(DerivedMetric.SKINFOLD_BODY_FAT.showsInBox(ActivityUser.GENDER_MALE, defaults))
        assertTrue(DerivedMetric.NAVY_BODY_FAT.showsInChart(ActivityUser.GENDER_MALE, defaults))
        // The women's Navy formula needs the narrowest waist, which is off by default.
        assertFalse(DerivedMetric.NAVY_BODY_FAT.showsInBox(ActivityUser.GENDER_FEMALE, defaults))
        assertTrue(DerivedMetric.NAVY_BODY_FAT.showsInBox(ActivityUser.GENDER_OTHER, defaults))
        assertFalse(DerivedMetric.NAVY_BODY_FAT.showsInChart(ActivityUser.GENDER_OTHER, defaults))
    }

    @Test
    fun `typed values ignore unknown codes`() {
        assertEquals(mapOf(WAIST_NAVEL to 81.5), BodyMeasurementCalculator.typedValues(mapOf(WAIST_NAVEL.code to 81.5f, 999 to 1f)))
    }

    @Test
    fun `format uses the precision of the metric`() {
        assertEquals("24.7", DerivedMetric.BMI.format(24.691, Locale.US))
        assertEquals("18.5 %", DerivedMetric.NAVY_BODY_FAT.format(18.466, Locale.US))
        assertEquals("0.90", DerivedMetric.WAIST_TO_HIP.format(0.9, Locale.US))
    }
}
