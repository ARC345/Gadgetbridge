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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*;

/**
 * Card on today's activity list to start, pause, resume and stop a workout on the device.
 * <p>
 * Idle: a compact row with a sport picker and Start. Active: a state label (text and icon,
 * not colour alone), a large elapsed timer, the sport and heart rate, and Pause/Resume plus
 * Stop. When the device is not connected the buttons are disabled and a line explains why.
 * <p>
 * The device support publishes the live values to the device preferences; the card follows
 * them. Shown only for devices that support phone-started workouts, and only for today.
 */
public class PhoneWorkoutCard implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String DEFAULT_SPORT = "98";

    private final View card;
    private final View idleGroup;
    private final MaterialAutoCompleteTextView sport;
    private final MaterialButton start;
    private final View activeGroup;
    private final ImageView stateIcon;
    private final TextView state;
    private final TextView elapsed;
    private final TextView detail;
    private final MaterialButton pauseResume;
    private final MaterialButton stop;
    private final TextView hint;

    @Nullable
    private GBDevice device;
    @Nullable
    private SharedPreferences prefs;
    private String[] sportNames = new String[0];
    private String[] sportValues = new String[0];
    private boolean showForToday = true;
    private boolean receiverRegistered = false;
    @Nullable
    private Runnable onWorkoutFinished;
    @Nullable
    private String lastState;

    // Refresh the buttons when the device connects or disconnects
    private final BroadcastReceiver deviceChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            card.post(PhoneWorkoutCard.this::update);
        }
    };

    public PhoneWorkoutCard(final View root) {
        card = root.findViewById(R.id.phone_workout_card);
        idleGroup = root.findViewById(R.id.phone_workout_idle);
        sport = root.findViewById(R.id.phone_workout_sport);
        start = root.findViewById(R.id.phone_workout_start);
        activeGroup = root.findViewById(R.id.phone_workout_active);
        stateIcon = root.findViewById(R.id.phone_workout_state_icon);
        state = root.findViewById(R.id.phone_workout_state);
        elapsed = root.findViewById(R.id.phone_workout_elapsed);
        detail = root.findViewById(R.id.phone_workout_detail);
        pauseResume = root.findViewById(R.id.phone_workout_pause_resume);
        stop = root.findViewById(R.id.phone_workout_stop);
        hint = root.findViewById(R.id.phone_workout_hint);
    }

    /** Called when a workout ends, so the screen can reload and show the saved workout. */
    public void setOnWorkoutFinished(@Nullable final Runnable callback) {
        onWorkoutFinished = callback;
    }

    /** Workouts can only be started for now, so the card is hidden on past days. */
    public void setShowForToday(final boolean isToday) {
        showForToday = isToday;
        update();
    }

    /** Binds the card to a device, or hides it if the device can't start workouts. */
    public void bind(@Nullable final GBDevice newDevice) {
        unregister();
        device = newDevice;
        if (device == null || !device.getDeviceCoordinator().supportsPhoneStartedWorkouts(device)) {
            prefs = null;
            card.setVisibility(View.GONE);
            return;
        }
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());

        final Context context = card.getContext();
        sportNames = context.getResources().getStringArray(R.array.qore_workout_sports);
        sportValues = context.getResources().getStringArray(R.array.qore_workout_sports_values);
        sport.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, sportNames));
        sport.setOnItemClickListener((parent, view, position, id) ->
                prefs.edit().putString(PREF_PHONE_WORKOUT_SPORT, sportValues[position]).apply());

        start.setOnClickListener(v -> send(PREF_PHONE_WORKOUT_START));
        pauseResume.setOnClickListener(v -> send(PHONE_WORKOUT_STATE_PAUSED.equals(currentState())
                ? PREF_PHONE_WORKOUT_RESUME : PREF_PHONE_WORKOUT_PAUSE));
        stop.setOnClickListener(v -> new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.qore_workout_stop_title)
                .setMessage(R.string.qore_workout_stop_confirm)
                .setPositiveButton(R.string.qore_workout_stop_confirm_button, (dialog, which) -> send(PREF_PHONE_WORKOUT_STOP))
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
        if (key != null && key.startsWith("phone_workout_")) {
            card.post(this::update);
        }
    }

    private void send(final String command) {
        if (device != null) {
            GBApplication.deviceService(device).onSendConfiguration(command);
        }
    }

    private String currentState() {
        return prefs != null ? prefs.getString(PREF_PHONE_WORKOUT_STATE, PHONE_WORKOUT_STATE_IDLE) : PHONE_WORKOUT_STATE_IDLE;
    }

    private void update() {
        if (prefs == null || device == null) {
            return;
        }
        final String workoutState = currentState();
        final boolean idle = PHONE_WORKOUT_STATE_IDLE.equals(workoutState);
        final boolean paused = PHONE_WORKOUT_STATE_PAUSED.equals(workoutState);

        // A workout just ended and was saved: reload the list so it shows up at the top
        if (idle && lastState != null && !PHONE_WORKOUT_STATE_IDLE.equals(lastState) && onWorkoutFinished != null) {
            onWorkoutFinished.run();
        }
        lastState = workoutState;

        // Keep a running workout visible on any day, so it can always be stopped
        card.setVisibility(showForToday || !idle ? View.VISIBLE : View.GONE);

        idleGroup.setVisibility(idle ? View.VISIBLE : View.GONE);
        activeGroup.setVisibility(idle ? View.GONE : View.VISIBLE);

        if (idle) {
            final String selected = prefs.getString(PREF_PHONE_WORKOUT_SPORT, DEFAULT_SPORT);
            for (int i = 0; i < sportValues.length; i++) {
                if (sportValues[i].equals(selected)) {
                    sport.setText(sportNames[i], false);
                    break;
                }
            }
        } else {
            final Context context = card.getContext();
            state.setText(paused ? R.string.qore_workout_state_paused : R.string.qore_workout_state_in_progress);
            stateIcon.setImageResource(paused ? R.drawable.ic_pause : R.drawable.ic_play);

            final String time = formatElapsed(prefs.getInt(PREF_PHONE_WORKOUT_ELAPSED_SECONDS, 0));
            elapsed.setText(time);
            elapsed.setContentDescription(context.getString(R.string.qore_workout_elapsed_description, time));

            final String sportName = prefs.getString(PREF_PHONE_WORKOUT_ACTIVE_SPORT_NAME, "");
            final int heartRate = prefs.getInt(PREF_PHONE_WORKOUT_HEART_RATE, 0);
            detail.setText(heartRate > 0 ? context.getString(R.string.qore_workout_detail_hr, sportName, heartRate) : sportName);

            pauseResume.setText(paused ? R.string.qore_workout_resume : R.string.qore_workout_pause);
            pauseResume.setIconResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        }

        // The charts screen holds a copy of the device made when it opened, whose state does
        // not follow the connection. Ask the device manager for the live device instead.
        final GBDevice liveDevice = GBApplication.app().getDeviceManager().getDeviceByAddress(device.getAddress());
        final boolean connected = (liveDevice != null ? liveDevice : device).isInitialized();
        start.setEnabled(connected);
        sport.setEnabled(connected);
        pauseResume.setEnabled(connected);
        stop.setEnabled(connected);
        hint.setVisibility(connected ? View.GONE : View.VISIBLE);
    }

    private static String formatElapsed(final int seconds) {
        final int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
