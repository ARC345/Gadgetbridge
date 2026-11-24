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

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.activities.charts.spec.ChartSeries
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurement
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityBodyMeasurementsBinding
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.util.GB

class BodyMeasurementsActivity : AbstractGBActivity() {
    private val viewModel: BodyMeasurementsViewModel by viewModels()
    private lateinit var binding: ActivityBodyMeasurementsBinding
    private lateinit var adapter: BodyMeasurementsAdapter
    private var renderedOptions: List<BodySeries> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBodyMeasurementsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BodyMeasurementsAdapter(onClick = { openEditor(it.id) }, onLongClick = ::confirmDelete)
        binding.bodyMeasurementsList.layoutManager = LinearLayoutManager(this)
        binding.bodyMeasurementsList.adapter = adapter
        binding.bodyMeasurementsChart.zoomable = true
        binding.bodyMeasurementsChart.dismissSelectionOnTapOutside(binding.root)
        binding.bodyMeasurementsAdd.setOnClickListener { openEditor(null) }
        buildPeriodChips()

        lifecycleScope.launch {
            viewModel.state.collect { state -> state?.let(::render) }
        }
        lifecycleScope.launch {
            viewModel.errors.collect {
                GB.toast(this@BodyMeasurementsActivity, R.string.body_measurements_error_database, Toast.LENGTH_SHORT, GB.ERROR)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Also picks up changed settings and new entries from the editor.
        viewModel.refresh()
    }

    private fun buildPeriodChips() {
        val group = binding.bodyMeasurementsPeriodGroup
        for (period in ChartPeriod.entries) {
            val chip = newChip(group, getString(period.labelRes), period)
            chip.isChecked = period == ChartPeriod.THREE_MONTHS
        }
        group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            val period = checkedIds.firstOrNull()?.let { chipGroup.findViewById<Chip>(it).tag as ChartPeriod } ?: return@setOnCheckedStateChangeListener
            viewModel.setPeriod(period)
        }
    }

    private fun newChip(group: ChipGroup, text: String, tag: Any): Chip {
        val chip = LayoutInflater.from(this).inflate(R.layout.layout_chart_chip, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = text
        chip.tag = tag
        group.addView(chip)
        return chip
    }

    private fun label(series: BodySeries): String = when (series) {
        is BodySeries.Measured -> getString(series.type.labelRes)
        is BodySeries.Derived -> getString(series.metric.labelRes)
    }

    private fun render(state: BodyMeasurementsViewModel.ScreenState) {
        adapter.submit(state.entries, state.units, state.enabled)
        binding.bodyMeasurementsEmpty.visibility = if (state.entries.isEmpty()) View.VISIBLE else View.GONE

        val group = binding.bodyMeasurementsSeriesGroup
        if (state.options != renderedOptions) {
            group.setOnCheckedStateChangeListener(null)
            group.removeAllViews()
            state.options.forEach { newChip(group, label(it), it) }
            renderedOptions = state.options
            group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
                val series = checkedIds.firstOrNull()?.let { chipGroup.findViewById<Chip>(it).tag as BodySeries } ?: return@setOnCheckedStateChangeListener
                viewModel.select(series)
            }
        }
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            if (chip.tag == state.selected && !chip.isChecked) {
                chip.isChecked = true
            }
        }
        val periodGroup = binding.bodyMeasurementsPeriodGroup
        for (i in 0 until periodGroup.childCount) {
            val chip = periodGroup.getChildAt(i) as Chip
            if (chip.tag == state.period && !chip.isChecked) {
                chip.isChecked = true
            }
        }

        val mainColor = ContextCompat.getColor(this, R.color.accent)
        val scaleColor = ContextCompat.getColor(this, R.color.chart_weight_body_water)
        val isWeight = state.selected == BodySeries.Measured(BodyMeasurementType.WEIGHT)
        val series = mutableListOf<ChartSeries>()
        series.add(
            BodyMeasurementChart.line(
                "main",
                if (isWeight) getString(R.string.body_measurements_series_typed) else label(state.selected),
                state.chart.main,
                mainColor,
            )
        )
        if (isWeight) {
            series.add(BodyMeasurementChart.line("scale", getString(R.string.body_measurements_series_scale), state.chart.scale, scaleColor))
        }
        val spec = BodyMeasurementChart.spec(series, state.chart.fromSec, state.chart.toSec)
        binding.bodyMeasurementsChart.setSpec(spec)
        if (spec.isEmpty) {
            binding.bodyMeasurementsChart.showMessage(getString(R.string.body_measurements_chart_empty))
        }
        binding.bodyMeasurementsChartLegend.setSeries(if (isWeight) series else emptyList())
    }

    private fun openEditor(entryId: Long?) {
        val intent = Intent(this, BodyMeasurementEditActivity::class.java)
        entryId?.let { intent.putExtra(BodyMeasurementEditActivity.EXTRA_ENTRY_ID, it) }
        startActivity(intent)
    }

    private fun confirmDelete(entry: BodyMeasurement) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.body_measurements_delete_title)
            .setMessage(DateUtils.formatDateTime(this, entry.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.delete(entry.id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_body_measurements, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            // finish() instead of the default up action, which recreates the parent.
            finish()
            true
        }
        R.id.body_measurements_settings -> {
            startActivity(Intent(this, BodyMeasurementsSettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
