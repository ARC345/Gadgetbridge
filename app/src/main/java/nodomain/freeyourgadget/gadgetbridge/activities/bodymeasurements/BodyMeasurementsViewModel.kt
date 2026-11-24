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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartPoint
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurement
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurementRepository
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits
import org.slf4j.LoggerFactory

/** The chosen series and period are kept in [SavedStateHandle], so they survive process death. */
class BodyMeasurementsViewModel(private val savedState: SavedStateHandle) : ViewModel() {
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

    private val _errors = Channel<Unit>(Channel.BUFFERED)

    /** One event for each database failure that the activity shows to the user. */
    val errors: Flow<Unit> = _errors.receiveAsFlow()

    private var selected: BodySeries? = savedState.get<String>(KEY_SERIES)?.let(::seriesOf)
    private var period = savedState.get<String>(KEY_PERIOD)?.let { name -> ChartPeriod.entries.firstOrNull { it.name == name } }
        ?: ChartPeriod.THREE_MONTHS
    private var loadJob: Job? = null

    fun select(series: BodySeries) {
        if (series == selected) {
            return
        }
        selected = series
        savedState[KEY_SERIES] = keyOf(series)
        refresh()
    }

    fun setPeriod(value: ChartPeriod) {
        if (value == period) {
            return
        }
        period = value
        savedState[KEY_PERIOD] = value.name
        refresh()
    }

    /** Deletes the entry in [viewModelScope], so a recreation of the activity does not skip the refresh. */
    fun delete(entryId: Long) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    GBApplication.acquireDB().use { db -> BodyMeasurementRepository(db.daoSession).delete(entryId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Could not delete the body measurement entry", e)
                _errors.trySend(Unit)
            }
            refresh()
        }
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
            val loaded = try {
                withContext(Dispatchers.IO) { load(nowMillis, units, enabled, gender, wanted, currentPeriod) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Could not load the body measurements", e)
                _errors.trySend(Unit)
                return@launch
            }
            selected = loaded.selected
            savedState[KEY_SERIES] = keyOf(loaded.selected)
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

    private companion object {
        private val LOG = LoggerFactory.getLogger(BodyMeasurementsViewModel::class.java)
        const val KEY_SERIES = "body_measurements_series"
        const val KEY_PERIOD = "body_measurements_period"

        fun keyOf(series: BodySeries): String = when (series) {
            is BodySeries.Measured -> "type:${series.type.code}"
            is BodySeries.Derived -> "derived:${series.metric.name}"
        }

        fun seriesOf(key: String): BodySeries? = when {
            key.startsWith("type:") -> key.removePrefix("type:").toIntOrNull()?.let(BodyMeasurementType::fromCode)?.let { BodySeries.Measured(it) }
            key.startsWith("derived:") -> DerivedMetric.entries.firstOrNull { it.name == key.removePrefix("derived:") }?.let { BodySeries.Derived(it) }
            else -> null
        }
    }
}
