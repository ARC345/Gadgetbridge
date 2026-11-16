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

import android.os.Parcelable
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementUnits
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * One editor field. [shownText] is the text that the editor first showed for [storedValue]. If
 * the user does not change it, the save keeps [storedValue] exactly, so a unit conversion never
 * changes a stored value a little on each save.
 */
@Parcelize
data class FieldState(
    val code: Int,
    val storedValue: Float?,
    val shownText: String,
    val currentText: String,
) : Parcelable {
    val isDirty: Boolean get() = currentText.trim() != shownText.trim()
}

sealed interface FieldOutcome {
    data object Unchanged : FieldOutcome

    data object Cleared : FieldOutcome

    /** The new value in the storage unit. */
    data class Changed(val value: Float) : FieldOutcome

    data class Invalid(@StringRes val messageRes: Int) : FieldOutcome
}

/** The pure logic of the entry editor, without Android views. */
object BodyMeasurementForm {
    sealed interface Validation {
        data class Ok(val upserts: Map<Int, Float>, val clearedTypes: Set<Int>) : Validation

        /** Type code to the message of the error. */
        data class FieldErrors(val errors: Map<Int, Int>) : Validation

        /** The entry would have no values and no note. */
        data object Empty : Validation
    }

    fun newField(type: BodyMeasurementType, storedValue: Float?, units: BodyMeasurementUnits, locale: Locale = Locale.getDefault()): FieldState {
        val text = storedValue?.let { units.formatInput(type, it.toDouble(), locale) }.orEmpty()
        return FieldState(type.code, storedValue, text, text)
    }

    /** Accepts a comma or a full stop as the decimal sign. */
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    fun outcome(field: FieldState, units: BodyMeasurementUnits): FieldOutcome {
        if (!field.isDirty) {
            return FieldOutcome.Unchanged
        }
        val text = field.currentText.trim()
        if (text.isEmpty()) {
            return if (field.storedValue != null) FieldOutcome.Cleared else FieldOutcome.Unchanged
        }
        val type = BodyMeasurementType.fromCode(field.code) ?: return FieldOutcome.Unchanged
        val number = parse(text)
        if (number == null || !number.isFinite()) {
            return FieldOutcome.Invalid(R.string.body_measurements_error_number)
        }
        if (number <= 0) {
            return FieldOutcome.Invalid(R.string.body_measurements_error_positive)
        }
        if (type == BodyMeasurementType.BODY_FAT && number > 100) {
            return FieldOutcome.Invalid(R.string.body_measurements_error_body_fat)
        }
        return FieldOutcome.Changed(units.fromInput(type, number).toFloat())
    }

    /**
     * @param existingCodes the codes of all value rows that the entry has now, also hidden types
     * and unknown codes
     */
    fun validate(fields: List<FieldState>, existingCodes: Set<Int>, note: String, units: BodyMeasurementUnits): Validation {
        val upserts = LinkedHashMap<Int, Float>()
        val cleared = LinkedHashSet<Int>()
        val errors = LinkedHashMap<Int, Int>()
        for (field in fields) {
            when (val outcome = outcome(field, units)) {
                FieldOutcome.Unchanged -> Unit
                FieldOutcome.Cleared -> cleared.add(field.code)
                is FieldOutcome.Changed -> upserts[field.code] = outcome.value
                is FieldOutcome.Invalid -> errors[field.code] = outcome.messageRes
            }
        }
        if (errors.isNotEmpty()) {
            return Validation.FieldErrors(errors)
        }
        val remaining = (existingCodes - cleared) + upserts.keys
        if (remaining.isEmpty() && note.isBlank()) {
            return Validation.Empty
        }
        return Validation.Ok(upserts, cleared)
    }

    /** The values for the "Calculated" box: valid typed text, else the stored values. */
    fun currentValues(fields: List<FieldState>, existing: Map<Int, Float>, units: BodyMeasurementUnits): Map<BodyMeasurementType, Double> {
        val values = LinkedHashMap(BodyMeasurementCalculator.typedValues(existing))
        for (field in fields) {
            val type = BodyMeasurementType.fromCode(field.code) ?: continue
            when (val outcome = outcome(field, units)) {
                FieldOutcome.Unchanged -> Unit
                FieldOutcome.Cleared, is FieldOutcome.Invalid -> values.remove(type)
                is FieldOutcome.Changed -> values[type] = outcome.value.toDouble()
            }
        }
        return values
    }

    /** A time in a daylight saving gap moves forward by the length of the gap. */
    fun timestampOf(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}
