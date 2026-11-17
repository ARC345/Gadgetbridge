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
    fun manualHeartRate(): ByteArray = query(CrpRingConstants.CMD_QUERY_MANUAL_HEART_RATE)
    fun manualSpo2(): ByteArray = query(CrpRingConstants.CMD_QUERY_MANUAL_SPO2)

    private fun query(command: Int, vararg payload: Byte): ByteArray =
        CrpRingFrame.build(CrpRingConstants.GROUP_QUERY, command, payload)
}
