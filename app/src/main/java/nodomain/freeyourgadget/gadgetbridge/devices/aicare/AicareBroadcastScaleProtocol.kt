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

/**
 * Decodes the advertisements of AICare (AILink) "broadcast LE" weight scales, sold for example as
 * the Fitelo smart scale (advertised name "tzc"). These scales never accept a connection: they put
 * each reading in the manufacturer specific data of their advertisement. The full payload is 15
 * bytes, including the two bytes that Android reads as the manufacturer id:
 *
 * ```
 * 0      header (0xc0 seen)
 * 1      counter, increments for each live reading and stays fixed while the reading is stable
 * 2-3    weight, big-endian, scaled by the unit (see [weightKg])
 * 4-5    impedance, big-endian; a constant 5000 on scales without a body fat sensor
 * 6-7    device type, 0x0002
 * 8      flags: bit 0 stable, bits 3-4 unit (0 kg, 1 jin, 2 lb, 3 st:lb)
 * 9-14   MAC address of the scale
 * ```
 */
object AicareBroadcastScaleProtocol {
    private const val PAYLOAD_LENGTH = 15
    private const val DEVICE_TYPE_WEIGHT_SCALE = 0x0002

    private const val UNIT_KG = 0
    private const val UNIT_JIN = 1
    private const val UNIT_LB = 2
    private const val UNIT_ST_LB = 3

    private const val KG_PER_LB = 0.45359237f

    /** The body composition estimates are only valid for impedances in this range. */
    private const val MIN_IMPEDANCE_OHM = 200
    private const val MAX_IMPEDANCE_OHM = 1200

    data class Reading(
        val counter: Int,
        val weightKg: Float,
        val stable: Boolean,
        /** null when the scale did not measure a plausible body impedance */
        val impedanceOhm: Int?,
    )

    /**
     * @param manufacturerId the key of the manufacturer specific data, i.e. bytes 0-1 of the payload
     * @param data the value of the manufacturer specific data, i.e. bytes 2-14 of the payload
     * @param deviceAddress the address the advertisement came from, which the payload repeats
     * @return the reading, or null when the data is not from an AICare broadcast scale at this address
     */
    @JvmStatic
    fun parse(manufacturerId: Int, data: ByteArray, deviceAddress: String): Reading? {
        val payload = byteArrayOf(manufacturerId.toByte(), (manufacturerId shr 8).toByte()) + data
        if (payload.size != PAYLOAD_LENGTH) {
            return null
        }
        if (uint16(payload, 6) != DEVICE_TYPE_WEIGHT_SCALE) {
            return null
        }
        if (!payload.copyOfRange(9, 15).contentEquals(macBytes(deviceAddress))) {
            return null
        }

        val flags = payload[8].toInt() and 0xff
        val weightKg = weightKg(uint16(payload, 2), (flags shr 3) and 0x03)
        if (weightKg <= 0f) {
            return null
        }
        val impedance = uint16(payload, 4)

        return Reading(
            counter = payload[1].toInt() and 0xff,
            weightKg = weightKg,
            stable = flags and 0x01 != 0,
            impedanceOhm = impedance.takeIf { it in MIN_IMPEDANCE_OHM..MAX_IMPEDANCE_OHM },
        )
    }

    private fun weightKg(raw: Int, unit: Int): Float {
        return when (unit) {
            UNIT_KG -> raw / 100f
            UNIT_JIN -> raw / 100f * 0.5f
            UNIT_LB -> raw / 10f * KG_PER_LB
            // high byte stones, low byte tenths of a pound
            UNIT_ST_LB -> ((raw shr 8) * 14 + (raw and 0xff) / 10f) * KG_PER_LB
            else -> 0f
        }
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun macBytes(address: String): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) {
            return null
        }
        val bytes = parts.map { it.toIntOrNull(16) ?: return null }
        return ByteArray(6) { bytes[it].toByte() }
    }
}
