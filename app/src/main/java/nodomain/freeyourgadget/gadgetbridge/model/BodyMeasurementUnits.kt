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

package nodomain.freeyourgadget.gadgetbridge.model

import android.content.Context
import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import java.util.Locale

/**
 * Converts body measurements between the storage unit (kg, %, cm, mm) and the units that the
 * user selected, and formats them.
 */
class BodyMeasurementUnits(val lengthUnit: LengthUnit, val weightUnit: WeightUnit) {
    /** One number field cannot show "st + lb", so the editor uses pounds for stone. */
    val inputWeightUnit: WeightUnit = if (weightUnit == WeightUnit.STONE) WeightUnit.POUND else weightUnit

    fun toInput(type: BodyMeasurementType, stored: Double): Double = when (type.category) {
        BodyMeasurementType.Category.WEIGHT -> WeightUnit.convertWeight(stored, inputWeightUnit)
        BodyMeasurementType.Category.CIRCUMFERENCE -> lengthUnit.fromCm(stored)
        BodyMeasurementType.Category.BODY_FAT, BodyMeasurementType.Category.SKINFOLD -> stored
    }

    fun fromInput(type: BodyMeasurementType, value: Double): Double = when (type.category) {
        BodyMeasurementType.Category.WEIGHT -> WeightUnit.toKg(value, inputWeightUnit)
        BodyMeasurementType.Category.CIRCUMFERENCE -> lengthUnit.toCm(value)
        BodyMeasurementType.Category.BODY_FAT, BodyMeasurementType.Category.SKINFOLD -> value
    }

    fun decimals(type: BodyMeasurementType): Int = when (type.category) {
        BodyMeasurementType.Category.WEIGHT -> 2
        BodyMeasurementType.Category.CIRCUMFERENCE -> lengthUnit.decimals
        BodyMeasurementType.Category.BODY_FAT, BodyMeasurementType.Category.SKINFOLD -> 1
    }

    /** A plain number without unit text, with a fixed number of decimals, for an editor field. */
    fun formatInput(type: BodyMeasurementType, stored: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.${decimals(type)}f", toInput(type, stored))

    @StringRes
    fun unitRes(type: BodyMeasurementType): Int = when (type.category) {
        BodyMeasurementType.Category.WEIGHT -> when (inputWeightUnit) {
            WeightUnit.KILOGRAM -> R.string.unit_kg
            WeightUnit.JIN -> R.string.body_measurements_unit_jin
            WeightUnit.POUND, WeightUnit.STONE -> R.string.unit_lb
        }
        BodyMeasurementType.Category.BODY_FAT -> R.string.unit_percentage
        BodyMeasurementType.Category.CIRCUMFERENCE -> if (lengthUnit == LengthUnit.INCH) R.string.unit_inch else R.string.cm
        BodyMeasurementType.Category.SKINFOLD -> R.string.unit_millimeter
    }

    /** Read-only text with the unit, for the list, the "Last:" helper text and the chart. */
    fun formatDisplay(context: Context, type: BodyMeasurementType, stored: Double): String =
        if (type.category == BodyMeasurementType.Category.WEIGHT) {
            WeightUnit.formatWeight(context, stored, weightUnit)
        } else {
            formatInput(type, stored) + " " + context.getString(unitRes(type))
        }

    /** The chart value: weight in the display weight unit (decimal stone for stone). */
    fun toChart(type: BodyMeasurementType, stored: Double): Double =
        if (type.category == BodyMeasurementType.Category.WEIGHT) WeightUnit.convertWeight(stored, weightUnit) else toInput(type, stored)
}
