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

data class CrpRingFileListPage(val names: List<String>, val remaining: Int)

/**
 * A 10,5 reply: kind 1 = file size, 2 = chunk size, 3 = CRC result, 0 = state. For kinds 3 and 0
 * the value is the signed result byte, as the vendor reads it: value > 0 is OK, 0xFF (-1) is not.
 */
data class CrpRingTransferReply(val kind: Int, val value: Long, val name: String)

/** Group 10: voice note files on the band. A file name is always 15 bytes, padded with zeros. */
object CrpRingRecordingCommands {
    const val GROUP = 10
    const val CMD_STATE = 0
    const val CMD_LIST_FILES = 3
    const val CMD_TRANSFER = 5
    const val CMD_STORAGE = 11

    fun name15(name: String): ByteArray = name.toByteArray(Charsets.UTF_8).copyOf(15)

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    fun listFiles(): ByteArray = CrpRingFrame.build(GROUP, CMD_LIST_FILES)

    fun queryState(): ByteArray = CrpRingFrame.build(GROUP, CMD_STATE)

    fun queryStorage(): ByteArray = CrpRingFrame.build(GROUP, CMD_STORAGE)

    fun querySize(name: String): ByteArray = CrpRingFrame.build(GROUP, CMD_TRANSFER, byteArrayOf(1) + name15(name))

    fun requestData(name: String, offset: Int, length: Int): ByteArray =
        CrpRingFrame.build(GROUP, CMD_TRANSFER, byteArrayOf(2) + le32(offset) + le32(length) + name15(name))

    /** The CRC is big-endian here, unlike all other numbers. */
    fun sendCrc(name: String, crc: Int): ByteArray =
        CrpRingFrame.build(GROUP, CMD_TRANSFER, byteArrayOf(3, (crc shr 8).toByte(), crc.toByte()) + name15(name))

    fun endTransfer(name: String, success: Boolean): ByteArray =
        CrpRingFrame.build(GROUP, CMD_TRANSFER, byteArrayOf(4, if (success) 1 else 0xFF.toByte()) + name15(name))
}

object CrpRingRecordingParsers {
    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    fun name(payload: ByteArray, offset: Int): String? {
        if (offset < 0 || offset + 15 > payload.size) return null
        return String(payload, offset, 15, Charsets.UTF_8).trimEnd('\u0000').trim()
    }

    /** 10,3: [count u16, remaining u16, name * count]. More pages follow while remaining > 0. */
    fun fileListPage(payload: ByteArray): CrpRingFileListPage? {
        if (payload.size < 4) return null
        val count = u16(payload, 0)
        val names = ArrayList<String>()
        var offset = 4
        for (i in 0 until count) {
            val name = name(payload, offset) ?: break
            if (name.isNotEmpty()) names.add(name)
            offset += 15
        }
        return CrpRingFileListPage(names, u16(payload, 2))
    }

    /** 10,0: [state, u32, u32, (u32)]. State 0 = none, 1 = recording, 2 = paused. */
    fun recordingState(payload: ByteArray): Int? = if (payload.size < 9) null else u8(payload, 0)

    /** A band file name made safe for the file system: no path parts, no unusual characters. */
    fun safeFileName(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.isEmpty() || safe.all { it == '.' }) "voice-note" else safe
    }

    fun transferReply(payload: ByteArray): CrpRingTransferReply? {
        if (payload.isEmpty()) return null
        return when (val kind = u8(payload, 0)) {
            1, 2 -> name(payload, 5)?.let { CrpRingTransferReply(kind, u32(payload, 1), it) }
            0, 3 -> name(payload, 2)?.let { CrpRingTransferReply(kind, payload[1].toLong(), it) }
            else -> null
        }
    }
}
