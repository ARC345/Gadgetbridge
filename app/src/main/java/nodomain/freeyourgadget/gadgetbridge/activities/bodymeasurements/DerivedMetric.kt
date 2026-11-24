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
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import java.util.Locale

/**
 * A value that the app calculates from the measurements and is never stored. Each formula uses
 * one fixed waist site and never the other one, so a chart series never mixes two sites.
 */
enum class DerivedMetric(@StringRes val labelRes: Int) {
    BMI(R.string.body_measurements_derived_bmi),
    NAVY_BODY_FAT(R.string.body_measurements_derived_navy),
    SKINFOLD_BODY_FAT(R.string.body_measurements_derived_skinfold),
    WAIST_TO_HIP(R.string.body_measurements_derived_waist_to_hip),
    WAIST_TO_HEIGHT(R.string.body_measurements_derived_waist_to_height),
    RELATIVE_FAT_MASS(R.string.body_measurements_derived_relative_fat_mass),
    BODY_ROUNDNESS(R.string.body_measurements_derived_body_roundness),
    WEIGHT_ADJUSTED_WAIST(R.string.body_measurements_derived_weight_adjusted_waist),
    CONICITY(R.string.body_measurements_derived_conicity),
    ;

    /** The body fat formulas of the sex, Navy, skinfold and relative fat mass, exist only for men and women. */
    val needsSex: Boolean get() = this == NAVY_BODY_FAT || this == SKINFOLD_BODY_FAT || this == RELATIVE_FAT_MASS

    /** The measured types that the formula needs, in the order that "Needs" names them. */
    fun inputs(gender: Int): List<BodyMeasurementType> = when (this) {
        BMI -> listOf(BodyMeasurementType.WEIGHT)
        NAVY_BODY_FAT -> when (gender) {
            ActivityUser.GENDER_MALE -> listOf(BodyMeasurementType.NECK, BodyMeasurementType.WAIST_NAVEL)
            ActivityUser.GENDER_FEMALE -> listOf(BodyMeasurementType.NECK, BodyMeasurementType.WAIST_NARROWEST, BodyMeasurementType.HIPS)
            else -> emptyList()
        }
        SKINFOLD_BODY_FAT -> when (gender) {
            ActivityUser.GENDER_MALE -> listOf(BodyMeasurementType.CHEST_SKINFOLD, BodyMeasurementType.ABDOMEN_SKINFOLD, BodyMeasurementType.THIGH_SKINFOLD)
            ActivityUser.GENDER_FEMALE -> listOf(BodyMeasurementType.TRICEPS_SKINFOLD, BodyMeasurementType.SUPRAILIAC_SKINFOLD, BodyMeasurementType.THIGH_SKINFOLD)
            else -> emptyList()
        }
        WAIST_TO_HIP -> listOf(BodyMeasurementType.WAIST_NAVEL, BodyMeasurementType.HIPS)
        WAIST_TO_HEIGHT -> listOf(BodyMeasurementType.WAIST_NAVEL)
        RELATIVE_FAT_MASS -> listOf(BodyMeasurementType.WAIST_NAVEL)
        BODY_ROUNDNESS -> listOf(BodyMeasurementType.WAIST_NAVEL)
        WEIGHT_ADJUSTED_WAIST -> listOf(BodyMeasurementType.WAIST_NAVEL, BodyMeasurementType.WEIGHT)
        CONICITY -> listOf(BodyMeasurementType.WAIST_NAVEL, BodyMeasurementType.WEIGHT)
    }

    private fun hasSex(gender: Int): Boolean = gender == ActivityUser.GENDER_MALE || gender == ActivityUser.GENDER_FEMALE

    /**
     * The editor box shows the metric when all of its inputs are enabled. A body fat formula also
     * shows when the inputs of only the other sex are enabled, so that a wrong sex in "About you"
     * is noticed from the formula that the line names.
     */
    fun showsInBox(gender: Int, enabled: Set<BodyMeasurementType>): Boolean = when {
        !needsSex || !hasSex(gender) -> enabled.containsAll(inputs(gender))
        else -> enabled.containsAll(inputs(ActivityUser.GENDER_MALE)) || enabled.containsAll(inputs(ActivityUser.GENDER_FEMALE))
    }

    /** The chart needs a result, so the inputs of the sex of the user and, for body fat, male or female. */
    fun showsInChart(gender: Int, enabled: Set<BodyMeasurementType>): Boolean =
        enabled.containsAll(inputs(gender)) && (!needsSex || hasSex(gender))

    fun format(value: Double, locale: Locale = Locale.getDefault()): String = when (this) {
        BMI -> String.format(locale, "%.1f", value)
        NAVY_BODY_FAT, SKINFOLD_BODY_FAT -> String.format(locale, "%.1f %%", value)
        RELATIVE_FAT_MASS -> String.format(locale, "%.1f %%", value)
        WAIST_TO_HIP, WAIST_TO_HEIGHT, BODY_ROUNDNESS, WEIGHT_ADJUSTED_WAIST, CONICITY -> String.format(locale, "%.2f", value)
    }
}
