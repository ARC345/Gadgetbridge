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

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.AxisSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSpec
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartValueFormat
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.LineCurve
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.SeriesStyle
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import kotlin.math.abs
import kotlin.math.max

enum class ChartPeriod(@StringRes val labelRes: Int, val days: Long?) {
    THREE_MONTHS(R.string.body_measurements_period_3_months, 90),
    ONE_YEAR(R.string.body_measurements_period_1_year, 365),
    ALL(R.string.body_measurements_period_all, null),
}

/** What the chart shows: one measured type or one calculated value. */
sealed interface BodySeries {
    data class Measured(val type: BodyMeasurementType) : BodySeries

    data class Derived(val metric: DerivedMetric) : BodySeries
}

object BodyMeasurementChart {
    /** X values are epoch seconds, as the other charts of the app use them. */
    fun spec(series: List<ChartSeries>, fromSec: Double, toSec: Double): ChartSpec {
        val values = series.flatMap { s -> s.points.map { it.y } }
        if (values.isEmpty()) {
            return ChartSpec.EMPTY
        }
        val low = values.min()
        val high = values.max()
        val margin = if (high > low) (high - low) * 0.1 else max(abs(high) * 0.05, 0.01)
        return ChartSpec(
            series = series,
            xAxis = AxisSpec(format = ChartValueFormat.DATE, minimum = fromSec, maximum = toSec),
            yAxis = AxisSpec(format = ChartValueFormat.DECIMAL, minimum = low - margin, maximum = high + margin),
        )
    }

    fun line(key: String, label: String, points: List<ChartPoint>, color: Int): ChartSeries =
        ChartSeries(key, label, points, SeriesStyle.Line(color, curve = LineCurve.STRAIGHT, showPoints = true))
}
