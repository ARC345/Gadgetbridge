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
package nodomain.freeyourgadget.gadgetbridge.devices.aicare

import android.util.SparseArray
import android.widget.Toast
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.GenericWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.scannable.ScannableDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * AICare (AILink) weight scales that only broadcast their readings, such as the Fitelo smart
 * scale. The scale cannot be connected: Gadgetbridge scans for it in the background and saves
 * one weight sample each time a reading becomes stable. See [AicareBroadcastScaleProtocol].
 *
 * The scales seen so far do not measure body impedance, so only weight (and BMI, derived from
 * the user's height) is available.
 */
class AicareBroadcastScaleCoordinator : AbstractBLEDeviceCoordinator() {
    override fun supports(candidate: GBDeviceCandidate): Boolean {
        val manufacturerData = candidate.manufacturerSpecificData
        for (i in 0 until manufacturerData.size()) {
            if (AicareBroadcastScaleProtocol.parse(manufacturerData.keyAt(i), manufacturerData.valueAt(i), candidate.macAddress) != null) {
                return true
            }
        }
        return false
    }

    override fun isConnectable(): Boolean {
        return false
    }

    override fun receivesDataFromAdvertisements(): Boolean {
        return true
    }

    override fun onAdvertisement(device: GBDevice, manufacturerData: SparseArray<ByteArray>): Boolean {
        for (i in 0 until manufacturerData.size()) {
            val reading = AicareBroadcastScaleProtocol.parse(manufacturerData.keyAt(i), manufacturerData.valueAt(i), device.address)
                ?: continue
            if (!reading.stable) {
                return true
            }

            val now = System.currentTimeMillis()
            val previous = lastHandled[device.address]
            if (previous != null &&
                previous.counter == reading.counter &&
                previous.weightKg == reading.weightKg &&
                now - previous.timestamp < DUPLICATE_WINDOW_MILLIS
            ) {
                // the scale repeats a stable reading for 15 seconds or more
                return true
            }
            lastHandled[device.address] = HandledReading(reading.counter, reading.weightKg, now)

            if (isFromSomeoneElse(device, reading.weightKg, now)) {
                LOG.info("Ignoring {} kg from {}, too far from the last weight", reading.weightKg, device.address)
                GB.toast(GBApplication.getContext().getString(R.string.aicare_scale_reading_ignored, reading.weightKg), Toast.LENGTH_LONG, GB.INFO)
            } else if (!saveReading(device, reading, now)) {
                // try again with the next repeat of the reading
                lastHandled.remove(device.address)
            }
            // stay in the scan filters, so that a second weigh-in right after this one is not missed
            return true
        }
        return true
    }

    /**
     * True when the user limited the readings to those near their last weight, and this one is
     * not. Without a recent reading to compare with, every reading is accepted.
     */
    private fun isFromSomeoneElse(device: GBDevice, weightKg: Float, now: Long): Boolean {
        val toleranceKg = GBApplication.getDeviceSpecificSharedPrefs(device.address)
            .getString(PREF_WEIGHT_TOLERANCE_KG, DEFAULT_WEIGHT_TOLERANCE_KG)
            ?.toFloatOrNull() ?: return false
        if (toleranceKg <= 0f) {
            return false
        }
        val latest = try {
            GBApplication.acquireDB().use { handler ->
                GenericWeightSampleProvider(device, handler.daoSession).latestSample
            }
        } catch (e: Exception) {
            LOG.error("Error reading the last weight sample", e)
            return false
        } ?: return false
        if (now - latest.timestamp > REFERENCE_MAX_AGE_MILLIS) {
            return false
        }
        return abs(weightKg - latest.weightKg) > toleranceKg
    }

    private fun saveReading(device: GBDevice, reading: AicareBroadcastScaleProtocol.Reading, timestamp: Long): Boolean {
        LOG.debug("Stable reading from {}: {}", device.address, reading)
        try {
            GBApplication.acquireDB().use { handler ->
                val sample = GenericWeightSample().apply {
                    this.timestamp = timestamp
                    weightKg = reading.weightKg
                    impedanceOhm = reading.impedanceOhm
                }
                GenericWeightSampleProvider(device, handler.daoSession).persistSamples(sample, GBApplication.getContext())
            }
        } catch (e: Exception) {
            LOG.error("Error saving weight sample", e)
            return false
        }
        GB.signalActivityDataFinish(device)
        GB.toast(GBApplication.getContext().getString(R.string.weight_kg, reading.weightKg), Toast.LENGTH_SHORT, GB.INFO)
        return true
    }

    override fun getManufacturer(): String {
        // The BLE module and protocol are AICare's; retail units are rebranded by other vendors.
        return "AICare"
    }

    override fun getBatteryCount(device: GBDevice): Int {
        return 0
    }

    override fun getBondingStyle(): Int {
        return BONDING_STYLE_NONE
    }

    override fun getWeightSampleProvider(device: GBDevice, session: DaoSession): TimeSampleProvider<out WeightSample?> {
        return GenericWeightSampleProvider(device, session)
    }

    override fun supportsWeightMeasurement(device: GBDevice): Boolean {
        return true
    }

    override fun supportsCharts(device: GBDevice): Boolean {
        return true
    }

    override fun createDeviceSupport(device: GBDevice): DeviceSupport {
        return ScannableDeviceSupport()
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_aicare_broadcast_scale
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_miscale
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.SCALE
    }

    override fun getDeviceSpecificSettings(device: GBDevice): DeviceSpecificSettings {
        val settings = DeviceSpecificSettings()
        settings.addRootScreen(R.xml.devicesettings_aicare_broadcast_scale)
        return settings
    }

    private data class HandledReading(val counter: Int, val weightKg: Float, val timestamp: Long)

    companion object {
        private val LOG = LoggerFactory.getLogger(AicareBroadcastScaleCoordinator::class.java)

        private const val PREF_WEIGHT_TOLERANCE_KG = "aicare_scale_weight_tolerance_kg"
        private const val DEFAULT_WEIGHT_TOLERANCE_KG = "5"

        private const val DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1000L

        /** An older last weight is no reference, the user's weight may have changed since. */
        private const val REFERENCE_MAX_AGE_MILLIS = 30 * 24 * 60 * 60 * 1000L

        private val lastHandled = ConcurrentHashMap<String, HandledReading>()
    }
}
