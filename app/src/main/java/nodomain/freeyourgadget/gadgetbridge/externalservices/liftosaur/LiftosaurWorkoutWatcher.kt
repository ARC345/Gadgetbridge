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

import android.service.notification.StatusBarNotification
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.service.devices.crpring.CrpRingCommands
import org.slf4j.LoggerFactory

/**
 * Detects a workout in the Liftosaur Android app from its live workout notification.
 *
 * Liftosaur shows this notification while a workout runs. It is posted when the workout starts and
 * removed when the workout ends. Gadgetbridge already has notification access, so it sees the same
 * events. The start and stop actions are added on top of [onWorkoutStarted] and [onWorkoutStopped].
 *
 * The state is kept in memory. After the app process restarts, the next start event is seen again.
 */
object LiftosaurWorkoutWatcher {
    private val LOG = LoggerFactory.getLogger(LiftosaurWorkoutWatcher::class.java)

    internal const val LIFTOSAUR_PACKAGE = "com.liftosaur.www.twa"

    /** The id Liftosaur uses for its live workout notification. */
    internal const val LIVE_NOTIFICATION_ID = 100

    /** Liftosaur uses one channel per variant, and every variant starts with this prefix. */
    internal const val LIVE_CHANNEL_PREFIX = "liftosaur_live_updates"

    /** Whether a Liftosaur workout starts and ends the band workout. */
    private const val BAND_WORKOUT_PREF = "liftosaur_band_workout_enabled"

    @Volatile
    private var workoutActive = false

    /** Whether a notification is the Liftosaur live workout notification. */
    internal fun isWorkoutNotification(packageName: String?, id: Int, channelId: String?): Boolean =
        packageName == LIFTOSAUR_PACKAGE &&
            id == LIVE_NOTIFICATION_ID &&
            channelId?.startsWith(LIVE_CHANNEL_PREFIX) == true

    /** Called for every posted notification. Acts only on the first post of a workout. */
    @JvmStatic
    fun onPosted(sbn: StatusBarNotification) {
        if (!isWorkoutNotification(sbn.packageName, sbn.id, sbn.notification.channelId)) return
        if (workoutActive) return
        workoutActive = true
        LOG.info("Liftosaur workout started")
        onWorkoutStarted()
    }

    /** Called for every removed notification. Acts only when a workout was active. */
    @JvmStatic
    fun onRemoved(sbn: StatusBarNotification) {
        if (!isWorkoutNotification(sbn.packageName, sbn.id, sbn.notification.channelId)) return
        if (!workoutActive) return
        workoutActive = false
        LOG.info("Liftosaur workout stopped")
        onWorkoutStopped()
    }

    private fun onWorkoutStarted() {
        if (!isBandWorkoutEnabled()) return
        sendToBands(CrpRingCommands.CONFIG_WORKOUT_START)
    }

    private fun onWorkoutStopped() {
        if (!isBandWorkoutEnabled()) return
        sendToBands(CrpRingCommands.CONFIG_WORKOUT_END)
    }

    private fun isBandWorkoutEnabled(): Boolean =
        GBApplication.getPrefs().preferences.getBoolean(BAND_WORKOUT_PREF, true)

    /** Sends [config] to every connected Qore Vox band. */
    private fun sendToBands(config: String) {
        val bands = GBApplication.app().deviceManager.devices.filter {
            it.type == DeviceType.PEBBLE_QORE_VOX && it.isConnected
        }
        if (bands.isEmpty()) {
            LOG.info("No connected Qore Vox band, skipping {}", config)
            return
        }
        for (band in bands) {
            GBApplication.deviceService(band).onSendConfiguration(config)
        }
    }
}
