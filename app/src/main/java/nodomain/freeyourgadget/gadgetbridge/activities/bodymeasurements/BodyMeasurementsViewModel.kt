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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurement
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurementRepository
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits

class BodyMeasurementsViewModel : ViewModel() {
    /** [scale] has points only for the weight series. */
    data class ChartData(val main: List<ChartPoint>, val scale: List<ChartPoint>, val fromSec: Double, val toSec: Double)

    data class ScreenState(
        val entries: List<BodyMeasurement>,
        val options: List<BodySeries>,
        val selected: BodySeries,
        val period: ChartPeriod,
        val chart: ChartData,
        val units: BodyMeasurementUnits,
        val enabled: Set<BodyMeasurementType>,
    )

    private val _state = MutableStateFlow<ScreenState?>(null)
    val state: StateFlow<ScreenState?> = _state.asStateFlow()

    private var selected: BodySeries? = null
    private var period = ChartPeriod.THREE_MONTHS
    private var loadJob: Job? = null

    fun select(series: BodySeries) {
        if (series == selected) {
            return
        }
        selected = series
        refresh()
    }

    fun setPeriod(value: ChartPeriod) {
        if (value == period) {
            return
        }
        period = value
        refresh()
    }

    fun refresh() {
        val nowMillis = System.currentTimeMillis()
        val units = BodyMeasurementPrefs.units()
        val enabled = BodyMeasurementPrefs.enabledTypes()
        val gender = ActivityUser().gender
        val wanted = selected
        val currentPeriod = period
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { load(nowMillis, units, enabled, gender, wanted, currentPeriod) }
            selected = loaded.selected
            _state.value = loaded
        }
    }

    private fun load(
        nowMillis: Long,
        units: BodyMeasurementUnits,
        enabled: Set<BodyMeasurementType>,
        gender: Int,
        wanted: BodySeries?,
        currentPeriod: ChartPeriod,
    ): ScreenState = GBApplication.acquireDB().use { db ->
        val session = db.daoSession
        val repo = BodyMeasurementRepository(session)
        val options: List<BodySeries> =
            BodyMeasurementType.entries.filter { it in enabled }.map { BodySeries.Measured(it) } +
                DerivedMetric.entries.filter { it.showsInChart(gender, enabled) }.map { BodySeries.Derived(it) }
        val current = wanted?.takeIf { it in options } ?: options.firstOrNull() ?: BodySeries.Measured(BodyMeasurementType.WEIGHT)
        val fromMillis = currentPeriod.days?.let { nowMillis - it * 86_400_000L } ?: 0L
        val all = repo.entries(0L, Long.MAX_VALUE)
        val inPeriod = all.filter { it.timestamp in fromMillis..nowMillis }.sortedBy { it.timestamp }

        var scale = emptyList<ChartPoint>()
        val main: List<ChartPoint> = when (current) {
            is BodySeries.Measured -> if (current.type == BodyMeasurementType.WEIGHT) {
                val weights = repo.weightSeries(fromMillis, nowMillis)
                scale = weights.filter { it.fromScale }.map { ChartPoint(it.timestamp / 1000.0, units.toChart(current.type, it.weightKg.toDouble())) }
                weights.filterNot { it.fromScale }.map { ChartPoint(it.timestamp / 1000.0, units.toChart(current.type, it.weightKg.toDouble())) }
            } else {
                inPeriod.mapNotNull { entry ->
                    entry.values[current.type.code]?.let { ChartPoint(entry.timestamp / 1000.0, units.toChart(current.type, it.toDouble())) }
                }
            }
            is BodySeries.Derived -> inPeriod.mapNotNull { entry ->
                val profile = BodyMeasurementCalculator.profileAt(session, entry.timestamp)
                val scaleKg = if (current.metric == DerivedMetric.BMI && entry.values[BodyMeasurementType.WEIGHT.code] == null) {
                    repo.scaleWeightOnDay(entry.timestamp)?.toDouble()
                } else {
                    null
                }
                val result = BodyMeasurementCalculator.calculate(current.metric, BodyMeasurementCalculator.typedValues(entry.values), profile, scaleKg)
                (result as? DerivedResult.Value)?.let { ChartPoint(entry.timestamp / 1000.0, it.value) }
            }
        }
        val nowSec = nowMillis / 1000.0
        val fromSec = if (currentPeriod.days == null) (main + scale).minOfOrNull { it.x } ?: nowSec else fromMillis / 1000.0
        ScreenState(all, options, current, currentPeriod, ChartData(main, scale, fromSec, nowSec), units, enabled)
    }
}
