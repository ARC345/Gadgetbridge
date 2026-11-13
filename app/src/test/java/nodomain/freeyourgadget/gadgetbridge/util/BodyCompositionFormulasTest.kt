package nodomain.freeyourgadget.gadgetbridge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Synthetic inputs; the expected values were computed from the published equations.
class BodyCompositionFormulasTest {
    @Test
    fun `bmi from weight and height`() {
        assertEquals(24.69, BodyCompositionEstimates.bmi(80.0, 180.0)!!, 0.01)
        assertNull(BodyCompositionEstimates.bmi(0.0, 180.0))
        assertNull(BodyCompositionEstimates.bmi(80.0, 0.0))
    }

    @Test
    fun `navy body fat for men uses waist and neck`() {
        assertEquals(18.47, BodyCompositionEstimates.navyBodyFat(true, 180.0, 40.0, 90.0, null)!!, 0.01)
    }

    @Test
    fun `navy body fat for women uses waist, hips and neck`() {
        assertEquals(29.74, BodyCompositionEstimates.navyBodyFat(false, 165.0, 33.0, 75.0, 100.0)!!, 0.01)
        assertNull(BodyCompositionEstimates.navyBodyFat(false, 165.0, 33.0, 75.0, null))
    }

    @Test
    fun `navy body fat has no result without a usable input`() {
        assertNull(BodyCompositionEstimates.navyBodyFat(true, 180.0, 40.0, 40.0, null))
        assertNull(BodyCompositionEstimates.navyBodyFat(true, 180.0, null, 90.0, null))
        assertNull(BodyCompositionEstimates.navyBodyFat(true, 0.0, 40.0, 90.0, null))
    }

    @Test
    fun `three-site skinfold body fat for men and women`() {
        assertEquals(17.95, BodyCompositionEstimates.skinfoldBodyFat(true, 30, 60.0)!!, 0.01)
        assertEquals(24.13, BodyCompositionEstimates.skinfoldBodyFat(false, 30, 60.0)!!, 0.01)
        assertNull(BodyCompositionEstimates.skinfoldBodyFat(true, 0, 60.0))
        assertNull(BodyCompositionEstimates.skinfoldBodyFat(true, 30, 0.0))
    }

    @Test
    fun `a very small skinfold sum gives an implausible result`() {
        val result = BodyCompositionEstimates.skinfoldBodyFat(true, 20, 3.0)!!
        assertEquals(-0.72, result, 0.01)
        assertFalse(BodyCompositionEstimates.isPlausibleBodyFat(result))
    }

    @Test
    fun `ratios`() {
        assertEquals(0.9, BodyCompositionEstimates.waistToHip(90.0, 100.0)!!, 1e-9)
        assertEquals(0.5, BodyCompositionEstimates.waistToHeight(90.0, 180.0)!!, 1e-9)
        assertNull(BodyCompositionEstimates.waistToHip(90.0, null))
        assertNull(BodyCompositionEstimates.waistToHeight(null, 180.0))
    }

    @Test
    fun `plausible body fat is more than 0 and less than 100`() {
        assertFalse(BodyCompositionEstimates.isPlausibleBodyFat(0.0))
        assertFalse(BodyCompositionEstimates.isPlausibleBodyFat(100.0))
        assertTrue(BodyCompositionEstimates.isPlausibleBodyFat(18.5))
    }
}
