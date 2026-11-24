/*  Copyright (C) 2026 oddballza

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
package nodomain.freeyourgadget.gadgetbridge.util;

import androidx.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneId;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.UserAttributes;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample;

/**
 * Values derived from a weight measurement when it is displayed: BMI from the weight and the
 * height, and the body composition from the impedance. Nothing here is persisted; the
 * profile of the measurement's day (height, age) is used, not today's.
 */
public final class BodyCompositionEstimates {
    private BodyCompositionEstimates() {
    }

    /**
     * Body mass index of a measurement, from its weight and the height recorded at the time of
     * the measurement. Needs no impedance, so it is available for any scale.
     *
     * @return the BMI, or null without a sample or a usable height
     */
    @Nullable
    public static Float bmi(final DaoSession session, @Nullable final WeightSample sample) {
        if (sample == null) {
            return null;
        }
        final int heightCm = heightCmAt(session, sample.getTimestamp());
        if (heightCm <= 0) {
            return null;
        }
        final float heightM = heightCm / 100f;
        return sample.getWeightKg() / (heightM * heightM);
    }

    /**
     * The user's height at the given time: the attributes recorded back then, or the current
     * profile when there is no usable record.
     */
    public static int heightCmAt(final DaoSession session, final long timestampMillis) {
        final User user = DBHelper.getUser(session);
        final UserAttributes attributes = DBHelper.getUserAttributesAt(user, timestampMillis);
        return attributes != null && attributes.getHeightCM() > 0
                ? attributes.getHeightCM()
                : new ActivityUser().getHeightCm();
    }

    /**
     * Estimates the body composition of a measurement from its raw impedance and the user profile
     * as it was at the time of the measurement (height and age change over the years, so an old
     * measurement is evaluated against the attributes recorded back then). Nothing is persisted;
     * the estimate is recomputed whenever it is shown.
     *
     * @return the estimate, or null when the sample carries no usable impedance
     */
    @Nullable
    public static BodyCompositionCalculator.BodyComposition composition(final DaoSession session, @Nullable final WeightSample sample) {
        if (sample == null || sample.getImpedanceOhm() == null) {
            return null;
        }
        final ActivityUser prefsUser = new ActivityUser();
        final int heightCm = heightCmAt(session, sample.getTimestamp());
        final int age = prefsUser.getAgeAt(Instant.ofEpochMilli(sample.getTimestamp()).atZone(ZoneId.systemDefault()).toLocalDate());
        return BodyCompositionCalculator.compute(prefsUser.getGender(), age, heightCm, sample.getWeightKg(), sample.getImpedanceOhm());
    }

    /**
     * Body mass index from a weight and a height.
     *
     * @return the BMI, or null if an input is not more than 0
     */
    @Nullable
    public static Double bmi(final double weightKg, final double heightCm) {
        if (weightKg <= 0 || heightCm <= 0) {
            return null;
        }
        final double heightM = heightCm / 100d;
        return weightKg / (heightM * heightM);
    }

    /**
     * Body fat % by the U.S. Navy circumference method (Hodgdon and Beckett, 1984), with the
     * constants for centimetres. For men, the waist is measured at the navel; for women, at its
     * narrowest point, together with the hips.
     *
     * @return the body fat %, or null if an input is missing or a log argument is not positive
     */
    @Nullable
    public static Double navyBodyFat(final boolean male, final double heightCm, @Nullable final Double neckCm,
                                     @Nullable final Double waistCm, @Nullable final Double hipsCm) {
        if (heightCm <= 0 || !isPositive(neckCm) || !isPositive(waistCm)) {
            return null;
        }
        if (male) {
            final double girth = waistCm - neckCm;
            if (girth <= 0) {
                return null;
            }
            return 86.010 * Math.log10(girth) - 70.041 * Math.log10(heightCm) + 30.30;
        }
        if (!isPositive(hipsCm)) {
            return null;
        }
        final double girth = waistCm + hipsCm - neckCm;
        if (girth <= 0) {
            return null;
        }
        return 163.205 * Math.log10(girth) - 97.684 * Math.log10(heightCm) - 104.912;
    }

    /**
     * Body fat % from the sum of three skinfolds (Jackson and Pollock, 1978, for men; Jackson,
     * Pollock and Ward, 1980, for women), with the Siri equation. Men: chest, abdomen and thigh.
     * Women: triceps, suprailiac and thigh.
     *
     * @return the body fat %, or null if an input is not more than 0
     */
    @Nullable
    public static Double skinfoldBodyFat(final boolean male, final int ageYears, final double sumMm) {
        if (ageYears <= 0 || sumMm <= 0) {
            return null;
        }
        final double density = male
                ? 1.10938 - 0.0008267 * sumMm + 0.0000016 * sumMm * sumMm - 0.0002574 * ageYears
                : 1.0994921 - 0.0009929 * sumMm + 0.0000023 * sumMm * sumMm - 0.0001392 * ageYears;
        if (density <= 0) {
            return null;
        }
        return 495 / density - 450;
    }

    @Nullable
    public static Double waistToHip(@Nullable final Double waistCm, @Nullable final Double hipsCm) {
        if (!isPositive(waistCm) || !isPositive(hipsCm)) {
            return null;
        }
        return waistCm / hipsCm;
    }

    @Nullable
    public static Double waistToHeight(@Nullable final Double waistCm, final double heightCm) {
        if (!isPositive(waistCm) || heightCm <= 0) {
            return null;
        }
        return waistCm / heightCm;
    }

    /**
     * Body fat % by relative fat mass (Woolcott and Bergman, 2018), from the height and the waist
     * circumference, both in centimetres. The constant is 64 for men and 76 for women.
     *
     * @return the body fat %, or null if an input is not more than 0
     */
    @Nullable
    public static Double relativeFatMass(final boolean male, final double heightCm, @Nullable final Double waistCm) {
        if (heightCm <= 0 || !isPositive(waistCm)) {
            return null;
        }
        return (male ? 64d : 76d) - 20d * heightCm / waistCm;
    }

    /**
     * Body Roundness Index (Thomas et al., 2013), from the waist circumference and the height, both
     * in centimetres. The index models the body as an ellipse; a waist larger than pi times the
     * height has no ellipse, so it returns null.
     *
     * @return the index, or null if an input is not more than 0 or the waist is too large
     */
    @Nullable
    public static Double bodyRoundnessIndex(@Nullable final Double waistCm, final double heightCm) {
        if (!isPositive(waistCm) || heightCm <= 0) {
            return null;
        }
        final double ratio = waistCm / (Math.PI * heightCm);
        final double inside = 1 - ratio * ratio;
        if (inside < 0) {
            return null;
        }
        return 364.2 - 365.5 * Math.sqrt(inside);
    }

    /**
     * Weight-Adjusted Waist Index (Park et al., 2018): the waist circumference in centimetres
     * divided by the square root of the weight in kilograms.
     *
     * @return the index in cm/√kg, or null if an input is not more than 0
     */
    @Nullable
    public static Double weightAdjustedWaist(@Nullable final Double waistCm, @Nullable final Double weightKg) {
        if (!isPositive(waistCm) || !isPositive(weightKg)) {
            return null;
        }
        return waistCm / Math.sqrt(weightKg);
    }

    /**
     * Conicity Index (Valdez et al., 1991, 1993): the waist circumference in metres, divided by
     * 0.109 times the square root of the weight in kilograms over the height in metres. A cylinder
     * gives 1.00 and a double cone gives about 1.73.
     *
     * @return the index, or null if an input is not more than 0
     */
    @Nullable
    public static Double conicityIndex(@Nullable final Double waistCm, @Nullable final Double weightKg, final double heightCm) {
        if (!isPositive(waistCm) || !isPositive(weightKg) || heightCm <= 0) {
            return null;
        }
        final double waistM = waistCm / 100d;
        final double heightM = heightCm / 100d;
        return waistM / (0.109 * Math.sqrt(weightKg / heightM));
    }

    /** A calculated body fat % can be impossible for valid inputs, for example a negative value. */
    public static boolean isPlausibleBodyFat(final double percent) {
        return percent > 0 && percent < 100;
    }

    private static boolean isPositive(@Nullable final Double value) {
        return value != null && value > 0;
    }

}
