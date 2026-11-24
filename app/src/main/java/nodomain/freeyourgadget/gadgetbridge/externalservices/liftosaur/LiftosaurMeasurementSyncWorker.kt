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
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.database.repository.BodyMeasurementRepository
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

/**
 * Sends one saved body measurement entry to Liftosaur. Enqueued by the body measurement editor after
 * each save, so a new or edited entry is sent again.
 *
 * Each value goes to its Liftosaur key, dated with the entry timestamp. A value the server already
 * holds at that time is replaced. Values with no Liftosaur key are skipped.
 *
 * Deleted values and entries are not removed from Liftosaur.
 */
class LiftosaurMeasurementSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val LOG = LoggerFactory.getLogger(LiftosaurMeasurementSyncWorker::class.java)

    override fun doWork(): Result {
        val context = applicationContext
        val tokens = LiftosaurTokenManager(context)
        val apiKey = tokens.getApiToken()
        if (apiKey == null || !tokens.isLoggedIn() || !isAutoSyncEnabled()) {
            return Result.success()
        }

        val entryId = inputData.getLong(INPUT_ENTRY_ID, -1L)
        if (entryId < 0) {
            return Result.success()
        }

        val entry = try {
            GBApplication.acquireDB().use { db -> BodyMeasurementRepository(db.daoSession).entry(entryId) }
        } catch (e: Exception) {
            LOG.error("Error reading body measurement entry {}", entryId, e)
            return Result.retry()
        }
        if (entry == null) {
            LOG.info("Body measurement entry {} no longer exists, nothing to send", entryId)
            return Result.success()
        }

        for ((code, stored) in entry.values) {
            val type = BodyMeasurementType.fromCode(code) ?: continue
            val key = LiftosaurMeasurementMapping.keyFor(type) ?: continue
            val value = LiftosaurMeasurementMapping.formatValue(type, stored) ?: continue

            when (val result = LiftosaurApiClient.upsertValue(apiKey, key, value, entry.timestamp)) {
                LiftosaurResult.Stored, LiftosaurResult.AlreadyStored -> Unit

                is LiftosaurResult.Rejected ->
                    LOG.warn("Liftosaur refused {} for entry {}: {}", key, entryId, result.reason)

                LiftosaurResult.Unauthorized -> {
                    notifyKeyRejected(context)
                    return Result.failure()
                }

                is LiftosaurResult.Unavailable -> {
                    LOG.warn("Liftosaur is unavailable, retrying entry {} later: {}", entryId, result.reason)
                    return Result.retry()
                }
            }
        }
        return Result.success()
    }

    private fun isAutoSyncEnabled(): Boolean =
        GBApplication.getPrefs().preferences.getBoolean(LiftosaurService.autoUploadPrefKey, false)

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
        const val INPUT_ENTRY_ID = "entry_id"
        private const val WORK_NAME_PREFIX = "LiftosaurMeasurementSyncWorker_"
        private const val NOTIFICATION_ID = 4722

        /**
         * Enqueues a sync for the saved entry [entryId], when Liftosaur auto-sync is on and the account
         * is logged in. Unique per entry (REPLACE), so a second save replaces a pending run.
         */
        @JvmStatic
        fun enqueueIfEnabled(context: Context, entryId: Long) {
            if (!GBApplication.getPrefs().preferences.getBoolean(LiftosaurService.autoUploadPrefKey, false)) {
                return
            }
            if (!LiftosaurTokenManager(context).isLoggedIn()) {
                return
            }
            val request = OneTimeWorkRequest.Builder(LiftosaurMeasurementSyncWorker::class.java)
                .setInputData(Data.Builder().putLong(INPUT_ENTRY_ID, entryId).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + entryId,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
