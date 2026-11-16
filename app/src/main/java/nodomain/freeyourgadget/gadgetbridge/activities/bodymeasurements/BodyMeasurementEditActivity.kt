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

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityBodyMeasurementEditBinding
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemBodyMeasurementFieldBinding
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class BodyMeasurementEditActivity : AbstractGBActivity() {
    private val viewModel: BodyMeasurementEditViewModel by viewModels()
    private lateinit var binding: ActivityBodyMeasurementEditBinding
    private val fieldViews = HashMap<Int, TextInputLayout>()
    private var built = false
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBodyMeasurementEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyImeInsets()
        setTitle(if (viewModel.entryId == null) R.string.body_measurements_new_title else R.string.body_measurements_edit_title)

        // Enabled only with unsaved changes: an enabled callback blocks the predictive back animation.
        val confirmExitCallback = onBackPressedDispatcher.addCallback(this, enabled = false) { confirmAndFinish() }
        lifecycleScope.launch {
            viewModel.hasUnsavedChanges.collect { confirmExitCallback.isEnabled = it }
        }
        binding.bodyMeasurementDate.setOnClickListener { pickDate() }
        binding.bodyMeasurementTime.setOnClickListener { pickTime() }
        // A picker that survived a recreation lost its listener; attach it again.
        @Suppress("UNCHECKED_CAST")
        (supportFragmentManager.findFragmentByTag(TAG_DATE) as? MaterialDatePicker<Long>)?.let(::attachDateListener)
        (supportFragmentManager.findFragmentByTag(TAG_TIME) as? MaterialTimePicker)?.let(::attachTimeListener)

        lifecycleScope.launch {
            viewModel.ui.collect { ui -> ui?.let(::render) }
        }
    }

    /** The base activity pads for the system bars only; the editor must also stay above the keyboard. */
    private fun applyImeInsets() {
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun render(ui: BodyMeasurementEditViewModel.UiState) {
        if (!built) {
            buildFields(ui.fields)
            binding.bodyMeasurementNote.setText(ui.note)
            binding.bodyMeasurementNote.doAfterTextChanged { viewModel.onNoteChanged(it?.toString().orEmpty()) }
            built = true
        }
        binding.bodyMeasurementDate.text = DateUtils.formatDateTime(this, ui.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
        binding.bodyMeasurementTime.text = DateUtils.formatDateTime(this, ui.timestamp, DateUtils.FORMAT_SHOW_TIME)
        for ((code, layout) in fieldViews) {
            val type = BodyMeasurementType.fromCode(code) ?: continue
            layout.helperText = ui.lastValues[code]?.let {
                getString(
                    R.string.body_measurements_last_value,
                    viewModel.units.formatDisplay(this, type, it.value.toDouble()),
                    DateUtils.formatDateTime(this, it.timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
                )
            }
        }
        binding.bodyMeasurementCalculated.text = ui.calculated.joinToString("\n") { (metric, result) ->
            getString(R.string.body_measurements_derived_line, getString(metric.labelRes), describe(metric, result))
        }
    }

    private fun describe(metric: DerivedMetric, result: DerivedResult): String = when (result) {
        is DerivedResult.Value -> {
            val text = metric.format(result.value)
            result.sourceRes?.let { getString(R.string.body_measurements_value_with_source, text, getString(it)) } ?: text
        }
        is DerivedResult.Missing -> getString(R.string.body_measurements_needs, getString(result.type.labelRes))
        DerivedResult.NeedsSex -> getString(R.string.body_measurements_needs_sex)
        DerivedResult.OutOfRange -> getString(R.string.body_measurements_out_of_range)
    }

    private fun buildFields(fields: List<FieldState>) {
        val byCode = fields.associateBy { it.code }
        val groups = listOf(
            R.string.body_measurements_group_weight to setOf(BodyMeasurementType.Category.WEIGHT, BodyMeasurementType.Category.BODY_FAT),
            R.string.body_measurements_group_circumferences to setOf(BodyMeasurementType.Category.CIRCUMFERENCE),
            R.string.body_measurements_group_skinfolds to setOf(BodyMeasurementType.Category.SKINFOLD),
        )
        val container = binding.bodyMeasurementFields
        val inflater = LayoutInflater.from(this)
        for ((titleRes, categories) in groups) {
            val types = BodyMeasurementType.entries.filter { it.category in categories && byCode.containsKey(it.code) }
            if (types.isEmpty()) {
                continue
            }
            val header = inflater.inflate(R.layout.item_body_measurement_group, container, false) as TextView
            header.setText(titleRes)
            container.addView(header)
            for (type in types) {
                val field = ItemBodyMeasurementFieldBinding.inflate(inflater, container, false)
                val layout = field.root
                layout.hint = getString(type.labelRes)
                layout.suffixText = getString(viewModel.units.unitRes(type))
                // All fields share one view id, so the automatic view state restore must be off;
                // the ViewModel keeps the text.
                field.bodyMeasurementFieldInput.isSaveEnabled = false
                field.bodyMeasurementFieldInput.setText(byCode.getValue(type.code).currentText)
                field.bodyMeasurementFieldInput.doAfterTextChanged {
                    layout.error = null
                    viewModel.onTextChanged(type.code, it?.toString().orEmpty())
                }
                layout.setEndIconOnClickListener { showHelp(type) }
                container.addView(layout)
                fieldViews[type.code] = layout
            }
        }
    }

    private fun showHelp(type: BodyMeasurementType) {
        MaterialAlertDialogBuilder(this)
            .setTitle(type.labelRes)
            .setMessage(type.helpRes)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(DateTimeUtils.toUtcDayStartMillis(viewModel.currentTimestamp()))
            .build()
        attachDateListener(picker)
        picker.show(supportFragmentManager, TAG_DATE)
    }

    private fun attachDateListener(picker: MaterialDatePicker<Long>) {
        picker.addOnPositiveButtonClickListener { selection ->
            // The picker uses UTC midnight; keep the local time of the entry.
            val zone = ZoneId.systemDefault()
            val time = Instant.ofEpochMilli(viewModel.currentTimestamp()).atZone(zone).toLocalTime()
            viewModel.changeTimestamp(BodyMeasurementForm.timestampOf(DateTimeUtils.utcMillisToLocalDate(selection), time, zone))
        }
    }

    private fun pickTime() {
        val current = Instant.ofEpochMilli(viewModel.currentTimestamp()).atZone(ZoneId.systemDefault())
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(current.hour)
            .setMinute(current.minute)
            .build()
        attachTimeListener(picker)
        picker.show(supportFragmentManager, TAG_TIME)
    }

    private fun attachTimeListener(picker: MaterialTimePicker) {
        picker.addOnPositiveButtonClickListener {
            val zone = ZoneId.systemDefault()
            val date = Instant.ofEpochMilli(viewModel.currentTimestamp()).atZone(zone).toLocalDate()
            viewModel.changeTimestamp(BodyMeasurementForm.timestampOf(date, LocalTime.of(picker.hour, picker.minute), zone))
        }
    }

    private fun save() {
        if (saving) {
            return
        }
        saving = true
        lifecycleScope.launch {
            when (val result = viewModel.save()) {
                is BodyMeasurementForm.Validation.Ok -> {
                    setResult(RESULT_OK)
                    finish()
                }
                is BodyMeasurementForm.Validation.FieldErrors -> {
                    result.errors.forEach { (code, messageRes) -> fieldViews[code]?.error = getString(messageRes) }
                    saving = false
                }
                BodyMeasurementForm.Validation.Empty -> {
                    GB.toast(this@BodyMeasurementEditActivity, R.string.body_measurements_error_empty, Toast.LENGTH_SHORT, GB.WARN)
                    saving = false
                }
            }
        }
    }

    private fun confirmAndFinish() {
        if (!viewModel.hasUnsavedChanges.value) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.body_measurements_discard_title)
            .setPositiveButton(R.string.body_measurements_discard) { _, _ -> finish() }
            .setNegativeButton(R.string.body_measurements_keep_editing, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_body_measurement_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            confirmAndFinish()
            true
        }
        R.id.body_measurement_save -> {
            save()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_ENTRY_ID = "body_measurement_entry_id"
        private const val TAG_DATE = "BODY_MEASUREMENT_DATE"
        private const val TAG_TIME = "BODY_MEASUREMENT_TIME"
    }
}
