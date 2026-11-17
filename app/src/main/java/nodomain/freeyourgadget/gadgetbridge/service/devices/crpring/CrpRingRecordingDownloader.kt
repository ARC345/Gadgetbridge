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

class CrpRingRecording(val name: String, val data: ByteArray)

/**
 * Downloads the voice notes that are not yet in [alreadyDownloaded], one at a time, as the
 * vendor app does: list → size → chunks of 4096 bytes (data on fdd6) → CRC. The caller gives
 * group 10 replies to [handle], fdd6 bytes to [onData], and calls [onTimeout] when the band
 * is silent. Not thread-safe.
 */
class CrpRingRecordingDownloader(
    private val alreadyDownloaded: Set<String>,
    private val send: (ByteArray) -> Unit,
) {
    companion object {
        const val CHUNK_SIZE = 4096
        /** 100 minutes at 16 kbit/s is 12 MB; anything far above that is not a voice note. */
        const val MAX_FILE_SIZE = 64L * 1024 * 1024
    }

    private enum class State { IDLE, LISTING, SIZE, DATA, CRC, DONE }

    private var state = State.IDLE
    private val listed = ArrayList<String>()
    private var queue: List<String> = emptyList()
    private var next = 0
    private var current: String? = null
    private var buffer = ByteArray(0)
    private var received = 0
    private var chunkExpected = 0
    private var chunkReceived = 0
    private var crc = CrpRingCrc16.VENDOR_START

    val queued: List<String> get() = queue
    val finished = mutableListOf<CrpRingRecording>()
    val failed = mutableListOf<String>()
    val isFinished: Boolean get() = state == State.DONE

    fun start() {
        state = State.LISTING
        send(CrpRingRecordingCommands.listFiles())
    }

    fun handle(message: CrpRingMessage): Boolean {
        if (message.group != CrpRingRecordingCommands.GROUP) return false
        return when (state) {
            State.LISTING -> {
                if (message.command != CrpRingRecordingCommands.CMD_LIST_FILES) return false
                val page = CrpRingRecordingParsers.fileListPage(message.payload) ?: return true
                listed.addAll(page.names)
                if (page.remaining <= 0) startDownloads()
                true
            }
            State.SIZE, State.DATA, State.CRC -> {
                if (message.command != CrpRingRecordingCommands.CMD_TRANSFER) return false
                val reply = CrpRingRecordingParsers.transferReply(message.payload) ?: return false
                if (reply.name != current) return false
                when (reply.kind) {
                    1 -> if (state == State.SIZE) onSize(reply.value)
                    3 -> if (state == State.CRC) onCrcResult(reply.value > 0)
                }
                true
            }
            else -> false
        }
    }

    fun onData(bytes: ByteArray) {
        if (state != State.DATA) return
        val n = minOf(bytes.size, chunkExpected - chunkReceived)
        if (n <= 0) return
        bytes.copyInto(buffer, received, 0, n)
        crc = CrpRingCrc16.compute(if (n == bytes.size) bytes else bytes.copyOf(n), crc)
        received += n
        chunkReceived += n
        if (chunkReceived < chunkExpected) return
        if (received < buffer.size) {
            requestChunk()
        } else {
            state = State.CRC
            send(CrpRingRecordingCommands.sendCrc(current!!, crc))
        }
    }

    fun onTimeout() {
        when (state) {
            State.LISTING -> startDownloads()
            State.SIZE, State.DATA, State.CRC -> failCurrent(sendEnd = true)
            else -> {}
        }
    }

    private fun startDownloads() {
        queue = listed.filter { it !in alreadyDownloaded }.distinct()
        next = 0
        startNext()
    }

    private fun startNext() {
        if (next >= queue.size) {
            state = State.DONE
            current = null
            return
        }
        val name = queue[next++]
        current = name
        state = State.SIZE
        send(CrpRingRecordingCommands.querySize(name))
    }

    private fun onSize(size: Long) {
        if (size <= 0 || size > MAX_FILE_SIZE) {
            failCurrent(sendEnd = false)
            return
        }
        buffer = ByteArray(size.toInt())
        received = 0
        crc = CrpRingCrc16.VENDOR_START
        requestChunk()
    }

    private fun requestChunk() {
        chunkExpected = minOf(CHUNK_SIZE, buffer.size - received)
        chunkReceived = 0
        state = State.DATA
        send(CrpRingRecordingCommands.requestData(current!!, received, chunkExpected))
    }

    private fun onCrcResult(ok: Boolean) {
        if (ok) {
            finished.add(CrpRingRecording(current!!, buffer))
            buffer = ByteArray(0)
            startNext()
        } else {
            failCurrent(sendEnd = true)
        }
    }

    private fun failCurrent(sendEnd: Boolean) {
        val name = current ?: return
        failed.add(name)
        buffer = ByteArray(0)
        if (sendEnd) send(CrpRingRecordingCommands.endTransfer(name, false))
        startNext()
    }
}
