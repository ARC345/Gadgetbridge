/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.externalservices.liftosaur

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.util.BodyCompositionEstimates
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** A scale reading, with the body fat the scale estimated from it when it has impedance data. */
private data class ScalePoint(val sample: WeightSample, val bodyFatPercent: Float?)

/**
 * Sends the new weight readings of one scale to Liftosaur. Enqueued, debounced per device, by
 * [nodomain.freeyourgadget.gadgetbridge.externalevents.NewDataReceiver] after a device fetch.
 *
 * Readings are sent in time order. A per-device mark records the timestamp of the last reading
 * that the server accepted, or that it refused for good, so a later run starts after it. The first
 * run only looks back [INITIAL_WINDOW_MS], so enabling the feature does not send the whole history.
 *
 * A network or server failure stops the run and keeps the mark, so the next run retries the
 * remaining readings. A rejected API key stops the run and posts a notification.
 */
class LiftosaurWeightSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val LOG = LoggerFactory.getLogger(LiftosaurWeightSyncWorker::class.java)

    override fun doWork(): Result {
        val context = applicationContext
        val tokens = LiftosaurTokenManager(context)
        val apiKey = tokens.getApiToken()
        if (apiKey == null || !tokens.isLoggedIn() || !isAutoSyncEnabled()) {
            return Result.success()
        }

        val address = inputData.getString(INPUT_DEVICE_ADDRESS)
        val gbDevice = address?.let { GBApplication.app().deviceManager.getDeviceByAddress(it) }
        if (gbDevice == null) {
            LOG.warn("LiftosaurWeightSyncWorker: device {} not found", address)
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val since = readMark(gbDevice.address) ?: (now - INITIAL_WINDOW_MS)
        val points = readScalePoints(gbDevice, since, now) ?: return Result.retry()

        var mark = since
        for (point in points) {
            val sample = point.sample
            val kg = sample.weightKg
            if (!kg.isFinite() || kg <= 0f || kg > MAX_WEIGHT_KG) {
                LOG.info("Skipping weight {} kg outside the accepted range", kg)
                mark = sample.timestamp
                continue
            }

            val values = buildList {
                add("weight" to LiftosaurApiClient.formatValue(kg, "kg"))
                point.bodyFatPercent
                    ?.takeIf { it.isFinite() && it in 0f..100f }
                    ?.let { add("bodyfat" to LiftosaurApiClient.formatValue(it, "%")) }
            }

            var failure: LiftosaurResult? = null
            for ((key, value) in values) {
                val result = LiftosaurApiClient.upsertValue(apiKey, key, value, sample.timestamp)
                if (result is LiftosaurResult.Unauthorized || result is LiftosaurResult.Unavailable) {
                    failure = result
                    break
                }
            }

            when (failure) {
                LiftosaurResult.Unauthorized -> {
                    writeMark(gbDevice.address, mark)
                    notifyKeyRejected(context)
                    return Result.failure()
                }

                is LiftosaurResult.Unavailable -> {
                    LOG.warn("Liftosaur is unavailable, retrying later: {}", failure.reason)
                    writeMark(gbDevice.address, mark)
                    return Result.retry()
                }

                else -> mark = sample.timestamp
            }
        }

        writeMark(gbDevice.address, mark)
        return Result.success()
    }

    /** Scale readings of [gbDevice] after [from] and up to [to], in time order, or null on a database error. */
    private fun readScalePoints(gbDevice: GBDevice, from: Long, to: Long): List<ScalePoint>? {
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                val provider = gbDevice.deviceCoordinator.getWeightSampleProvider(gbDevice, db.daoSession)
                    ?: return emptyList()
                // The mark is the timestamp already handled, so the query starts one millisecond after it.
                provider.getAllSamples(from + 1, to).sortedBy { it.timestamp }.map { sample ->
                    ScalePoint(sample, BodyCompositionEstimates.composition(db.daoSession, sample)?.bodyFatPercent)
                }
            }
        } catch (e: Exception) {
            LOG.error("Error reading weight samples for {}", gbDevice.address, e)
            null
        }
    }

    private fun isAutoSyncEnabled(): Boolean =
        GBApplication.getPrefs().preferences.getBoolean(LiftosaurService.autoUploadPrefKey, false)

    private fun readMark(address: String): Long? {
        val value = GBApplication.getPrefs().preferences.getLong(markKey(address), -1L)
        return if (value < 0) null else value
    }

    private fun writeMark(address: String, timestamp: Long) {
        GBApplication.getPrefs().preferences.edit { putLong(markKey(address), timestamp) }
    }

    private fun notifyKeyRejected(context: Context) {
        val notification = NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.liftosaur_sync_failed_notification_title))
            .setContentText(context.getString(R.string.toast_error_invalid_api_key))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        GB.notify(NOTIFICATION_ID, notification, context)
    }

    companion object {
        const val INPUT_DEVICE_ADDRESS = "device_address"
        private const val WORK_NAME_PREFIX = "LiftosaurWeightSyncWorker_"
        private const val NOTIFICATION_ID = 4721
        private const val DEBOUNCE_SECONDS = 10L

        /** Readings older than this are not sent on the first run. */
        private const val INITIAL_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        /** Same upper bound as the Health Connect weight sync. */
        private const val MAX_WEIGHT_KG = 1000f

        private fun markKey(address: String) = "liftosaur_weight_synced_until_$address"

        /**
         * Enqueues a sync for [deviceAddress] when Liftosaur auto-sync is on and the account is
         * logged in. Unique per device (REPLACE), so a burst of new-data broadcasts runs once.
         */
        @JvmStatic
        fun enqueueIfEnabled(context: Context, deviceAddress: String) {
            if (!GBApplication.getPrefs().preferences.getBoolean(LiftosaurService.autoUploadPrefKey, false)) {
                return
            }
            if (!LiftosaurTokenManager(context).isLoggedIn()) {
                return
            }
            val request = OneTimeWorkRequest.Builder(LiftosaurWeightSyncWorker::class.java)
                .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString(INPUT_DEVICE_ADDRESS, deviceAddress).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + deviceAddress,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
