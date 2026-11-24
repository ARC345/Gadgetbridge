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
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import nodomain.freeyourgadget.gadgetbridge.devices.CrpRingStepsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample

/**
 * Read-only provider. It merges the 30-minute step samples, the heart rate samples and the
 * sleep stages into one activity sample for each minute that has data.
 */
class CrpRingActivitySampleProvider(
    private val device: GBDevice,
    private val session: DaoSession,
) : SampleProvider<GenericActivitySample> {

    override fun normalizeType(rawType: Int): ActivityKind = ActivityKind.fromCode(rawType)

    override fun toRawActivityKind(activityKind: ActivityKind): Int = activityKind.code

    override fun normalizeIntensity(rawIntensity: Int): Float = rawIntensity.toFloat()

    override fun getAllActivitySamples(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> {
        val byTimestamp = HashMap<Int, GenericActivitySample>()
        fun sampleAt(timestamp: Int): GenericActivitySample = byTimestamp.getOrPut(timestamp) {
            GenericActivitySample().also {
                it.provider = this
                it.timestamp = timestamp
                // The band was worn when it counted steps or measured the heart rate. Without this,
                // the kind stays NOT_MEASURED and charts such as "Speed zones" skip the sample.
                // The sleep overlay below sets the sleep kinds.
                it.rawKind = ActivityKind.ACTIVITY.code
            }
        }

        val fromMillis = timestampFrom * 1000L
        val toMillis = timestampTo * 1000L
        for (sample in CrpRingStepsSampleProvider(device, session).getAllSamples(fromMillis, toMillis)) {
            sampleAt(toMinute(sample.timestamp)).steps = sample.steps
        }
        for (sample in GenericHeartRateSampleProvider(device, session).getAllSamples(fromMillis, toMillis)) {
            sampleAt(toMinute(sample.timestamp)).heartRate = sample.heartRate
        }

        val sleepProvider = GenericSleepStageSampleProvider(device, session)
        val stages = sleepProvider.getAllSamples(fromMillis, toMillis).toMutableList()
        sleepProvider.getLastSampleBefore(fromMillis)?.let {
            if (it.timestamp + it.duration * 60_000L > fromMillis) stages.add(0, it)
        }
        for (stage in stages) {
            val kind = sleepStageToActivityKind(stage.stage)
            val start = toMinute(stage.timestamp)
            for (timestamp in start until start + stage.duration * 60 step 60) {
                if (timestamp < timestampFrom || timestamp > timestampTo) continue
                val sample = sampleAt(timestamp)
                sample.rawKind = kind.code
                sample.rawIntensity = ActivitySample.NOT_MEASURED
            }
        }

        return byTimestamp.values
            .filter { it.timestamp in timestampFrom..timestampTo }
            .sortedBy { it.timestamp }
            .toMutableList()
    }

    override fun getAllActivitySamplesHighRes(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> =
        getAllActivitySamples(timestampFrom, timestampTo)

    override fun hasHighResData(): Boolean = false

    override fun getActivitySamples(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> =
        getAllActivitySamples(timestampFrom, timestampTo)
            .filter { it.kind == ActivityKind.ACTIVITY }
            .toMutableList()

    override fun addGBActivitySample(activitySample: GenericActivitySample) {
        throw UnsupportedOperationException("Read-only sample provider")
    }

    override fun addGBActivitySamples(activitySamples: List<GenericActivitySample>) {
        throw UnsupportedOperationException("Read-only sample provider")
    }

    override fun createActivitySample(): GenericActivitySample = GenericActivitySample()

    override fun getLatestActivitySample(): GenericActivitySample? = null

    override fun getLatestActivitySample(until: Int): GenericActivitySample? = null

    override fun getFirstActivitySample(): GenericActivitySample? = null

    override fun getFirstActivitySample(after: Int): GenericActivitySample? = null

    private fun toMinute(timestampMillis: Long): Int = ((timestampMillis / 1000L) / 60L * 60L).toInt()

    private fun sleepStageToActivityKind(stage: Int): ActivityKind = when (stage) {
        CrpRingConstants.SLEEP_AWAKE -> ActivityKind.AWAKE_SLEEP
        CrpRingConstants.SLEEP_LIGHT -> ActivityKind.LIGHT_SLEEP
        CrpRingConstants.SLEEP_DEEP -> ActivityKind.DEEP_SLEEP
        CrpRingConstants.SLEEP_REM -> ActivityKind.REM_SLEEP
        else -> ActivityKind.UNKNOWN
    }
}
