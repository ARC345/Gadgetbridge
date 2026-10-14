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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*;

/**
 * Card on the activity list to start, pause, resume and stop a workout on the device.
 * Shown only for devices whose coordinator supports phone-started workouts. The device
 * support writes the workout state and a status line to the device preferences, and
 * the card follows those changes live.
 */
public class PhoneWorkoutCard implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final View card;
    private final TextView status;
    private final View sportLayout;
    private final MaterialAutoCompleteTextView sport;
    private final MaterialButton start;
    private final MaterialButton pause;
    private final MaterialButton resume;
    private final MaterialButton stop;
    private final View buttonGap;

    @Nullable
    private GBDevice device;
    @Nullable
    private SharedPreferences prefs;
    private boolean receiverRegistered = false;

    // Refresh the buttons when the device connects or disconnects
    private final BroadcastReceiver deviceChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            card.post(PhoneWorkoutCard.this::update);
        }
    };
    private String[] sportNames;
    private String[] sportValues;

    public PhoneWorkoutCard(final View root) {
        card = root.findViewById(R.id.phone_workout_card);
        status = root.findViewById(R.id.phone_workout_status);
        sportLayout = root.findViewById(R.id.phone_workout_sport_layout);
        sport = root.findViewById(R.id.phone_workout_sport);
        start = root.findViewById(R.id.phone_workout_start);
        pause = root.findViewById(R.id.phone_workout_pause);
        resume = root.findViewById(R.id.phone_workout_resume);
        stop = root.findViewById(R.id.phone_workout_stop);
        buttonGap = root.findViewById(R.id.phone_workout_button_gap);
    }

    /** Shows the card for this device, or hides it if the device can't start workouts. */
    public void bind(@Nullable final GBDevice newDevice) {
        unregister();
        device = newDevice;
        if (device == null || !device.getDeviceCoordinator().supportsPhoneStartedWorkouts(device)) {
            card.setVisibility(View.GONE);
            prefs = null;
            return;
        }
        card.setVisibility(View.VISIBLE);
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());

        final Context context = card.getContext();
        sportNames = context.getResources().getStringArray(R.array.qore_workout_sports);
        sportValues = context.getResources().getStringArray(R.array.qore_workout_sports_values);
        sport.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, sportNames));
        sport.setOnItemClickListener((parent, view, position, id) ->
                prefs.edit().putString(PREF_PHONE_WORKOUT_SPORT, sportValues[position]).apply());

        start.setOnClickListener(v -> send(PREF_PHONE_WORKOUT_START));
        pause.setOnClickListener(v -> send(PREF_PHONE_WORKOUT_PAUSE));
        resume.setOnClickListener(v -> send(PREF_PHONE_WORKOUT_RESUME));
        stop.setOnClickListener(v -> new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.qore_workout_stop)
                .setMessage(R.string.qore_workout_stop_confirm)
                .setPositiveButton(R.string.ok, (dialog, which) -> send(PREF_PHONE_WORKOUT_STOP))
                .setNegativeButton(R.string.cancel, null)
                .show());

        prefs.registerOnSharedPreferenceChangeListener(this);
        LocalBroadcastManager.getInstance(context)
                .registerReceiver(deviceChangedReceiver, new IntentFilter(GBDevice.ACTION_DEVICE_CHANGED));
        receiverRegistered = true;
        update();
    }

    public void unregister() {
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(card.getContext()).unregisterReceiver(deviceChangedReceiver);
            receiverRegistered = false;
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (PREF_PHONE_WORKOUT_STATE.equals(key) || PREF_PHONE_WORKOUT_STATUS.equals(key) || PREF_PHONE_WORKOUT_SPORT.equals(key)) {
            card.post(this::update);
        }
    }

    private void send(final String command) {
        if (device != null) {
            GBApplication.deviceService(device).onSendConfiguration(command);
        }
    }

    private void update() {
        if (prefs == null || device == null) {
            return;
        }
        final Context context = card.getContext();
        final String state = prefs.getString(PREF_PHONE_WORKOUT_STATE, PHONE_WORKOUT_STATE_IDLE);
        final boolean idle = PHONE_WORKOUT_STATE_IDLE.equals(state);
        final boolean running = PHONE_WORKOUT_STATE_RUNNING.equals(state);
        final boolean paused = PHONE_WORKOUT_STATE_PAUSED.equals(state);

        final String statusText = prefs.getString(PREF_PHONE_WORKOUT_STATUS, "");
        status.setText(statusText.isEmpty() ? context.getString(R.string.qore_workout_status_idle) : statusText);

        final String selected = prefs.getString(PREF_PHONE_WORKOUT_SPORT, "98");
        for (int i = 0; i < sportValues.length; i++) {
            if (sportValues[i].equals(selected)) {
                sport.setText(sportNames[i], false);
                break;
            }
        }

        sportLayout.setVisibility(idle ? View.VISIBLE : View.GONE);
        start.setVisibility(idle ? View.VISIBLE : View.GONE);
        pause.setVisibility(running ? View.VISIBLE : View.GONE);
        resume.setVisibility(paused ? View.VISIBLE : View.GONE);
        stop.setVisibility(idle ? View.GONE : View.VISIBLE);
        buttonGap.setVisibility(idle ? View.GONE : View.VISIBLE);

        // The charts screen holds a copy of the device made when it opened, whose state does
        // not follow the connection. Ask the device manager for the live device instead.
        final GBDevice liveDevice = GBApplication.app().getDeviceManager().getDeviceByAddress(device.getAddress());
        final boolean connected = (liveDevice != null ? liveDevice : device).isInitialized();
        start.setEnabled(connected);
        pause.setEnabled(connected);
        resume.setEnabled(connected);
        stop.setEnabled(connected);
    }
}
