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
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionEstimates
import java.time.Instant
import java.time.ZoneId

/** The user attributes at the time of an entry. Sex comes from "About you" as it is. */
data class BodyProfile(val gender: Int, val ageYears: Int, val heightCm: Int)

sealed interface DerivedResult {
    /** [sourceRes] names the weight source (BMI) or the formula sex (body fat), or is null. */
    data class Value(val value: Double, @StringRes val sourceRes: Int?) : DerivedResult

    data class Missing(val type: BodyMeasurementType) : DerivedResult

    data object NeedsSex : DerivedResult

    /** The inputs are present, but the result is impossible. The chart does not plot it. */
    data object OutOfRange : DerivedResult
}

object BodyMeasurementCalculator {
    /**
     * @param values the measured values in storage units
     * @param scaleWeightKg the scale weight of the entry day, used for BMI without a typed weight
     */
    fun calculate(
        metric: DerivedMetric,
        values: Map<BodyMeasurementType, Double>,
        profile: BodyProfile,
        scaleWeightKg: Double?,
    ): DerivedResult {
        val male = profile.gender == ActivityUser.GENDER_MALE
        if (metric.needsSex && !male && profile.gender != ActivityUser.GENDER_FEMALE) {
            return DerivedResult.NeedsSex
        }
        if (metric == DerivedMetric.BMI) {
            val typed = values[BodyMeasurementType.WEIGHT]?.takeIf { it > 0 }
            val weightKg = typed ?: scaleWeightKg ?: return DerivedResult.Missing(BodyMeasurementType.WEIGHT)
            val bmi = BodyCompositionEstimates.bmi(weightKg, profile.heightCm.toDouble())
                ?: return DerivedResult.Missing(BodyMeasurementType.WEIGHT)
            val source = if (typed != null) R.string.body_measurements_weight_typed else R.string.body_measurements_weight_scale
            return DerivedResult.Value(bmi, source)
        }
        metric.inputs(profile.gender).firstOrNull { (values[it] ?: 0.0) <= 0.0 }?.let {
            return DerivedResult.Missing(it)
        }
        val height = profile.heightCm.toDouble()
        val raw: Double? = when (metric) {
            DerivedMetric.NAVY_BODY_FAT -> BodyCompositionEstimates.navyBodyFat(
                male,
                height,
                values[BodyMeasurementType.NECK],
                values[if (male) BodyMeasurementType.WAIST_NAVEL else BodyMeasurementType.WAIST_NARROWEST],
                values[BodyMeasurementType.HIPS],
            )
            DerivedMetric.SKINFOLD_BODY_FAT -> BodyCompositionEstimates.skinfoldBodyFat(
                male,
                profile.ageYears,
                metric.inputs(profile.gender).sumOf { values.getValue(it) },
            )
            DerivedMetric.WAIST_TO_HIP -> BodyCompositionEstimates.waistToHip(values[BodyMeasurementType.WAIST_NAVEL], values[BodyMeasurementType.HIPS])
            DerivedMetric.WAIST_TO_HEIGHT -> BodyCompositionEstimates.waistToHeight(values[BodyMeasurementType.WAIST_NAVEL], height)
            DerivedMetric.RELATIVE_FAT_MASS -> BodyCompositionEstimates.relativeFatMass(male, height, values[BodyMeasurementType.WAIST_NAVEL])
            DerivedMetric.BODY_ROUNDNESS -> BodyCompositionEstimates.bodyRoundnessIndex(values[BodyMeasurementType.WAIST_NAVEL], height)
            DerivedMetric.WEIGHT_ADJUSTED_WAIST -> BodyCompositionEstimates.weightAdjustedWaist(values[BodyMeasurementType.WAIST_NAVEL], values[BodyMeasurementType.WEIGHT])
            DerivedMetric.CONICITY -> BodyCompositionEstimates.conicityIndex(values[BodyMeasurementType.WAIST_NAVEL], values[BodyMeasurementType.WEIGHT], height)
            DerivedMetric.BMI -> error("handled above")
        }
        if (raw == null || (metric.needsSex && !BodyCompositionEstimates.isPlausibleBodyFat(raw))) {
            return DerivedResult.OutOfRange
        }
        val source = when {
            !metric.needsSex -> null
            male -> R.string.body_measurements_formula_male
            else -> R.string.body_measurements_formula_female
        }
        return DerivedResult.Value(raw, source)
    }

    /** Stored values (type code to value) as measured values; unknown codes are left out. */
    fun typedValues(raw: Map<Int, Float>): Map<BodyMeasurementType, Double> =
        raw.entries.mapNotNull { (code, value) -> BodyMeasurementType.fromCode(code)?.let { it to value.toDouble() } }.toMap()

    /** Needs the database (height history) and the preferences (sex, date of birth). */
    fun profileAt(session: DaoSession, timestampMillis: Long): BodyProfile {
        val user = ActivityUser()
        val date = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return BodyProfile(user.gender, user.getAgeAt(date), BodyCompositionEstimates.heightCmAt(session, timestampMillis))
    }
}
