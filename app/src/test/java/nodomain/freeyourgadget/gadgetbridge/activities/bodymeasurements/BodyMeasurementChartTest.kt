package nodomain.freeyourgadget.gadgetbridge.activities.bodymeasurements

import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMeasurementChartTest {
    @Test
    fun `no points give an empty spec`() {
        val spec = BodyMeasurementChart.spec(listOf(BodyMeasurementChart.line("a", "A", emptyList(), 0)), 0.0, 10.0)
        assertTrue(spec.isEmpty)
    }

    @Test
    fun `the axes cover the period and the values with a margin`() {
        val points = listOf(ChartPoint(100.0, 30.0), ChartPoint(200.0, 32.0))
        val spec = BodyMeasurementChart.spec(listOf(BodyMeasurementChart.line("a", "A", points, 0)), 50.0, 300.0)
        assertEquals(ChartValueFormat.DATE, spec.xAxis.format)
        assertEquals(50.0, spec.xAxis.minimum!!, 0.0)
        assertEquals(300.0, spec.xAxis.maximum!!, 0.0)
        assertEquals(29.8, spec.yAxis.minimum!!, 1e-9)
        assertEquals(32.2, spec.yAxis.maximum!!, 1e-9)
    }

    @Test
    fun `a single value still gets a visible range`() {
        val spec = BodyMeasurementChart.spec(listOf(BodyMeasurementChart.line("a", "A", listOf(ChartPoint(1.0, 0.5)), 0)), 0.0, 2.0)
        assertTrue(spec.yAxis.minimum!! < 0.5)
        assertTrue(spec.yAxis.maximum!! > 0.5)
    }
}
