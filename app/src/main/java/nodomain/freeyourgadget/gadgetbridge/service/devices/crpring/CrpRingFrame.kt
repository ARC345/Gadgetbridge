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

/** One complete frame from the band, without its 6-byte header. */
class CrpRingMessage(val group: Int, val command: Int, val payload: ByteArray) {
    override fun toString(): String =
        "CrpRingMessage(group=$group, command=$command, payload=${payload.joinToString("") { "%02x".format(it) }})"
}

/** Frame format: FD DA <flags> <len> <group> <cmd> <payload>. Bit 0 of flags is bit 8 of len. */
object CrpRingFrame {
    const val HEADER_LENGTH = 6
    const val MAX_LENGTH = 511
    private const val MAGIC_0 = 0xFD.toByte()
    private const val MAGIC_1 = 0xDA.toByte()
    private const val FLAGS_PHONE = 0x10

    fun build(group: Int, command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = HEADER_LENGTH + payload.size
        require(length <= MAX_LENGTH) { "Frame too long: $length" }
        val frame = ByteArray(length)
        frame[0] = MAGIC_0
        frame[1] = MAGIC_1
        frame[2] = (FLAGS_PHONE or ((length shr 8) and 1)).toByte()
        frame[3] = (length and 0xFF).toByte()
        frame[4] = group.toByte()
        frame[5] = command.toByte()
        payload.copyInto(frame, HEADER_LENGTH)
        return frame
    }

    /** The full frame length if [bytes] starts a valid frame, else null. */
    fun frameLength(bytes: ByteArray): Int? {
        if (bytes.size < HEADER_LENGTH || bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
        val flags = bytes[2].toInt() and 0xFF
        val kind = flags and 0xFE
        if (kind != 0x10 && kind != 0x20) return null
        val length = ((flags and 1) shl 8) or (bytes[3].toInt() and 0xFF)
        return if (length in HEADER_LENGTH..MAX_LENGTH) length else null
    }

    fun parse(frame: ByteArray): CrpRingMessage = CrpRingMessage(
        frame[4].toInt() and 0xFF,
        frame[5].toInt() and 0xFF,
        frame.copyOfRange(HEADER_LENGTH, frame.size)
    )

    fun split(frame: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0) { "Bad chunk size: $chunkSize" }
        return (frame.indices step chunkSize).map { frame.copyOfRange(it, minOf(it + chunkSize, frame.size)) }
    }
}

/** Joins notification parts into frames. Not thread-safe. */
class CrpRingFrameAssembler(private val timeoutMillis: Long = 2000) {
    private var buffer: ByteArray? = null
    private var filled = 0
    private var startedAt = 0L

    fun add(part: ByteArray, nowMillis: Long): CrpRingMessage? {
        if (part.isEmpty()) return null
        if (buffer != null && nowMillis - startedAt > timeoutMillis) reset()
        val length = CrpRingFrame.frameLength(part)
        if (length != null) {
            buffer = ByteArray(length)
            filled = 0
            startedAt = nowMillis
        } else if (buffer == null) {
            return null
        }
        val target = buffer!!
        if (part.size > target.size - filled) {
            reset()
            return null
        }
        part.copyInto(target, filled)
        filled += part.size
        if (filled < target.size) return null
        reset()
        return CrpRingFrame.parse(target)
    }

    fun reset() {
        buffer = null
        filled = 0
    }
}
