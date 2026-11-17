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

/**
 * The Nordic CRC-16 (crc16_compute) that the band uses to check a recording transfer. The
 * vendor app starts it at 0xFDDA, not at the usual 0xFFFF.
 */
object CrpRingCrc16 {
    const val VENDOR_START = 0xFDDA

    fun compute(data: ByteArray, start: Int = VENDOR_START): Int {
        var crc = start and 0xFFFF
        for (b in data) {
            crc = ((crc ushr 8) or (crc shl 8)) and 0xFFFF
            crc = crc xor (b.toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = (crc xor (crc shl 12)) and 0xFFFF
            crc = (crc xor ((crc and 0xFF) shl 5)) and 0xFFFF
        }
        return crc
    }
}
