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

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * A body measurement that the user types by hand. The code is stored in the database: never
 * change it, and never use it again for a different type. The order of the entries is the
 * display order.
 */
enum class BodyMeasurementType(
    val code: Int,
    val category: Category,
    @StringRes val labelRes: Int,
    @StringRes val helpRes: Int,
    val enabledByDefault: Boolean,
) {
    WEIGHT(1, Category.WEIGHT, R.string.body_measurements_type_weight, R.string.body_measurements_help_weight, true),
    BODY_FAT(2, Category.BODY_FAT, R.string.body_measurements_type_body_fat, R.string.body_measurements_help_body_fat, false),
    NECK(100, Category.CIRCUMFERENCE, R.string.body_measurements_type_neck, R.string.body_measurements_help_neck, true),
    SHOULDERS(101, Category.CIRCUMFERENCE, R.string.body_measurements_type_shoulders, R.string.body_measurements_help_shoulders, true),
    CHEST(102, Category.CIRCUMFERENCE, R.string.body_measurements_type_chest, R.string.body_measurements_help_chest, true),
    WAIST_NARROWEST(103, Category.CIRCUMFERENCE, R.string.body_measurements_type_waist_narrowest, R.string.body_measurements_help_waist_narrowest, false),
    WAIST_NAVEL(104, Category.CIRCUMFERENCE, R.string.body_measurements_type_waist_navel, R.string.body_measurements_help_waist_navel, true),
    HIPS(105, Category.CIRCUMFERENCE, R.string.body_measurements_type_hips, R.string.body_measurements_help_hips, true),
    UPPER_ARM_RELAXED_LEFT(110, Category.CIRCUMFERENCE, R.string.body_measurements_type_upper_arm_relaxed_left, R.string.body_measurements_help_upper_arm_relaxed, true),
    UPPER_ARM_RELAXED_RIGHT(111, Category.CIRCUMFERENCE, R.string.body_measurements_type_upper_arm_relaxed_right, R.string.body_measurements_help_upper_arm_relaxed, true),
    UPPER_ARM_FLEXED_LEFT(112, Category.CIRCUMFERENCE, R.string.body_measurements_type_upper_arm_flexed_left, R.string.body_measurements_help_upper_arm_flexed, true),
    UPPER_ARM_FLEXED_RIGHT(113, Category.CIRCUMFERENCE, R.string.body_measurements_type_upper_arm_flexed_right, R.string.body_measurements_help_upper_arm_flexed, true),
    FOREARM_LEFT(114, Category.CIRCUMFERENCE, R.string.body_measurements_type_forearm_left, R.string.body_measurements_help_forearm, true),
    FOREARM_RIGHT(115, Category.CIRCUMFERENCE, R.string.body_measurements_type_forearm_right, R.string.body_measurements_help_forearm, true),
    THIGH_LEFT(116, Category.CIRCUMFERENCE, R.string.body_measurements_type_thigh_left, R.string.body_measurements_help_thigh, true),
    THIGH_RIGHT(117, Category.CIRCUMFERENCE, R.string.body_measurements_type_thigh_right, R.string.body_measurements_help_thigh, true),
    CALF_LEFT(118, Category.CIRCUMFERENCE, R.string.body_measurements_type_calf_left, R.string.body_measurements_help_calf, true),
    CALF_RIGHT(119, Category.CIRCUMFERENCE, R.string.body_measurements_type_calf_right, R.string.body_measurements_help_calf, true),
    CHEST_SKINFOLD(200, Category.SKINFOLD, R.string.body_measurements_type_chest_skinfold, R.string.body_measurements_help_chest_skinfold, false),
    ABDOMEN_SKINFOLD(201, Category.SKINFOLD, R.string.body_measurements_type_abdomen_skinfold, R.string.body_measurements_help_abdomen_skinfold, false),
    THIGH_SKINFOLD(202, Category.SKINFOLD, R.string.body_measurements_type_thigh_skinfold, R.string.body_measurements_help_thigh_skinfold, false),
    TRICEPS_SKINFOLD(203, Category.SKINFOLD, R.string.body_measurements_type_triceps_skinfold, R.string.body_measurements_help_triceps_skinfold, false),
    SUPRAILIAC_SKINFOLD(204, Category.SKINFOLD, R.string.body_measurements_type_suprailiac_skinfold, R.string.body_measurements_help_suprailiac_skinfold, false),
    ;

    /** The category sets the storage unit: kg, %, cm or mm. */
    enum class Category { WEIGHT, BODY_FAT, CIRCUMFERENCE, SKINFOLD }

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        /** The type of a stored code, or null for a code that this build does not know. */
        fun fromCode(code: Int): BodyMeasurementType? = BY_CODE[code]

        fun defaultEnabled(): Set<BodyMeasurementType> = entries.filterTo(LinkedHashSet()) { it.enabledByDefault }
    }
}
