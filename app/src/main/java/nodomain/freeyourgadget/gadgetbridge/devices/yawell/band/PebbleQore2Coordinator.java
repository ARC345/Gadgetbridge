/*  Copyright (C) 2026 TODO: author

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
package nodomain.freeyourgadget.gadgetbridge.devices.yawell.band;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.EstimatedVo2MaxSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.Vo2MaxSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.yawell.ring.AbstractYawellRingCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.model.FitActivityTrackProvider;
import nodomain.freeyourgadget.gadgetbridge.model.Vo2MaxSample;

/**
 * Pebble Qore 2 fitness band. Uses the same Nordic UART and de5bf728 command
 * service as the Colmi/Yawell rings, and the same 16-byte command framing.
 */
public class PebbleQore2Coordinator extends AbstractYawellRingCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("PBL Qore 2.*");
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_pebble_qore2;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }

    @Override
    public boolean supportsTemperatureMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsContinuousTemperature(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsPhoneStartedWorkouts(@NonNull final GBDevice device) {
        return true;
    }

    /**
     * Workouts started from the phone are saved with a FIT file holding the per-second heart
     * rate and the phone's GPS, so charts and the map read the track from it.
     */
    @Override
    public ActivityTrackProvider getActivityTrackProvider(@NonNull final GBDevice device, @NonNull final Context context) {
        return new FitActivityTrackProvider();
    }

    /**
     * The band does not measure VO2 max. This turns on a rough estimate computed
     * from its heart rate data, see {@link EstimatedVo2MaxSampleProvider}.
     */
    @Override
    public boolean supportsVO2Max(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public Vo2MaxSampleProvider<? extends Vo2MaxSample> getVo2MaxSampleProvider(@NonNull final GBDevice device, @NonNull final DaoSession session) {
        return new EstimatedVo2MaxSampleProvider(getSampleProvider(device, session));
    }
}
