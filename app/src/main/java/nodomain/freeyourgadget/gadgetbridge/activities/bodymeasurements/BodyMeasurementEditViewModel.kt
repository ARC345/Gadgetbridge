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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurementRepository
import nodomain.freeyourgadget.gadgetbridge.database.repository.TimedValue
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits

/**
 * The state of the entry editor. The field states, the date and time and the note are kept in
 * [SavedStateHandle], so they survive a recreation of the activity. Everything else is loaded
 * again from the database.
 */
class BodyMeasurementEditViewModel(private val state: SavedStateHandle) : ViewModel() {
    data class UiState(
        val timestamp: Long,
        val note: String,
        val fields: List<FieldState>,
        val lastValues: Map<Int, TimedValue>,
        val calculated: List<Pair<DerivedMetric, DerivedResult>>,
    )

    /** The intent extras are the defaults of the SavedStateHandle. */
    val entryId: Long? = state.get<Long>(BodyMeasurementEditActivity.EXTRA_ENTRY_ID)?.takeIf { it > 0 }
    val units: BodyMeasurementUnits = BodyMeasurementPrefs.units()
    private val enabled = BodyMeasurementPrefs.enabledTypes()

    private var existing: Map<Int, Float> = emptyMap()
    private var profile: BodyProfile? = null
    private var scaleWeightKg: Double? = null
    private var lastValues: Map<Int, TimedValue> = emptyMap()

    private val _ui = MutableStateFlow<UiState?>(null)
    val ui: StateFlow<UiState?> = _ui.asStateFlow()

    private var fields: List<FieldState>
        get() = state.get<ArrayList<FieldState>>(KEY_FIELDS).orEmpty()
        set(value) {
            state[KEY_FIELDS] = ArrayList(value)
        }
    private var timestamp: Long
        get() = state.get<Long>(KEY_TIMESTAMP) ?: 0L
        set(value) {
            state[KEY_TIMESTAMP] = value
        }
    private var note: String
        get() = state.get<String>(KEY_NOTE).orEmpty()
        set(value) {
            state[KEY_NOTE] = value
        }
    private var originalTimestamp: Long
        get() = state.get<Long>(KEY_ORIGINAL_TIMESTAMP) ?: 0L
        set(value) {
            state[KEY_ORIGINAL_TIMESTAMP] = value
        }
    private var originalNote: String
        get() = state.get<String>(KEY_ORIGINAL_NOTE).orEmpty()
        set(value) {
            state[KEY_ORIGINAL_NOTE] = value
        }

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val restored = state.contains(KEY_FIELDS)
        val now = System.currentTimeMillis()
        val id = entryId
        val entry = withContext(Dispatchers.IO) {
            GBApplication.acquireDB().use { db -> id?.let { BodyMeasurementRepository(db.daoSession).entry(it) } }
        }
        existing = entry?.values.orEmpty()
        if (!restored) {
            timestamp = entry?.timestamp ?: now
            originalTimestamp = timestamp
            note = entry?.note.orEmpty()
            originalNote = note
            fields = BodyMeasurementType.entries.filter { it in enabled }
                .map { BodyMeasurementForm.newField(it, existing[it.code], units) }
        }
        reloadContext()
    }

    /** Loads what depends on the entry time: the profile, the scale weight and the last values. */
    private suspend fun reloadContext() {
        val ts = timestamp
        val codes = fields.map { it.code }
        val id = entryId
        withContext(Dispatchers.IO) {
            GBApplication.acquireDB().use { db ->
                val repo = BodyMeasurementRepository(db.daoSession)
                profile = BodyMeasurementCalculator.profileAt(db.daoSession, ts)
                scaleWeightKg = repo.scaleWeightOnDay(ts)?.toDouble()
                lastValues = codes.mapNotNull { code -> repo.latestValue(code, ts, id)?.let { code to it } }.toMap()
            }
        }
        publish()
    }

    private fun publish() {
        refreshUnsavedChanges()
        val currentProfile = profile ?: return
        val values = BodyMeasurementForm.currentValues(fields, existing, units)
        val calculated = DerivedMetric.entries
            .filter { it.showsInBox(currentProfile.gender, enabled) }
            .map { it to BodyMeasurementCalculator.calculate(it, values, currentProfile, scaleWeightKg) }
        _ui.value = UiState(timestamp, note, fields, lastValues, calculated)
    }

    fun onTextChanged(code: Int, text: String) {
        fields = fields.map { if (it.code == code) it.copy(currentText = text) else it }
        publish()
    }

    fun onNoteChanged(text: String) {
        note = text
        refreshUnsavedChanges()
    }

    fun changeTimestamp(value: Long) {
        timestamp = value
        refreshUnsavedChanges()
        viewModelScope.launch { reloadContext() }
    }

    fun currentTimestamp(): Long = timestamp

    private val _hasUnsavedChanges = MutableStateFlow(false)

    /** The activity enables its back callback only while this is true, so that predictive back works otherwise. */
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private fun refreshUnsavedChanges() {
        _hasUnsavedChanges.value =
            fields.any { it.isDirty } || note.trim() != originalNote.trim() || timestamp != originalTimestamp
    }

    /** Validates and, if the form is valid, saves. */
    suspend fun save(): BodyMeasurementForm.Validation {
        val result = BodyMeasurementForm.validate(fields, existing.keys, note, units)
        if (result is BodyMeasurementForm.Validation.Ok) {
            val id = entryId
            val ts = timestamp
            val text = note
            withContext(Dispatchers.IO) {
                GBApplication.acquireDB().use { db ->
                    BodyMeasurementRepository(db.daoSession).save(id, ts, text, result.upserts, result.clearedTypes)
                }
            }
        }
        return result
    }

    private companion object {
        const val KEY_FIELDS = "body_measurement_fields"
        const val KEY_TIMESTAMP = "body_measurement_timestamp"
        const val KEY_NOTE = "body_measurement_note"
        const val KEY_ORIGINAL_TIMESTAMP = "body_measurement_original_timestamp"
        const val KEY_ORIGINAL_NOTE = "body_measurement_original_note"
    }
}
