/*  Copyright (C) 2026 Arnav Rastogi

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
package nodomain.freeyourgadget.gadgetbridge.devices;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Vo2MaxSample;

/**
 * Read-only provider that estimates VO2 max for devices that do not measure it.
 * <p>
 * For each day it uses the heart rate already stored in the activity samples:
 * - resting heart rate = 10th percentile of that day's valid heart rate values,
 * - maximum heart rate = 208 - 0.7 * age (Tanaka et al., 2001),
 * - VO2 max = 15.3 * HRmax / HRrest (Uth et al., 2004).
 * <p>
 * This is a rough population-level estimate, not a measurement. Days with too few
 * heart rate values are skipped.
 */
public class EstimatedVo2MaxSampleProvider implements Vo2MaxSampleProvider<Vo2MaxSample> {
    private static final int MIN_HR_SAMPLES_PER_DAY = 30;
    private static final int MAX_DAYS_TO_LOOK_BACK = 30;
    private static final long DAY_MILLIS = 24 * 60 * 60 * 1000L;

    private final SampleProvider<? extends ActivitySample> activityProvider;

    public EstimatedVo2MaxSampleProvider(final SampleProvider<? extends ActivitySample> activityProvider) {
        this.activityProvider = activityProvider;
    }

    @NonNull
    @Override
    public List<Vo2MaxSample> getAllSamples(final long timestampFrom, final long timestampTo) {
        final List<Vo2MaxSample> samples = new ArrayList<>();
        for (long day = startOfDay(timestampFrom); day <= timestampTo; day += DAY_MILLIS) {
            final Vo2MaxSample sample = estimateForDay(day);
            if (sample != null && sample.getTimestamp() >= timestampFrom && sample.getTimestamp() <= timestampTo) {
                samples.add(sample);
            }
        }
        return samples;
    }

    @Nullable
    private Vo2MaxSample estimateForDay(final long dayStartMillis) {
        final int from = (int) (dayStartMillis / 1000);
        final int to = (int) ((dayStartMillis + DAY_MILLIS) / 1000) - 1;
        final List<? extends ActivitySample> activity = activityProvider.getAllActivitySamples(from, to);

        final HeartRateUtils heartRateUtils = HeartRateUtils.getInstance();
        final List<Integer> heartRates = new ArrayList<>();
        long lastTimestamp = 0;
        for (final ActivitySample sample : activity) {
            if (heartRateUtils.isValidHeartRateValue(sample.getHeartRate())) {
                heartRates.add(sample.getHeartRate());
                lastTimestamp = Math.max(lastTimestamp, sample.getTimestamp() * 1000L);
            }
        }
        if (heartRates.size() < MIN_HR_SAMPLES_PER_DAY) {
            return null;
        }

        Collections.sort(heartRates);
        final int restingHeartRate = heartRates.get(heartRates.size() / 10);
        final int age = new ActivityUser().getAge();
        final float maxHeartRate = 208 - 0.7f * age;
        if (restingHeartRate <= 0 || maxHeartRate <= restingHeartRate) {
            return null;
        }

        final float vo2Max = 15.3f * maxHeartRate / restingHeartRate;
        return new EstimatedVo2MaxSample(lastTimestamp, vo2Max);
    }

    @Nullable
    @Override
    public Vo2MaxSample getLatestSample(final Vo2MaxSample.Type type, final long until) {
        for (int i = 0; i < MAX_DAYS_TO_LOOK_BACK; i++) {
            final Vo2MaxSample sample = estimateForDay(startOfDay(until) - i * DAY_MILLIS);
            if (sample != null && sample.getTimestamp() <= until) {
                return sample;
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Vo2MaxSample getLatestSample() {
        return getLatestSample(Vo2MaxSample.Type.ANY, System.currentTimeMillis());
    }

    @Nullable
    @Override
    public Vo2MaxSample getFirstSample() {
        final ActivitySample first = activityProvider.getFirstActivitySample();
        if (first == null) {
            return null;
        }
        final long firstDay = startOfDay(first.getTimestamp() * 1000L);
        final long today = startOfDay(System.currentTimeMillis());
        for (long day = firstDay; day <= today; day += DAY_MILLIS) {
            final Vo2MaxSample sample = estimateForDay(day);
            if (sample != null) {
                return sample;
            }
        }
        return null;
    }

    @Override
    public void addSample(final Vo2MaxSample timeSample) {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @Override
    public void addSamples(final List<Vo2MaxSample> timeSamples) {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @NonNull
    @Override
    public Vo2MaxSample createSample() {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    private static long startOfDay(final long timestampMillis) {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampMillis);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static class EstimatedVo2MaxSample implements Vo2MaxSample {
        private final long timestamp;
        private final float value;

        EstimatedVo2MaxSample(final long timestamp, final float value) {
            this.timestamp = timestamp;
            this.value = value;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public Type getType() {
            return Type.ANY;
        }

        @Override
        public float getValue() {
            return value;
        }
    }
}
