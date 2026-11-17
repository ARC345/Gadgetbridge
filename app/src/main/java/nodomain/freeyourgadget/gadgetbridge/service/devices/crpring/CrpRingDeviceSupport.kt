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

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo
import nodomain.freeyourgadget.gadgetbridge.devices.crpring.CrpRingConstants
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.time.ZoneId
import kotlin.random.Random

/** The broadcast needs a Serializable sample; the database entity is not Serializable. */
private data class RealtimeHeartRate(private val time: Long, private val bpm: Int) : HeartRateSample, Serializable {
    override fun getTimestamp(): Long = time
    override fun getHeartRate(): Int = bpm
}

class CrpRingDeviceSupport : AbstractBTLESingleDeviceSupport(LOG) {
    companion object {
        private val LOG = LoggerFactory.getLogger(CrpRingDeviceSupport::class.java)
        private const val FETCH_STEP_TIMEOUT_MILLIS = 5_000L
        private const val MEASUREMENT_TIMEOUT_MILLIS = 60_000L
        private const val AUTH_TIMEOUT_MILLIS = 5_000L
    }

    private val lock = Any()
    private val assembler = CrpRingFrameAssembler()
    private val handler = Handler(Looper.getMainLooper())
    private var fetcher: CrpRingHistoryFetcher? = null
    private var chunkSize = 20
    private val fetchTimeout = Runnable { onFetchStepTimeout() }
    private val measurementTimeout = Runnable { stopHeartRateMeasurement() }
    private val postAuth = CrpRingPostAuth { sendTimeAndUserInfo() }
    private val authTimeout = Runnable {
        LOG.info("No PIN reply, sending time and user info anyway")
        postAuth.onTimeout()
    }

    init {
        addSupportedService(CrpRingConstants.UUID_SERVICE)
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE)
    }

    override fun useAutoConnect(): Boolean = false

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        builder.requestMtu(CrpRingConstants.MTU)
        builder.notify(CrpRingConstants.UUID_CHARACTERISTIC_NOTIFY, true)
        builder.notify(CrpRingConstants.UUID_CHARACTERISTIC_STEPS, true)
        builder.notify(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL, true)
        builder.read(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL)
        write(builder, CrpRingCommands.firmwareVersion())
        // Time and user info follow the PIN reply (see CrpRingPostAuth), as in the vendor app.
        postAuth.reset()
        write(builder, CrpRingCommands.sendPin(getOrCreatePin()))
        builder.setDeviceState(GBDevice.State.INITIALIZED)
        handler.removeCallbacks(authTimeout)
        handler.postDelayed(authTimeout, AUTH_TIMEOUT_MILLIS)
        return builder
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState != BluetoothProfile.STATE_CONNECTED) stopConnectionWork()
        super.onConnectionStateChange(gatt, status, newState)
    }

    /** The link is gone: stop the fetch and the timers, so nothing waits in the write queue. */
    private fun stopConnectionWork() {
        handler.removeCallbacks(fetchTimeout)
        handler.removeCallbacks(authTimeout)
        handler.removeCallbacks(measurementTimeout)
        synchronized(lock) {
            val active = fetcher
            if (active != null) {
                LOG.warn("Connection lost during the history fetch, stopping it")
                active.cancel()
                fetcher = null
                device.unsetBusyTask()
                device.sendDeviceUpdateIntent(context)
            }
            assembler.reset()
        }
    }

    private fun sendTimeAndUserInfo() {
        val builder = createTransactionBuilder("Set time and user info")
        write(builder, CrpRingCommands.setTime(System.currentTimeMillis(), ZoneId.systemDefault()))
        write(builder, userInfoCommand())
        builder.queue()
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
            chunkSize = mtu - 3
            LOG.info("MTU is {}, write chunk size is {}", mtu, chunkSize)
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) return true
        when (characteristic.uuid) {
            CrpRingConstants.UUID_CHARACTERISTIC_NOTIFY -> {
                val message = synchronized(lock) { assembler.add(value, System.currentTimeMillis()) }
                if (message != null) handleMessage(message)
                return true
            }
            CrpRingConstants.UUID_CHARACTERISTIC_STEPS -> {
                LOG.debug("Live steps: {}", GB.hexdump(value))
                return true
            }
            GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL -> {
                CrpRingParsers.firstByte(value)?.let { reportBattery(it) }
                return true
            }
        }
        return false
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ): Boolean {
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) return true
        if (characteristic.uuid == GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL && status == BluetoothGatt.GATT_SUCCESS) {
            CrpRingParsers.firstByte(value)?.let { reportBattery(it) }
            return true
        }
        return false
    }

    override fun onSetTime() {
        send("Set time", CrpRingCommands.setTime(System.currentTimeMillis(), ZoneId.systemDefault()))
    }

    override fun onFindDevice(start: Boolean) {
        send("Find device", CrpRingCommands.findDevice(start))
    }

    override fun onHeartRateTest() {
        handler.removeCallbacks(measurementTimeout)
        send("Start heart rate measurement", CrpRingCommands.measureHeartRate(true))
        handler.postDelayed(measurementTimeout, MEASUREMENT_TIMEOUT_MILLIS)
    }

    override fun onFetchRecordedData(dataTypes: Int) {
        synchronized(lock) {
            if (fetcher != null) {
                LOG.info("History fetch is already running")
                return
            }
            device.setBusyTask(R.string.busy_task_fetch_activity_data, context)
            device.sendDeviceUpdateIntent(context)
            val active = CrpRingHistoryFetcher(System.currentTimeMillis(), ZoneId.systemDefault()) { frame ->
                send("Fetch history", frame)
            }
            fetcher = active
            active.start()
            scheduleFetchTimeout()
        }
    }

    override fun dispose() {
        handler.removeCallbacksAndMessages(null)
        synchronized(lock) {
            if (fetcher != null) {
                fetcher = null
                device.unsetBusyTask()
            }
            assembler.reset()
        }
        super.dispose()
    }

    private fun handleMessage(message: CrpRingMessage) {
        LOG.debug("Received {}", message)
        synchronized(lock) {
            val active = fetcher
            if (active != null && active.handle(message)) {
                onFetchProgress(active)
                return
            }
        }
        when (message.group to message.command) {
            CrpRingConstants.GROUP_SYSTEM to CrpRingConstants.CMD_FIRMWARE_VERSION -> handleFirmware(message.payload)
            CrpRingConstants.GROUP_SYSTEM to CrpRingConstants.CMD_BATTERY ->
                CrpRingParsers.firstByte(message.payload)?.let { reportBattery(it) }
            CrpRingConstants.GROUP_SET to CrpRingConstants.CMD_AUTH -> handleAuth(message.payload)
            CrpRingConstants.GROUP_SET to CrpRingConstants.CMD_MEASURE_HEART_RATE -> handleHeartRate(message.payload)
            else -> LOG.debug("Unhandled message group={} command={}", message.group, message.command)
        }
    }

    private fun handleFirmware(payload: ByteArray) {
        val version = CrpRingParsers.text(payload)
        if (version.isEmpty()) return
        LOG.info("Firmware version: {}", version)
        evaluateGBDeviceEvent(GBDeviceEventVersionInfo().apply { fwVersion = version })
    }

    private fun handleAuth(payload: ByteArray) {
        val result = CrpRingParsers.authResult(payload) ?: return
        if (result.operation != CrpRingConstants.AUTH_SEND) return
        handler.removeCallbacks(authTimeout)
        postAuth.onAuthResult(result.isOk)
        if (result.isOk) {
            LOG.info("The band accepted the PIN")
        } else {
            LOG.warn("The band did not accept the PIN, result {}", result.result)
            GB.toast(context, context.getString(R.string.crpring_pin_rejected), Toast.LENGTH_LONG, GB.WARN)
        }
    }

    private fun handleHeartRate(payload: ByteArray) {
        val bpm = CrpRingParsers.firstByte(payload) ?: return
        if (bpm !in CrpRingConstants.HEART_RATE_RANGE) {
            LOG.debug("Heart rate measurement value {} is not valid", bpm)
            return
        }
        val sample = GenericHeartRateSample().apply {
            timestamp = System.currentTimeMillis()
            heartRate = bpm
        }
        CrpRingHistoryStore.saveHeartRate(device, context, sample)
        val intent = Intent(DeviceService.ACTION_REALTIME_SAMPLES)
            .putExtra(GBDevice.EXTRA_DEVICE, device)
            .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, RealtimeHeartRate(sample.timestamp, bpm))
            .putExtra(DeviceService.EXTRA_TIMESTAMP, sample.timestamp)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        stopHeartRateMeasurement()
    }

    private fun stopHeartRateMeasurement() {
        handler.removeCallbacks(measurementTimeout)
        send("Stop heart rate measurement", CrpRingCommands.measureHeartRate(false))
    }

    private fun reportBattery(level: Int) {
        if (level !in 0..100) return
        evaluateGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
            this.level = level
            state = BatteryState.BATTERY_NORMAL
        })
    }

    /** Call with [lock] held. */
    private fun onFetchProgress(active: CrpRingHistoryFetcher) {
        if (active.isFinished) finishFetch(active) else scheduleFetchTimeout()
    }

    private fun onFetchStepTimeout() {
        synchronized(lock) {
            val active = fetcher ?: return
            LOG.warn("History step timed out")
            active.onTimeout()
            onFetchProgress(active)
        }
    }

    private fun scheduleFetchTimeout() {
        handler.removeCallbacks(fetchTimeout)
        handler.postDelayed(fetchTimeout, FETCH_STEP_TIMEOUT_MILLIS)
    }

    /** Call with [lock] held. */
    private fun finishFetch(active: CrpRingHistoryFetcher) {
        handler.removeCallbacks(fetchTimeout)
        fetcher = null
        if (active.skipped.isNotEmpty()) LOG.warn("Skipped history steps: {}", active.skipped)
        if (active.abortedAfterTimeouts) LOG.warn("The band stopped answering; the fetch ended early")
        CrpRingHistoryStore.save(device, context, active.result)
        device.unsetBusyTask()
        device.sendDeviceUpdateIntent(context)
        GB.signalActivityDataFinish(device)
    }

    private fun getOrCreatePin(): String {
        val prefs = GBApplication.getDeviceSpecificSharedPrefs(device.address)
        val stored = prefs.getString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, null)?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        val pin = (10_000_000 + Random.nextInt(90_000_000)).toString()
        prefs.edit().putString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, pin).apply()
        LOG.info("Created a new PIN for the band")
        return pin
    }

    private fun userInfoCommand(): ByteArray {
        val user = ActivityUser()
        val gender = if (user.gender == ActivityUser.GENDER_FEMALE) CrpRingConstants.GENDER_FEMALE else CrpRingConstants.GENDER_MALE
        return CrpRingCommands.userInfo(user.heightCm, user.weightKg, user.age, gender, user.stepLengthCm)
    }

    private fun write(builder: TransactionBuilder, frame: ByteArray) {
        for (chunk in CrpRingFrame.split(frame, chunkSize)) {
            builder.write(CrpRingConstants.UUID_CHARACTERISTIC_WRITE, *chunk)
        }
    }

    private fun send(name: String, frame: ByteArray) {
        val builder = createTransactionBuilder(name)
        write(builder, frame)
        builder.queue()
    }
}
