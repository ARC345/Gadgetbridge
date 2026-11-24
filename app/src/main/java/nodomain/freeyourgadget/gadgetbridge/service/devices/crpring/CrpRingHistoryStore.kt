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
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.CrpRingStepsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.CrpRingStepsSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.slf4j.LoggerFactory

/** Saves a fetch result. Rows with the same device and timestamp are replaced. */
object CrpRingHistoryStore {
    private val LOG = LoggerFactory.getLogger(CrpRingHistoryStore::class.java)

    fun save(device: GBDevice, context: Context, result: CrpRingHistoryResult) {
        LOG.info(
            "Saving {} steps, {} heart rate, {} SpO2, {} HRV, {} stress and {} sleep samples",
            result.steps.size, result.heartRate.size, result.spo2.size, result.hrv.size, result.stress.size, result.sleep.size
        )
        try {
            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                CrpRingStepsSampleProvider(device, session).persistSamples(
                    result.steps.map { s -> CrpRingStepsSample().apply { timestamp = s.timestampMillis; steps = s.value } },
                    context
                )
                GenericHeartRateSampleProvider(device, session).persistSamples(
                    result.heartRate.map { s -> GenericHeartRateSample().apply { timestamp = s.timestampMillis; heartRate = s.value } },
                    context
                )
                GenericSpo2SampleProvider(device, session).persistSamples(
                    result.spo2.map { s -> GenericSpo2Sample().apply { timestamp = s.timestampMillis; spo2 = s.value } },
                    context
                )
                GenericHrvValueSampleProvider(device, session).persistSamples(
                    result.hrv.map { s -> GenericHrvValueSample().apply { timestamp = s.timestampMillis; value = s.value } },
                    context
                )
                GenericStressSampleProvider(device, session).persistSamples(
                    result.stress.map { s -> GenericStressSample().apply { timestamp = s.timestampMillis; stress = s.value } },
                    context
                )
                GenericSleepStageSampleProvider(device, session).persistSamples(
                    result.sleep.map { s ->
                        GenericSleepStageSample().apply {
                            timestamp = s.startMillis
                            duration = s.durationMinutes
                            stage = s.stage
                        }
                    },
                    context
                )
            }
        } catch (e: Exception) {
            LOG.error("Failed to save CRP ring history", e)
        }
    }

    fun saveHeartRate(device: GBDevice, context: Context, sample: GenericHeartRateSample) {
        try {
            GBApplication.acquireDB().use { db ->
                GenericHeartRateSampleProvider(device, db.daoSession).persistSamples(listOf(sample), context)
            }
        } catch (e: Exception) {
            LOG.error("Failed to save CRP ring heart rate", e)
        }
    }
}
