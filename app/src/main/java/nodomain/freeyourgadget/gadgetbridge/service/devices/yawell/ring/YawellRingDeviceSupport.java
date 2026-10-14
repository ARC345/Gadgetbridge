/*  Copyright (C) 2024-2026 Arjan Schrijver, Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.yawell.ring;

import static nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils.formatIso8601;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.ColmiHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.yawell.ring.YawellRingLiveActivityContext;
import nodomain.freeyourgadget.gadgetbridge.devices.yawell.ring.YawellRingConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.yawell.ring.YawellRingPacketHandler;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.export.FitExporter;
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationProviderType;
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationService;
import nodomain.freeyourgadget.gadgetbridge.externalevents.opentracks.OpenTracksController;
import nodomain.freeyourgadget.gadgetbridge.entities.ColmiHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser;
import nodomain.freeyourgadget.gadgetbridge.model.Alarm;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.model.CallSpec;
import nodomain.freeyourgadget.gadgetbridge.model.DistanceUnit;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions;
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.IntentListener;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.btle.profiles.deviceinfo.DeviceInfoProfile;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

public class YawellRingDeviceSupport extends AbstractBTLESingleDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(YawellRingDeviceSupport.class);
    private final Handler backgroundTasksHandler = new Handler(Looper.getMainLooper());

    private final DeviceInfoProfile<YawellRingDeviceSupport> deviceInfoProfile;
    private String cachedFirmwareVersion = null;

    private int daysAgo;
    private int packetsTotalNr;
    private Calendar syncingDay;

    private int bigDataPacketSize;
    private ByteBuffer bigDataPacket;

    private static final int LIVE_ACTIVITY_BUFFER_INTERVAL = 2000;
    private final YawellRingLiveActivityContext liveActivityContext = new YawellRingLiveActivityContext();

    // Phone-started workout in progress (CMD_PHONE_SPORT / CMD_PHONE_SPORT_NOTIFY)
    private boolean workoutActive = false;
    private byte workoutSport = 0;
    private long workoutStartMillis = 0;
    private int workoutElapsedSeconds = 0;
    private int workoutLastHeartRate = 0;
    private long workoutStoppedMillis = 0;
    private final List<Integer> workoutHeartRates = new ArrayList<>();
    // Per-second track of the workout: heart rate from the band, location from the phone's GPS
    private static final long GPS_FIX_MAX_AGE_MILLIS = 10_000;
    private final List<ActivityPoint> workoutTrack = new ArrayList<>();
    private boolean workoutUsesGps = false;
    private boolean workoutUsesOpenTracks = false;
    private Location workoutLastLocation = null;
    private long workoutLastLocationMillis = 0;

    public YawellRingDeviceSupport() {
        super(LOG);
        addSupportedService(YawellRingConstants.CHARACTERISTIC_SERVICE_V1);
        addSupportedService(YawellRingConstants.CHARACTERISTIC_SERVICE_V2);
        addSupportedService(GattService.SERVICE_DEVICE_INFORMATION.uuid);

        IntentListener mListener = intent -> {
            String action = intent.getAction();
            if (DeviceInfoProfile.ACTION_DEVICE_INFO.equals(action)) {
                handleDeviceInfo(intent.getParcelableExtra(DeviceInfoProfile.EXTRA_DEVICE_INFO));
            }
        };

        deviceInfoProfile = new DeviceInfoProfile<>(this);
        deviceInfoProfile.addListener(mListener);
        addSupportedProfile(deviceInfoProfile);
    }

    @Override
    public void dispose() {
        synchronized (ConnectionMonitor) {
            backgroundTasksHandler.removeCallbacksAndMessages(null);

            LOG.info("Stopping live activity timeout scheduler");
            if (liveActivityContext.getRealtimeStepsScheduler() != null) {
                liveActivityContext.getRealtimeStepsScheduler().shutdown();
                liveActivityContext.setRealtimeStepsScheduler(null);
            }

            super.dispose();
        }
    }

    @Override
    public void setContext(GBDevice gbDevice, BluetoothAdapter btAdapter, Context context) {
        if (gbDevice.getFirmwareVersion() != null) {
            setCachedFirmwareVersion(gbDevice.getFirmwareVersion());
        }
        super.setContext(gbDevice, btAdapter, context);
    }

    public String getCachedFirmwareVersion() {
        return this.cachedFirmwareVersion;
    }

    public void setCachedFirmwareVersion(String version) {
        this.cachedFirmwareVersion = version;
    }

    private void handleDeviceInfo(DeviceInfo info) {
        LOG.debug("Device info: {}", info);

        GBDeviceEventVersionInfo versionCmd = new GBDeviceEventVersionInfo();
        versionCmd.hwVersion = info.getHardwareRevision();
        versionCmd.fwVersion = info.getFirmwareRevision();
        handleGBDeviceEvent(versionCmd);
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        builder.setDeviceState(GBDevice.State.INITIALIZING);

        // A workout's state only lives in this session. After a reconnect or app restart
        // the screen must not offer Pause/Stop for a workout this session never started.
        if (!workoutActive) {
            publishWorkoutIdle();
        }

        if (getDevice().getFirmwareVersion() == null) {
            getDevice().setFirmwareVersion(getCachedFirmwareVersion() != null ? getCachedFirmwareVersion() : "N/A");
        }
        deviceInfoProfile.requestDeviceInfo(builder);

        builder.setDeviceState(GBDevice.State.INITIALIZED);

        builder.notify(YawellRingConstants.CHARACTERISTIC_NOTIFY_V1, true);
        builder.notify(YawellRingConstants.CHARACTERISTIC_NOTIFY_V2, true);

        // Delay initialization with 2 seconds to give the ring time to settle
        backgroundTasksHandler.removeCallbacksAndMessages(null);
        backgroundTasksHandler.postDelayed(this::postConnectInitialization, 2000);

        return builder;
    }

    private void postConnectInitialization() {
        setPhoneName();
        setDateTime();
        setUserPreferences();
        requestBatteryInfo();
        requestSettingsFromRing();
        if (getDevice().getDeviceCoordinator().getAlarmSlotCount(getDevice()) > 0) {
            fetchAlarms();
        }
    }

    @Override
    public boolean onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) {
            return true;
        }

        UUID characteristicUUID = characteristic.getUuid();

        LOG.debug("Characteristic {} changed, value: {}", characteristicUUID, StringUtils.bytesToHex(value));

        if (characteristicUUID.equals(YawellRingConstants.CHARACTERISTIC_NOTIFY_V1)) {
            switch (value[0]) {
                case YawellRingConstants.CMD_SET_DATE_TIME:
                    LOG.info("Received set date/time response: {}", StringUtils.bytesToHex(value));
                    break;
                case YawellRingConstants.CMD_BATTERY:
                    int levelResponse = value[1];
                    boolean charging = value[2] == 1;
                    LOG.info("Received battery level response: {}% (charging: {})", levelResponse, charging);
                    GBDeviceEventBatteryInfo batteryEvent = createDeviceBatteryInfoEvent(levelResponse, charging);
                    evaluateGBDeviceEvent(batteryEvent);
                    break;
                case YawellRingConstants.CMD_PHONE_NAME:
                    LOG.info("Received phone name response: {}", StringUtils.bytesToHex(value));
                    break;
                case YawellRingConstants.CMD_PREFERENCES:
                    LOG.info("Received user preferences response: {}", StringUtils.bytesToHex(value));
                    break;
                case YawellRingConstants.CMD_SYNC_HEART_RATE:
                    LOG.info("Received HR history sync packet: {}", StringUtils.bytesToHex(value));
                    int hrPacketNr = value[1] & 0xff;
                    if (hrPacketNr == 0xff) {
                        LOG.info("Empty HR history, sync aborted");
                        getDevice().unsetBusyTask();
                        getDevice().sendDeviceUpdateIntent(getContext());
                    } else if (hrPacketNr == 0) {
                        packetsTotalNr = value[2];
                        LOG.info("HR history packet {} out of total {}", hrPacketNr, packetsTotalNr);
                    } else {
                        LOG.info("HR history packet {} out of total {} (data for {}:00-{}:00)", hrPacketNr, packetsTotalNr, hrPacketNr-1, hrPacketNr);
                        Calendar sampleCal = (Calendar) syncingDay.clone();
                        int startValue = hrPacketNr == 1 ? 6 : 2;  // packet 1 contains the sync-from timestamp in bytes 2-5
                        int minutesInPreviousPackets = 0;
                        if (hrPacketNr == 1) {
                            int timestamp = BLETypeConversions.toUint32(value[2], value[3], value[4], value[5]);
                            Date timestampDate = DateTimeUtils.parseTimeStamp(timestamp);
                            LOG.info("Receiving HR history sequence with timestamp {}", DateTimeUtils.formatIso8601UTC(timestampDate));
                        } else {
                            minutesInPreviousPackets = 9 * 5;  // packet 1
                            minutesInPreviousPackets += (hrPacketNr - 2) * 13 * 5;
                        }
                        final List<ColmiHeartRateSample> heartRateSamples = new ArrayList<>(value.length);
                        final Calendar now = Calendar.getInstance();
                        for (int i = startValue; i < value.length - 1; i++) {
                            final int heartRate = value[i] & 0xFF;
                            if (heartRate != 0x00) {
                                // Determine time of day
                                int minuteOfDay = minutesInPreviousPackets + (i - startValue) * 5;
                                sampleCal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
                                sampleCal.set(Calendar.MINUTE, minuteOfDay % 60);
                                sampleCal.set(Calendar.SECOND, 0);
                                if (sampleCal.after(now)) {
                                    // The ring's own per-time-of-day history buffer is not
                                    // reliably zeroed for a slot it has not measured yet -- it
                                    // can hold a leftover non-zero byte from that same clock
                                    // position on a PREVIOUS day, which would otherwise be
                                    // recorded here as if it were a real measurement from the
                                    // future. sampleCal carries the calendar date this sync
                                    // request targeted (syncingDay), with only the time of day
                                    // overwritten above, so this comparison is correct for a
                                    // past day too: sampleCal is then always before "now"
                                    // regardless of the hour, and this branch never fires.
                                    //
                                    // The independently reverse-engineered reference client
                                    // (tahnok/colmi_r02_client, hr.py) works around the same
                                    // firmware behaviour by explicitly zeroing every slot after
                                    // "now" when syncing today. Do the same here, per-sample.
                                    LOG.info("Value {} is {} bpm, but time of day {} is in the future -- skipping, likely stale data from the same slot on a previous day", i, heartRate, formatIso8601(sampleCal));
                                    continue;
                                }
                                LOG.info("Value {} is {} bpm, time of day is {}", i, heartRate, formatIso8601(sampleCal));
                                ColmiHeartRateSample gbSample = new ColmiHeartRateSample();
                                gbSample.setTimestamp(sampleCal.getTimeInMillis());
                                gbSample.setHeartRate(heartRate);
                                heartRateSamples.add(gbSample);
                            }
                        }
                        try (DBHandler db = GBApplication.acquireDB()) {
                            ColmiHeartRateSampleProvider sampleProvider = new ColmiHeartRateSampleProvider(getDevice(), db.getDaoSession());
                            sampleProvider.persistSamples(heartRateSamples, getContext());
                        } catch (Exception e) {
                            LOG.error("Error acquiring database for recording heart rate samples", e);
                        }

                        if (hrPacketNr == packetsTotalNr - 1) {
                            getDevice().unsetBusyTask();
                            getDevice().sendDeviceUpdateIntent(getContext());
                        }
                    }
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 7) {
                            daysAgo++;
                            fetchHistoryHR();
                        } else {
                            fetchHistoryStress();
                        }
                    }
                    break;
                case YawellRingConstants.CMD_AUTO_HR_PREF:
                    YawellRingPacketHandler.hrIntervalSettings(this, value);
                    break;
                case YawellRingConstants.CMD_GOALS:
                    YawellRingPacketHandler.goalsSettings(value);
                    break;
                case YawellRingConstants.CMD_AUTO_SPO2_PREF:
                    YawellRingPacketHandler.spo2Settings(this, value);
                    break;
                case YawellRingConstants.CMD_PACKET_SIZE:
                    LOG.info("Received packet size indicator: {} bytes", value[1] & 0xff);
                    break;
                case YawellRingConstants.CMD_AUTO_STRESS_PREF:
                    YawellRingPacketHandler.stressSettings(this, value);
                    break;
                case YawellRingConstants.CMD_AUTO_HRV_PREF:
                    YawellRingPacketHandler.hrvSettings(this, value);
                    break;
                case YawellRingConstants.CMD_AUTO_TEMP_PREF:
                    if (value[1] == 0x03) {  // so far always observed to be 0x03
                        YawellRingPacketHandler.tempSettings(this, value);
                    }
                    break;
                case YawellRingConstants.CMD_SYNC_STRESS:
                    YawellRingPacketHandler.historicalStress(getDevice(), getContext(), value);
                    if (!getDevice().isBusy()) {
                        fetchHistorySpo2();
                    }
                    break;
                case YawellRingConstants.CMD_SYNC_ACTIVITY:
                    YawellRingPacketHandler.historicalActivity(getDevice(), getContext(), value);
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 7) {
                            daysAgo++;
                            fetchHistoryActivity();
                        } else {
                            daysAgo = 0;
                            fetchHistoryHR();
                        }
                    }
                    break;
                case YawellRingConstants.CMD_SYNC_HRV:
                    getDevice().setBusyTask(R.string.busy_task_fetch_hrv_data, getContext());
                    YawellRingPacketHandler.historicalHRV(getDevice(), getContext(), value, daysAgo);
                    if (!getDevice().isBusy()) {
                        if (daysAgo < 6) {
                            daysAgo++;
                            fetchHistoryHRV();
                        } else {
                            if (getDevice().getDeviceCoordinator().supportsTemperatureMeasurement(gbDevice)) {
                                fetchTemperature();
                            } else {
                                fetchRecordedDataFinished();
                            }
                        }
                    }
                    break;
                case YawellRingConstants.CMD_FIND_DEVICE:
                    LOG.info("Received find device response: {}", StringUtils.bytesToHex(value));
                    break;
                case YawellRingConstants.CMD_MANUAL_HEART_RATE:
                    YawellRingPacketHandler.liveHeartRate(getDevice(), getContext(), value);
                    break;
                case YawellRingConstants.CMD_REALTIME_HEART_RATE:
                    YawellRingPacketHandler.realtimeHeartRate(getDevice(), getContext(), liveActivityContext, value);

                    // The realtime measurement has a timeout of 60 seconds.
                    // Send a "continue" command every 30 packets (= every 30 seconds)
                    liveActivityContext.setRealtimeHrmPacketCount((liveActivityContext.getRealtimeHrmPacketCount()+1) % 30);

                    if(liveActivityContext.isRealtimeHrm() && liveActivityContext.getRealtimeHrmPacketCount() == 0) {
                        byte[] measureHeartRatePacket = buildPacket(new byte[]{YawellRingConstants.CMD_REALTIME_HEART_RATE, 0x03});
                        LOG.info("Continue realtime HRM request sent: {}", StringUtils.bytesToHex(measureHeartRatePacket));
                        sendWrite("continueRealtimeHRMRequest", measureHeartRatePacket);
                    }

                    break;
                case YawellRingConstants.CMD_PHONE_SPORT:
                    LOG.info("Received phone sport response: {}", StringUtils.bytesToHex(value));
                    break;
                case YawellRingConstants.CMD_PHONE_SPORT_NOTIFY:
                    // [0x78, sport, state (1 running, 2 paused), elapsed seconds (2 bytes BE), heart rate, ...]
                    final int sportState = value[2] & 0xff;
                    final int elapsedSeconds = ((value[3] & 0xff) << 8) | (value[4] & 0xff);
                    final int sportHeartRate = value[5] & 0xff;
                    LOG.debug("Workout update: sport {}, state {}, {} s, {} bpm", value[1] & 0xff, sportState, elapsedSeconds, sportHeartRate);
                    if (!workoutActive && (sportState == 1 || sportState == 2)
                            && System.currentTimeMillis() - workoutStoppedMillis > 5000) {
                        // The band is still running a workout this session did not start (e.g. after
                        // an app restart). Adopt it so it can be paused, stopped and saved.
                        LOG.info("Adopting workout already running on the band: sport {}, {} s", value[1] & 0xff, elapsedSeconds);
                        workoutSport = value[1];
                        workoutStartMillis = System.currentTimeMillis() - elapsedSeconds * 1000L;
                        workoutElapsedSeconds = elapsedSeconds;
                        workoutLastHeartRate = 0;
                        workoutHeartRates.clear();
                        workoutActive = true;
                        // OpenTracks, if used, kept recording across the restart; only resume GPS here
                        beginWorkoutTracking(getDevicePrefs(), false);
                    }
                    if (workoutActive) {
                        workoutElapsedSeconds = Math.max(workoutElapsedSeconds, elapsedSeconds);
                        if (sportState == 1 && sportHeartRate > 0) {
                            workoutHeartRates.add(sportHeartRate);
                            workoutLastHeartRate = sportHeartRate;
                        }
                        if (sportState == 1) {
                            recordTrackPoint(sportHeartRate);
                        }
                        publishWorkout(sportState == 2);
                    }
                    break;
                case YawellRingConstants.CMD_NOTIFICATION:
                    switch (value[1]) {
                        case YawellRingConstants.NOTIFICATION_NEW_HR_DATA:
                            LOG.info("Received notification from ring that new HR data is available to sync");
                            break;
                        case YawellRingConstants.NOTIFICATION_NEW_SPO2_DATA:
                            LOG.info("Received notification from ring that new SpO2 data is available to sync");
                            break;
                        case YawellRingConstants.NOTIFICATION_NEW_STEPS_DATA:
                            LOG.info("Received notification from ring that new steps data is available to sync");
                            break;
                        case YawellRingConstants.NOTIFICATION_BATTERY_LEVEL:
                            int levelNotif = value[2];
                            charging = value[3] == 1;
                            LOG.info("Received battery level notification: {}% (charging: {})", levelNotif, charging);
                            GBDeviceEventBatteryInfo batteryNotifEvent = createDeviceBatteryInfoEvent(levelNotif, charging);
                            evaluateGBDeviceEvent(batteryNotifEvent);
                            break;
                        case YawellRingConstants.NOTIFICATION_LIVE_ACTIVITY:
                            YawellRingPacketHandler.liveActivity(getDevice(), getContext(), liveActivityContext, value);
                            break;
                        default:
                            LOG.info("Received unrecognized notification: {}", StringUtils.bytesToHex(value));
                            break;
                    }
                    break;
                default:
                    LOG.info("Received unrecognized packet: {}", StringUtils.bytesToHex(value));
                    break;
            }
        }
        if (characteristicUUID.equals(YawellRingConstants.CHARACTERISTIC_NOTIFY_V2)) {
            // Big data responses can arrive in multiple packets that need to be concatenated
            if (bigDataPacket != null) {
                LOG.debug("Received {} bytes on big data characteristic while waiting for follow-up data", value.length);
                bigDataPacket.rewind();
                ByteBuffer concatenated = ByteBuffer
                        .allocate(bigDataPacket.limit() + value.length)
                        .put(bigDataPacket)
                        .put(value);
                bigDataPacket = concatenated;
                if (bigDataPacket.limit() < bigDataPacketSize + 6) {
                    // If the received data is smaller than the expected packet size (+ 6 bytes header),
                    // wait for the next packet and append it
                    LOG.debug("Big data packet is not complete yet, got {} bytes while expecting {}+6. Waiting for more...", bigDataPacket.limit(), bigDataPacketSize);
                    return true;
                } else {
                    value = bigDataPacket.array();
                    bigDataPacket = null;
                    LOG.debug("Big data packet complete, got {} bytes while expecting {}+6", value.length, bigDataPacketSize);
                }
            }
            switch (value[0]) {
                case YawellRingConstants.CMD_BIG_DATA_V2:
                    int packetLength = BLETypeConversions.toUint16(value[2], value[3]);
                    if (value.length < packetLength + 6) {
                        // If the received packet is smaller than the expected packet size (+ 6 bytes header),
                        // wait for the next packet and append it
                        LOG.debug("Big data packet is not complete yet, got {} bytes while expecting {}+6. Waiting for more...", value.length, packetLength);
                        bigDataPacketSize = packetLength;
                        bigDataPacket = ByteBuffer.wrap(value);
                        return true;
                    }
                    switch (value[1]) {
                        case YawellRingConstants.BIG_DATA_TYPE_TEMPERATURE:
                            YawellRingPacketHandler.historicalTemperature(getDevice(), getContext(), value);
                            fetchRecordedDataFinished();
                            break;
                        case YawellRingConstants.BIG_DATA_TYPE_SLEEP:
                            YawellRingPacketHandler.historicalSleep(getDevice(), getContext(), value);

                            daysAgo = 0;
                            fetchHistoryHRV();

                            // Signal history sync finished at this point, since older firmwares
                            // will not send anything back after requesting HRV history
                            fetchRecordedDataFinished();
                            break;
                        case YawellRingConstants.BIG_DATA_TYPE_SPO2:
                            YawellRingPacketHandler.historicalSpo2(getDevice(), getContext(), value);
                            fetchHistorySleep();
                            break;
                        case YawellRingConstants.BIG_DATA_TYPE_ALARM:
                            YawellRingPacketHandler.alarmsSettings(getDevice(), getContext(), value);
                            break;
                        default:
                            LOG.info("Received unrecognized big data packet: {}", StringUtils.bytesToHex(value));
                            break;
                    }
                    break;
                default:
                    LOG.info("Received unrecognized notify v2 packet: {}", StringUtils.bytesToHex(value));
                    break;
            }
            return true;
        }

        return false;
    }

    @NonNull
    private static GBDeviceEventBatteryInfo createDeviceBatteryInfoEvent(int levelResponse, boolean charging) {
        GBDeviceEventBatteryInfo batteryEvent = new GBDeviceEventBatteryInfo();
        batteryEvent.level = levelResponse;
        batteryEvent.state = charging ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;
        return batteryEvent;
    }

    private byte[] buildPacket(byte[] contents) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        if (contents.length <= 15) {
            buffer.put(contents);
            int checksum = 0;
            for (byte content : contents) {
                checksum = (byte) (checksum + content) & 0xff;
            }
            buffer.put(15, (byte) checksum);
        } else {
            LOG.warn("Packet content too long!");
        }
        return buffer.array();
    }

    private byte[] buildBigDataV2Packet(final byte type, final byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(6 + payload.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(YawellRingConstants.CMD_BIG_DATA_V2);
        buffer.put(type);
        buffer.putShort((short) payload.length);
        buffer.putShort((short) YawellRingPacketHandler.crc16Modbus(payload));
        buffer.put(payload);
        return buffer.array();
    }

    private void sendWrite(String taskName, byte[] contents) {
        TransactionBuilder builder = createTransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(YawellRingConstants.CHARACTERISTIC_WRITE);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue();
        }
    }

    private void sendCommand(String taskName, byte[] contents) {
        TransactionBuilder builder = createTransactionBuilder(taskName);
        BluetoothGattCharacteristic characteristic = getCharacteristic(YawellRingConstants.CHARACTERISTIC_COMMAND);
        if (characteristic != null) {
            builder.write(characteristic, contents);
            builder.queue();
        }
    }

    private void startWorkout(final Prefs prefs) {
        if (workoutActive) {
            LOG.info("Workout already running, ignoring start");
            GB.toast(getContext(), R.string.qore_workout_already_running, Toast.LENGTH_SHORT, GB.INFO);
            return;
        }
        int sport;
        try {
            sport = Integer.parseInt(prefs.getString(YawellRingConstants.PREF_WORKOUT_SPORT, "98"));
        } catch (final NumberFormatException e) {
            sport = 98;
        }
        workoutSport = (byte) sport;
        workoutStartMillis = System.currentTimeMillis();
        workoutElapsedSeconds = 0;
        workoutLastHeartRate = 0;
        workoutHeartRates.clear();
        workoutActive = true;
        beginWorkoutTracking(prefs, true);
        // Halo sends start, then continue, when it begins a workout
        sendWorkoutStatus(YawellRingConstants.PHONE_SPORT_START);
        sendWorkoutStatus(YawellRingConstants.PHONE_SPORT_CONTINUE);
        publishWorkout(false);
    }

    /** @return true if a workout was running and the status was sent */
    private boolean sendWorkoutStatus(final byte status) {
        if (!workoutActive) {
            LOG.info("No workout running, not sending status {}", status);
            GB.toast(getContext(), R.string.qore_workout_none_running, Toast.LENGTH_SHORT, GB.INFO);
            return false;
        }
        final byte[] packet = buildPacket(new byte[]{YawellRingConstants.CMD_PHONE_SPORT, status, workoutSport});
        LOG.info("Workout status request sent: {}", StringUtils.bytesToHex(packet));
        sendWrite("workoutStatusRequest", packet);
        return true;
    }

    private void stopWorkout() {
        if (!sendWorkoutStatus(YawellRingConstants.PHONE_SPORT_STOP)) {
            return;
        }
        workoutActive = false;
        workoutStoppedMillis = System.currentTimeMillis();
        endWorkoutTracking();
        saveWorkout();
        publishWorkoutIdle();
        GB.toast(getContext(), R.string.qore_workout_saved, Toast.LENGTH_SHORT, GB.INFO);
    }

    private void saveWorkout() {
        final long endMillis = System.currentTimeMillis();
        final ActivitySummaryData data = new ActivitySummaryData();
        data.add(ActivitySummaryEntries.ACTIVE_SECONDS, workoutElapsedSeconds, ActivitySummaryEntries.UNIT_SECONDS);
        if (!workoutHeartRates.isEmpty()) {
            int sum = 0, min = Integer.MAX_VALUE, max = 0;
            for (final int hr : workoutHeartRates) {
                sum += hr;
                min = Math.min(min, hr);
                max = Math.max(max, hr);
            }
            data.add(ActivitySummaryEntries.HR_AVG, Math.round((float) sum / workoutHeartRates.size()), ActivitySummaryEntries.UNIT_BPM);
            data.add(ActivitySummaryEntries.HR_MIN, min, ActivitySummaryEntries.UNIT_BPM);
            data.add(ActivitySummaryEntries.HR_MAX, max, ActivitySummaryEntries.UNIT_BPM);
        }

        final ActivityKind kind = sportToActivityKind(workoutSport & 0xff);
        try (DBHandler db = GBApplication.acquireDB()) {
            final DaoSession session = db.getDaoSession();
            final BaseActivitySummary summary = new BaseActivitySummary();
            summary.setDevice(DBHelper.getDevice(getDevice(), session));
            summary.setUser(DBHelper.getUser(session));
            summary.setName(kind.getLabel(getContext()));
            summary.setActivityKind(kind.getCode());
            summary.setStartTime(new Date(workoutStartMillis));
            summary.setEndTime(new Date(endMillis));
            if (!workoutTrack.isEmpty()) {
                attachFitTrack(summary, data, kind);
            }
            summary.setSummaryData(data.toString());
            session.getBaseActivitySummaryDao().insert(summary);
            LOG.info("Saved workout: {} for {} s, {} heart rate samples, {} track points",
                    kind, workoutElapsedSeconds, workoutHeartRates.size(), workoutTrack.size());
        } catch (final Exception e) {
            LOG.error("Error saving workout", e);
        }
    }

    /** Walks, runs, rides and football are recorded with the phone's GPS. */
    private static boolean isOutdoorSport(final int sport) {
        switch (sport) {
            case 4:   // walk
            case 7:   // run
            case 9:   // cycling
            case 32:  // football
                return true;
            default:
                return false;
        }
    }

    /** Starts phone GPS (and optionally OpenTracks) for an outdoor workout. */
    private void beginWorkoutTracking(final Prefs prefs, final boolean startOpenTracks) {
        workoutTrack.clear();
        workoutLastLocation = null;
        final int sport = workoutSport & 0xff;
        final boolean outdoor = isOutdoorSport(sport);

        workoutUsesGps = outdoor && prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_GPS, true);
        if (workoutUsesGps) {
            GBLocationService.start(getContext(), getDevice(), GBLocationProviderType.GPS, 1000);
        }

        workoutUsesOpenTracks = startOpenTracks && outdoor
                && prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_WORKOUT_START_ON_PHONE, false);
        if (workoutUsesOpenTracks) {
            OpenTracksController.startRecording(getContext(), sportToActivityKind(sport));
        }
    }

    private void endWorkoutTracking() {
        if (workoutUsesGps) {
            GBLocationService.stop(getContext(), getDevice());
        }
        if (workoutUsesOpenTracks) {
            OpenTracksController.stopRecording(getContext());
        }
        workoutUsesGps = false;
        workoutUsesOpenTracks = false;
    }

    /** Adds a point with the band's heart rate and, outdoors, the latest phone location. */
    private void recordTrackPoint(final int heartRate) {
        GPSCoordinate location = null;
        final Location l = workoutLastLocation;
        if (workoutUsesGps && l != null && System.currentTimeMillis() - workoutLastLocationMillis < GPS_FIX_MAX_AGE_MILLIS) {
            location = l.hasAltitude()
                    ? new GPSCoordinate(l.getLongitude(), l.getLatitude(), l.getAltitude())
                    : new GPSCoordinate(l.getLongitude(), l.getLatitude());
        }
        if (heartRate <= 0 && location == null) {
            return;
        }
        final ActivityPoint point = new ActivityPoint(new Date());
        if (heartRate > 0) {
            point.setHeartRate(heartRate);
        }
        if (location != null) {
            point.setLocation(location);
            if (l.hasSpeed()) {
                point.setSpeed(l.getSpeed());
            }
        }
        workoutTrack.add(point);
    }

    @Override
    public void onSetGpsLocation(final Location location) {
        if (workoutActive && workoutUsesGps) {
            workoutLastLocation = location;
            workoutLastLocationMillis = System.currentTimeMillis();
        }
    }

    /**
     * Writes the recorded track (heart rate, plus GPS outdoors) to a FIT file and attaches it
     * to the workout. Gadgetbridge then uses it for the workout charts and map, and FIT export
     * (e.g. for Strava) hands this file over as is.
     */
    private void attachFitTrack(final BaseActivitySummary summary, final ActivitySummaryData data, final ActivityKind kind) {
        boolean hasGps = false;
        final ActivityTrack track = new ActivityTrack();
        track.setBaseTime(summary.getStartTime());
        track.setName(kind.getLabel(getContext()));
        for (final ActivityPoint point : workoutTrack) {
            track.addTrackPoint(point);
            hasGps |= point.getLocation() != null;
        }
        data.setHasGps(hasGps);
        try {
            final File dir = new File(FileUtils.getExternalFilesDir(), "qore2-workouts");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create " + dir);
            }
            final File file = new File(dir, "workout-" + summary.getStartTime().getTime() + ".fit");
            new FitExporter().performExport(track, summary, data, file);
            summary.setRawDetailsPath(file.getAbsolutePath());
            LOG.info("Wrote workout track to {} ({} points, gps: {})", file, workoutTrack.size(), hasGps);
        } catch (final Exception e) {
            LOG.error("Failed to write the workout FIT file", e);
        }
    }

    /** Publishes the live workout values for the activity list card. */
    private void publishWorkout(final boolean paused) {
        getDevicePrefs().getPreferences().edit()
                .putString(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_STATE,
                        paused ? YawellRingConstants.WORKOUT_STATE_PAUSED : YawellRingConstants.WORKOUT_STATE_RUNNING)
                .putString(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_ACTIVE_SPORT_NAME,
                        sportToActivityKind(workoutSport & 0xff).getLabel(getContext()))
                .putInt(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_ELAPSED_SECONDS, workoutElapsedSeconds)
                .putInt(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_HEART_RATE, workoutLastHeartRate)
                .apply();
    }

    /** Clears the live workout values: no workout is running. */
    private void publishWorkoutIdle() {
        getDevicePrefs().getPreferences().edit()
                .putString(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_STATE, YawellRingConstants.WORKOUT_STATE_IDLE)
                .remove(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_ACTIVE_SPORT_NAME)
                .remove(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_ELAPSED_SECONDS)
                .remove(DeviceSettingsPreferenceConst.PREF_PHONE_WORKOUT_HEART_RATE)
                .apply();
    }

    /** Maps the band's sport type codes (from the Halo app) to Gadgetbridge activity kinds. */
    private static ActivityKind sportToActivityKind(final int sport) {
        switch (sport) {
            case 4:
                return ActivityKind.WALKING;
            case 6:
                return ActivityKind.SWIMMING;
            case 7:
                return ActivityKind.RUNNING;
            case 9:
                return ActivityKind.CYCLING;
            case 32:
                return ActivityKind.SOCCER;
            case 98:
                return ActivityKind.FITNESS_EXERCISES;
            default:
                return ActivityKind.EXERCISE;
        }
    }

    private void requestBatteryInfo() {
        byte[] batteryRequestPacket = buildPacket(new byte[]{YawellRingConstants.CMD_BATTERY});
        LOG.info("Battery request sent: {}", StringUtils.bytesToHex(batteryRequestPacket));
        sendWrite("batteryRequest", batteryRequestPacket);
    }

    private void setPhoneName() {
        byte[] setPhoneNamePacket = buildPacket(new byte[]{
                YawellRingConstants.CMD_PHONE_NAME,
                0x02, // Client major version
                0x0a, // Client minor version
                'G',
                'B'
        });
        LOG.info("Phone name sent: {}", StringUtils.bytesToHex(setPhoneNamePacket));
        sendWrite("phoneNameRequest", setPhoneNamePacket);
    }

    private void setDateTime() {
        Calendar now = GregorianCalendar.getInstance();
        byte[] setDateTimePacket = buildPacket(new byte[]{
                YawellRingConstants.CMD_SET_DATE_TIME,
                Byte.parseByte(String.valueOf(now.get(Calendar.YEAR) % 2000), 16),
                Byte.parseByte(String.valueOf(now.get(Calendar.MONTH) + 1), 16),
                Byte.parseByte(String.valueOf(now.get(Calendar.DAY_OF_MONTH)), 16),
                Byte.parseByte(String.valueOf(now.get(Calendar.HOUR_OF_DAY)), 16),
                Byte.parseByte(String.valueOf(now.get(Calendar.MINUTE)), 16),
                Byte.parseByte(String.valueOf(now.get(Calendar.SECOND)), 16)
        });
        LOG.info("Set date/time request sent: {}", StringUtils.bytesToHex(setDateTimePacket));
        sendWrite("dateTimeRequest", setDateTimePacket);
    }

    @Override
    public void onSetTime() {
        setDateTime();
    }

    @Override
    public void onSendConfiguration(String config) {
        final Prefs prefs = getDevicePrefs();
        switch (config) {
            case YawellRingConstants.PREF_WORKOUT_START:
                startWorkout(prefs);
                return;
            case YawellRingConstants.PREF_WORKOUT_PAUSE:
                if (sendWorkoutStatus(YawellRingConstants.PHONE_SPORT_PAUSE)) {
                    publishWorkout(true);
                }
                return;
            case YawellRingConstants.PREF_WORKOUT_RESUME:
                if (sendWorkoutStatus(YawellRingConstants.PHONE_SPORT_CONTINUE)) {
                    publishWorkout(false);
                }
                return;
            case YawellRingConstants.PREF_WORKOUT_STOP:
                stopWorkout();
                return;
            case SettingsActivity.PREF_UNIT_DISTANCE:
                setUserPreferences();
                break;
            case DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL:
                final long hrIntervalSecs = prefs.getLong(DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL, 0);
                // Round to nearest 5 minutes and limit to 60 minutes due to device constraints
                final long hrIntervalMins = Math.min(Math.round(hrIntervalSecs / 60.0 / 5.0) * 5, 60);
                byte[] hrIntervalPacket = buildPacket(new byte[]{
                    YawellRingConstants.CMD_AUTO_HR_PREF,
                    YawellRingConstants.PREF_WRITE,
                    hrIntervalMins > 0 ? (byte) 0x01 : (byte) 0x02,
                    (byte) hrIntervalMins
                });
                LOG.info("HR interval preference request sent: {}", StringUtils.bytesToHex(hrIntervalPacket));
                sendWrite("hrIntervalPreferenceRequest", hrIntervalPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING:
                final boolean spo2Enabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, false);
                byte[] spo2PrefsPacket = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_SPO2_PREF, YawellRingConstants.PREF_WRITE, (byte) (spo2Enabled ? 0x01 : 0x00)});
                LOG.info("SpO2 preference request sent: {}", StringUtils.bytesToHex(spo2PrefsPacket));
                sendWrite("spo2PreferenceRequest", spo2PrefsPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_HEARTRATE_STRESS_MONITORING:
                final boolean stressEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HEARTRATE_STRESS_MONITORING, false);
                byte[] stressPrefsPacket = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_STRESS_PREF, YawellRingConstants.PREF_WRITE, (byte) (stressEnabled ? 0x01 : 0x00)});
                LOG.info("Stress preference request sent: {}", StringUtils.bytesToHex(stressPrefsPacket));
                sendWrite("stressPreferenceRequest", stressPrefsPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_HRV_ALL_DAY_MONITORING:
                final boolean hrvEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_HRV_ALL_DAY_MONITORING, false);
                byte[] hrvPrefsPacket = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_HRV_PREF, YawellRingConstants.PREF_WRITE, (byte) (hrvEnabled ? 0x01 : 0x00)});
                LOG.info("HRV preference request sent: {}", StringUtils.bytesToHex(hrvPrefsPacket));
                sendWrite("hrvPreferenceRequest", hrvPrefsPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_TEMPERATURE_ALL_DAY_MONITORING:
                final boolean tempEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_TEMPERATURE_ALL_DAY_MONITORING, false);
                byte[] tempPrefsPacket = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_TEMP_PREF, 0x03, YawellRingConstants.PREF_WRITE, (byte) (tempEnabled ? 0x01 : 0x00)});
                LOG.info("Temperature preference request sent: {}", StringUtils.bytesToHex(tempPrefsPacket));
                sendWrite("tempPreferenceRequest", tempPrefsPacket);
                break;
            case DeviceSettingsPreferenceConst.PREF_DISPLAY_ENABLED:
            case DeviceSettingsPreferenceConst.PREF_WEARLOCATION:
            case DeviceSettingsPreferenceConst.PREF_SCREEN_BRIGHTNESS:
            case DeviceSettingsPreferenceConst.PREF_DISPLAY_ENABLED_ALL_DAY:
            case DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_START:
            case DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_END:
                final boolean displayEnabled = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_DISPLAY_ENABLED, false);
                final String wearLocation = prefs.getString(DeviceSettingsPreferenceConst.PREF_WEARLOCATION, "left");
                int brightness = prefs.getInt(DeviceSettingsPreferenceConst.PREF_SCREEN_BRIGHTNESS, 2);
                if (brightness < 0 || brightness > 4) brightness = 2;
                final boolean displayEnabledAllDay = prefs.getBoolean(DeviceSettingsPreferenceConst.PREF_DISPLAY_ENABLED_ALL_DAY, false);
                final String displayOnStart = prefs.getString(DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_START, "07:00");
                final int displayOnStartHour = Integer.parseInt(displayOnStart.split(":")[0]);
                final int displayOnStartMin = Integer.parseInt(displayOnStart.split(":")[1]);
                final String displayOnEnd = prefs.getString(DeviceSettingsPreferenceConst.PREF_DISPLAY_ON_END, "22:00");
                final int displayOnEndHour = Integer.parseInt(displayOnEnd.split(":")[0]);
                final int displayOnEndMin = Integer.parseInt(displayOnEnd.split(":")[1]);
                byte[] displayPrefsPacket = buildPacket(new byte[]{
                    YawellRingConstants.CMD_DISPLAY_PREF,
                    0x04,
                    (byte) (displayEnabled ? 0x01 : 0x02),
                    (byte) (Objects.equals(wearLocation, "left") ? 0x01 : 0x02),
                    (byte) (brightness + 1),
                    0x05,
                    (byte) (displayEnabledAllDay ? 0x02 : 0x01),
                    (byte) displayOnStartHour,
                    (byte) displayOnStartMin,
                    (byte) displayOnEndHour,
                    (byte) displayOnEndMin
                });
                LOG.info("Display preferences request sent: {}", StringUtils.bytesToHex(displayPrefsPacket));
                sendWrite("displayPreferencesRequest", displayPrefsPacket);
                break;
        }
    }

    private void setUserPreferences() {
        final ActivityUser user = new ActivityUser();
        final DistanceUnit distanceUnit = GBApplication.getPrefs().getDistanceUnit();
        byte userGender;
        switch (user.getGender()) {
            case ActivityUser.GENDER_FEMALE:
                userGender = 0x01;
                break;
            case ActivityUser.GENDER_MALE:
                userGender = 0x00;
                break;
            default:
                userGender = 0x02;
                break;
        }
        byte[] userPrefsPacket = buildPacket(new byte[]{
                YawellRingConstants.CMD_PREFERENCES,
                YawellRingConstants.PREF_WRITE,
                0x00,  // 24h format, 0x01 is 12h format
                (byte) (distanceUnit == DistanceUnit.METRIC ? 0x00 : 0x01),
                userGender,
                (byte) user.getAge(),
                (byte) user.getHeightCm(),
                (byte) user.getWeightKg(),
                0x00,  // systolic blood pressure (e.g. 120)
                0x00,  // diastolic blood pressure (e.g. 90)
                0x00  // heart rate value warning threshold: (e.g. 160)
        });
        LOG.info("User preferences request sent: {}", StringUtils.bytesToHex(userPrefsPacket));
        sendWrite("userPreferenceRequest", userPrefsPacket);
    }

    private void requestSettingsFromRing() {
        byte[] request = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_HR_PREF, YawellRingConstants.PREF_READ});
        LOG.info("Request HR measurement interval from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("hrIntervalRequest", request);
        request = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_STRESS_PREF, YawellRingConstants.PREF_READ});
        LOG.info("Request stress measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("stressSettingRequest", request);
        request = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_SPO2_PREF, YawellRingConstants.PREF_READ});
        LOG.info("Request SpO2 measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("spo2SettingRequest", request);
        request = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_HRV_PREF, YawellRingConstants.PREF_READ});
        LOG.info("Request HRV measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("hrvSettingRequest", request);
        request = buildPacket(new byte[]{YawellRingConstants.CMD_AUTO_TEMP_PREF, 0x03, YawellRingConstants.PREF_READ});
        LOG.info("Request temperature measurement setting from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("tempSettingRequest", request);
        request = buildPacket(new byte[]{YawellRingConstants.CMD_GOALS, YawellRingConstants.PREF_READ});
        LOG.info("Request goals from ring: {}", StringUtils.bytesToHex(request));
        sendWrite("goalsSettingRequest", request);
    }

    @Override
    public void onPowerOff() {
        byte[] poweroffPacket = buildPacket(new byte[]{YawellRingConstants.CMD_POWER_OFF, 0x01});
        LOG.info("Poweroff request sent: {}", StringUtils.bytesToHex(poweroffPacket));
        sendWrite("poweroffRequest", poweroffPacket);
    }

    @Override
    public void onFactoryReset() {
        byte[] resetPacket = buildPacket(new byte[]{YawellRingConstants.CMD_FACTORY_RESET, 0x66, 0x66});
        LOG.info("Factory reset request sent: {}", StringUtils.bytesToHex(resetPacket));
        sendWrite("resetRequest", resetPacket);
    }

    @Override
    public void onFindDevice(boolean start) {
        if (!start) return;

        byte[] findDevicePacket = buildPacket(new byte[]{YawellRingConstants.CMD_FIND_DEVICE, 0x55, (byte) 0xAA});
        LOG.info("Find device request sent: {}", StringUtils.bytesToHex(findDevicePacket));
        sendWrite("findDeviceRequest", findDevicePacket);
    }

    private boolean supportsNotifications() {
        return getCoordinator().supportsNotifications(getDevice());
    }

    @Override
    public void onNotification(NotificationSpec notificationSpec) {
        if (!supportsNotifications()) return;

        BluetoothGattCharacteristic characteristic = getCharacteristic(YawellRingConstants.CHARACTERISTIC_WRITE);
        if (characteristic == null) return;

        String from = notificationSpec.getSender() != null ? notificationSpec.getSender() : notificationSpec.getTitle();
        String text = StringUtils.join(":", from, notificationSpec.getBody()).toString();
        // The firmware only buzzes once per message, so repeat it to make it noticeable
        int count = getDevicePrefs().getInt(YawellRingConstants.PREF_NOTIFICATION_VIBRATION_COUNT, 3);

        // The device has no screen, so each repeat is just another buzz. Send them as separate
        // transactions instead of sleeping in one, so that other BLE traffic is not blocked.
        for (int i = 0; i < count; i++) {
            backgroundTasksHandler.postDelayed(() -> {
                TransactionBuilder builder = createTransactionBuilder("pushNotification");
                writePushMessage(builder, characteristic, YawellRingConstants.PUSH_TYPE_SMS, text);
                builder.queue();
            }, i * 1000L);
        }
    }

    @Override
    public void onSetCallState(CallSpec callSpec) {
        if (!supportsNotifications()) return;

        byte type;
        switch (callSpec.getCommand()) {
            case CallSpec.CALL_INCOMING:
                type = YawellRingConstants.PUSH_TYPE_CALL_INCOMING;
                break;
            case CallSpec.CALL_ACCEPT:
            case CallSpec.CALL_REJECT:
            case CallSpec.CALL_START:
            case CallSpec.CALL_END:
                type = YawellRingConstants.PUSH_TYPE_CALL_STOP;
                break;
            default:
                return;
        }

        BluetoothGattCharacteristic characteristic = getCharacteristic(YawellRingConstants.CHARACTERISTIC_WRITE);
        if (characteristic == null) return;

        String caller = callSpec.getName() != null ? callSpec.getName() : callSpec.getNumber();
        TransactionBuilder builder = createTransactionBuilder("pushCallState");
        writePushMessage(builder, characteristic, type, caller);
        builder.queue();
    }

    /**
     * A push message is split into chunks of 11 bytes of UTF-8 text, each sent as
     * {CMD_PUSH_MESSAGE, type, total chunks, chunk index (1-based), text...}.
     */
    private void writePushMessage(TransactionBuilder builder, BluetoothGattCharacteristic characteristic, byte type, String text) {
        byte[] textBytes = StringUtils.truncateToBytes(text, YawellRingConstants.PUSH_CHUNK_SIZE * YawellRingConstants.PUSH_MAX_CHUNKS);
        int chunks = Math.max(1, (textBytes.length + YawellRingConstants.PUSH_CHUNK_SIZE - 1) / YawellRingConstants.PUSH_CHUNK_SIZE);
        for (int i = 0; i < chunks; i++) {
            int start = i * YawellRingConstants.PUSH_CHUNK_SIZE;
            int length = Math.min(YawellRingConstants.PUSH_CHUNK_SIZE, textBytes.length - start);
            ByteBuffer contents = ByteBuffer.allocate(4 + length);
            contents.put(YawellRingConstants.CMD_PUSH_MESSAGE);
            contents.put(type);
            contents.put((byte) chunks);
            contents.put((byte) (i + 1));
            contents.put(textBytes, start, length);
            byte[] packet = buildPacket(contents.array());
            LOG.debug("Push message chunk sent: {}", StringUtils.bytesToHex(packet));
            builder.write(characteristic, packet);
        }
    }

    @Override
    public void onHeartRateTest() {
        byte[] measureHeartRatePacket = buildPacket(new byte[]{YawellRingConstants.CMD_MANUAL_HEART_RATE, 0x01});
        LOG.info("Measure HR request sent: {}", StringUtils.bytesToHex(measureHeartRatePacket));
        sendWrite("measureHRRequest", measureHeartRatePacket);
    }

    @Override
    public void onEnableRealtimeHeartRateMeasurement(boolean enable) {
        if (enable == liveActivityContext.isRealtimeHrm()) return;
        liveActivityContext.setRealtimeHrm(enable);
        liveActivityContext.setRealtimeHrmPacketCount(0);

        byte enableByte;
        if(enable) enableByte = 0x01;
        else enableByte = 0x02;

        byte[] measureHeartRatePacket = buildPacket(new byte[]{YawellRingConstants.CMD_REALTIME_HEART_RATE, enableByte});
        LOG.info("Enable realtime HRM request sent: {}", StringUtils.bytesToHex(measureHeartRatePacket));
        sendWrite("enableRealtimeHRMRequest", measureHeartRatePacket);
    }

    @Override
    public void onEnableRealtimeSteps(boolean enable) {
        if (enable == liveActivityContext.isRealtimeSteps()) return;
        liveActivityContext.setRealtimeSteps(enable);

        if(enable) {
            liveActivityContext.setBufferedSteps(0);
            liveActivityContext.setBufferedCalories(0);
            liveActivityContext.setBufferedDistance(0);

            LOG.info("Starting live activity timeout scheduler");
            ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
            service.scheduleWithFixedDelay(YawellRingPacketHandler.liveActivityPulse(getDevice(), getContext(), liveActivityContext), 0, LIVE_ACTIVITY_BUFFER_INTERVAL, TimeUnit.MILLISECONDS);
            liveActivityContext.setRealtimeStepsScheduler(service);
        } else {
            LOG.info("Stopping live activity timeout scheduler");
            liveActivityContext.getRealtimeStepsScheduler().shutdown();
            liveActivityContext.setRealtimeStepsScheduler(null);
        }
    }

    @Override
    public void onFetchRecordedData(int dataTypes) {
        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), "", true, 0, getContext());
        daysAgo = 0;
        fetchHistoryActivity();
    }

    private void fetchRecordedDataFinished() {
        GB.updateTransferNotification(null, "", false, 100, getContext());
        LOG.info("Sync finished!");
        getDevice().unsetBusyTask();
        getDevice().sendDeviceUpdateIntent(getContext());
        GB.signalActivityDataFinish(getDevice());
    }

    private void fetchHistoryActivity() {
        getDevice().setBusyTask(R.string.busy_task_fetch_activity_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
        syncingDay.set(Calendar.HOUR_OF_DAY, 0);
        syncingDay.set(Calendar.MINUTE, 0);
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        byte[] activityHistoryRequest = buildPacket(new byte[]{YawellRingConstants.CMD_SYNC_ACTIVITY, (byte) daysAgo, 0x0f, 0x00, 0x5f, 0x01});
        LOG.info("Fetch historical activity data request sent: {}", StringUtils.bytesToHex(activityHistoryRequest));
        sendWrite("activityHistoryRequest", activityHistoryRequest);
    }

    private void fetchHistoryHR() {
        getDevice().setBusyTask(R.string.busy_task_fetch_hr_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        if (daysAgo != 0) {
            syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
            syncingDay.set(Calendar.HOUR_OF_DAY, 0);
            syncingDay.set(Calendar.MINUTE, 0);
            syncingDay.set(Calendar.SECOND, 0);
        }
        syncingDay.set(Calendar.MILLISECOND, 0);
        ByteBuffer hrHistoryRequestBB = ByteBuffer.allocate(5);
        hrHistoryRequestBB.order(ByteOrder.LITTLE_ENDIAN);
        hrHistoryRequestBB.put(0, YawellRingConstants.CMD_SYNC_HEART_RATE);
        long requestTimestamp = syncingDay.getTimeInMillis() + syncingDay.get(Calendar.ZONE_OFFSET) + syncingDay.get(Calendar.DST_OFFSET);
        hrHistoryRequestBB.putInt(1, (int) (requestTimestamp / 1000));
        byte[] hrHistoryRequest = buildPacket(hrHistoryRequestBB.array());
        LOG.info("Fetch historical HR data request sent ({}): {}", DateTimeUtils.formatIso8601(syncingDay.getTime()), StringUtils.bytesToHex(hrHistoryRequest));
        sendWrite("hrHistoryRequest", hrHistoryRequest);
    }

    private void fetchHistoryStress() {
        getDevice().setBusyTask(R.string.busy_task_fetch_stress_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        byte[] stressHistoryRequest = buildPacket(new byte[]{YawellRingConstants.CMD_SYNC_STRESS});
        LOG.info("Fetch historical stress data request sent: {}", StringUtils.bytesToHex(stressHistoryRequest));
        sendWrite("stressHistoryRequest", stressHistoryRequest);
    }

    private void fetchHistorySpo2() {
        getDevice().setBusyTask(R.string.busy_task_fetch_spo2_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        byte[] spo2HistoryRequest = new byte[]{
                YawellRingConstants.CMD_BIG_DATA_V2,
                YawellRingConstants.BIG_DATA_TYPE_SPO2,
                0x01,
                0x00,
                (byte) 0xff,
                0x00,
                (byte) 0xff
        };
        LOG.info("Fetch historical SpO2 data request sent: {}", StringUtils.bytesToHex(spo2HistoryRequest));
        sendCommand("spo2HistoryRequest", spo2HistoryRequest);
    }

    private void fetchHistorySleep() {
        getDevice().setBusyTask(R.string.busy_task_fetch_sleep_data, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        byte[] sleepHistoryRequest = new byte[]{
                YawellRingConstants.CMD_BIG_DATA_V2,
                YawellRingConstants.BIG_DATA_TYPE_SLEEP,
                0x01,
                0x00,
                (byte) 0xff,
                0x00,
                (byte) 0xff
        };
        LOG.info("Fetch historical sleep data request sent: {}", StringUtils.bytesToHex(sleepHistoryRequest));
        sendCommand("sleepHistoryRequest", sleepHistoryRequest);
    }

    private void fetchHistoryHRV() {
        getDevice().sendDeviceUpdateIntent(getContext());
        syncingDay = Calendar.getInstance();
        if (daysAgo != 0) {
            syncingDay.add(Calendar.DAY_OF_MONTH, 0 - daysAgo);
            syncingDay.set(Calendar.HOUR_OF_DAY, 0);
            syncingDay.set(Calendar.MINUTE, 0);
        }
        syncingDay.set(Calendar.SECOND, 0);
        syncingDay.set(Calendar.MILLISECOND, 0);
        ByteBuffer hrvHistoryRequestBB = ByteBuffer.allocate(5);
        hrvHistoryRequestBB.order(ByteOrder.LITTLE_ENDIAN);
        hrvHistoryRequestBB.put(0, YawellRingConstants.CMD_SYNC_HRV);
        hrvHistoryRequestBB.putInt(1, daysAgo);
        byte[] hrvHistoryRequest = buildPacket(hrvHistoryRequestBB.array());
        LOG.info("Fetch historical HRV data request sent ({}): {}", formatIso8601(syncingDay), StringUtils.bytesToHex(hrvHistoryRequest));
        sendWrite("hrvHistoryRequest", hrvHistoryRequest);
    }

    private void fetchAlarms() {
        final byte[] alarmsRequest = buildBigDataV2Packet(YawellRingConstants.BIG_DATA_TYPE_ALARM, new byte[]{YawellRingConstants.ALARM_OP_READ});
        sendCommand("getAlarmsRequest", alarmsRequest);
    }

    @Override
    public void onSetAlarms(final ArrayList<? extends Alarm> alarms) {
        final byte[] payload = YawellRingPacketHandler.encodeAlarmsPayload(YawellRingConstants.ALARM_OP_WRITE, alarms);
        final byte[] setAlarmsPacket = buildBigDataV2Packet(YawellRingConstants.BIG_DATA_TYPE_ALARM, payload);
        sendCommand("setAlarmsRequest", setAlarmsPacket);
    }

    private void fetchTemperature() {
        getDevice().setBusyTask(R.string.busy_task_fetch_temperature, getContext());
        getDevice().sendDeviceUpdateIntent(getContext());
        byte[] temperatureHistoryRequest = new byte[]{
                YawellRingConstants.CMD_BIG_DATA_V2,
                YawellRingConstants.BIG_DATA_TYPE_TEMPERATURE,
                0x01,
                0x00,
                0x3e,
                (byte) 0x81,
                0x02
        };
        LOG.info("Fetch historical temperature data request sent: {}", StringUtils.bytesToHex(temperatureHistoryRequest));
        sendCommand("temperatureHistoryRequest", temperatureHistoryRequest);
    }
}
