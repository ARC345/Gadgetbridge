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

import nodomain.freeyourgadget.gadgetbridge.devices.crpring.CrpRingConstants
import java.time.ZoneId

/** Request frames sent to the band. */
object CrpRingCommands {
    /** Configuration keys that start and end a workout on the band. */
    const val CONFIG_WORKOUT_START = "crp_workout_start"
    const val CONFIG_WORKOUT_END = "crp_workout_end"

    /** Workout type codes and states, as the Vox app sends them. */
    const val WORKOUT_WEIGHT_LIFTING = 79
    const val STATE_PAUSE = -2
    const val STATE_END = -1
    const val GOAL_TYPE_NONE = -1

    private const val CMD_WORKOUT_STATE = 12

    private fun le32(value: Long) = byteArrayOf(
        value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte()
    )

    private fun flag(on: Boolean) = byteArrayOf(if (on) 1 else 0)
    private fun clampByte(value: Int) = value.coerceIn(0, 255).toByte()

    fun setTime(utcMillis: Long, zone: ZoneId): ByteArray = CrpRingFrame.build(
        CrpRingConstants.GROUP_SET, CrpRingConstants.CMD_SET_TIME,
        le32(CrpRingTime.toDeviceSeconds(utcMillis, zone)) + byteArrayOf(8)
    )

    fun userInfo(heightCm: Int, weightKg: Int, age: Int, gender: Int, stepLengthCm: Int): ByteArray =
        CrpRingFrame.build(
            CrpRingConstants.GROUP_SET, CrpRingConstants.CMD_SET_USER_INFO,
            byteArrayOf(clampByte(heightCm), clampByte(weightKg), clampByte(age), clampByte(gender), clampByte(stepLengthCm))
        )

    fun sendPin(pin: String): ByteArray {
        val bytes = pin.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..247) { "Bad PIN length: ${bytes.size}" }
        return CrpRingFrame.build(
            CrpRingConstants.GROUP_SET, CrpRingConstants.CMD_AUTH,
            byteArrayOf(CrpRingConstants.AUTH_SEND.toByte(), bytes.size.toByte()) + bytes
        )
    }

    fun firmwareVersion(): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_SYSTEM, CrpRingConstants.CMD_FIRMWARE_VERSION)

    fun findDevice(start: Boolean): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_ACTION, CrpRingConstants.CMD_FIND_DEVICE, flag(start))

    fun measureHeartRate(start: Boolean): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_SET, CrpRingConstants.CMD_MEASURE_HEART_RATE, flag(start))

    fun stepsDetails(day: Int): ByteArray = query(CrpRingConstants.CMD_QUERY_STEPS_DETAILS, day.toByte())
    fun sleep(day: Int): ByteArray = query(CrpRingConstants.CMD_QUERY_SLEEP, day.toByte())
    fun timedHeartRate(day: Int, page: Int): ByteArray =
        query(CrpRingConstants.CMD_QUERY_TIMED_HEART_RATE, day.toByte(), page.toByte())
    fun timedSpo2(day: Int, page: Int): ByteArray =
        query(CrpRingConstants.CMD_QUERY_TIMED_SPO2, day.toByte(), page.toByte())
    fun timedHrv(day: Int, page: Int): ByteArray =
        query(CrpRingConstants.CMD_QUERY_TIMED_HRV, day.toByte(), page.toByte())
    fun timedStress(day: Int, page: Int): ByteArray =
        query(CrpRingConstants.CMD_QUERY_TIMED_STRESS, day.toByte(), page.toByte())
    fun sleepSessions(): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_SLEEP, CrpRingConstants.CMD_SLEEP_SESSIONS)
    fun sleepDetails(id: Int): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_SLEEP, CrpRingConstants.CMD_SLEEP_DETAILS, byteArrayOf(id.toByte()))
    fun sleepSessionStages(id: Int): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_SLEEP, CrpRingConstants.CMD_SLEEP_SESSION_STAGES, byteArrayOf(id.toByte()))
    fun manualHeartRate(): ByteArray = query(CrpRingConstants.CMD_QUERY_MANUAL_HEART_RATE)
    fun manualSpo2(): ByteArray = query(CrpRingConstants.CMD_QUERY_MANUAL_SPO2)

    /**
     * Workout state frame (group 1, command 12). The payload is the state, the goal type and the goal
     * value. A start sends the workout type in the state byte, such as [WORKOUT_WEIGHT_LIFTING].
     */
    fun workoutState(state: Int, goalType: Int = GOAL_TYPE_NONE, goalValue: Int = 0): ByteArray =
        CrpRingFrame.build(
            CrpRingConstants.GROUP_SET,
            CMD_WORKOUT_STATE,
            byteArrayOf(state.toByte(), goalType.toByte()) + le32(goalValue.toLong())
        )

    fun startWorkout(workoutType: Int = WORKOUT_WEIGHT_LIFTING): ByteArray = workoutState(workoutType)

    /** The Vox app pauses a workout before it ends it, so the pause frame is sent first. */
    fun pauseWorkout(): ByteArray = workoutState(STATE_PAUSE)

    fun endWorkout(): ByteArray = workoutState(STATE_END)

    private fun query(command: Int, vararg payload: Byte): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_QUERY, command, payload)
}
