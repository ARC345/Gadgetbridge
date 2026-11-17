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
 * vendor app does: storage check → recording state → list → for each new name the transfer:
 * size → chunks of 4096 bytes (data on fdd6) → CRC.
 *
 * On a band without file storage ("stream-only", as the Qore Vox), a listed name is virtual:
 * the band is first asked which stored files hold it (10,5 kind 5), each of these segments is
 * transferred, and the segments are joined in frame order.
 *
 * A band that records at the moment is skipped; [skipReason] then says why. The caller gives
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
        const val MAX_FILE_SIZE = 16L * 1024 * 1024
        private const val KIND_MAPPING = 5
    }

    private enum class State { IDLE, STORAGE, RECORDING_STATE, LISTING, MAPPING, SIZE, DATA, CRC, DONE }

    private var state = State.IDLE
    private var streamOnly = false
    private val listed = ArrayList<String>()
    private var queue: List<String> = emptyList()
    private var next = 0

    /** The note (listed name) that is being downloaded. */
    private var current: String? = null
    /** The band file of the transfer in progress: the note itself, or one of its segments. */
    private var transferName: String? = null

    private val mapping = ArrayList<CrpRingSegment>()
    private var segments: List<CrpRingSegment> = emptyList()
    private var segmentIndex = 0
    private val parts = ArrayList<ByteArray>()

    private var buffer = ByteArray(0)
    private var received = 0
    private var chunkExpected = 0
    private var chunkReceived = 0
    private var crc = CrpRingCrc16.VENDOR_START

    val queued: List<String> get() = queue
    val finished = mutableListOf<CrpRingRecording>()
    val failed = mutableListOf<String>()
    val isFinished: Boolean get() = state == State.DONE

    /** Set when the download did not run at all. */
    var skipReason: String? = null
        private set

    fun start() {
        state = State.STORAGE
        send(CrpRingRecordingCommands.queryStorage())
    }

    fun handle(message: CrpRingMessage): Boolean {
        if (message.group != CrpRingRecordingCommands.GROUP) return false
        return when (state) {
            State.STORAGE -> {
                if (message.command != CrpRingRecordingCommands.CMD_STORAGE || message.payload.isEmpty()) return false
                streamOnly = message.payload[0].toInt() != 1
                state = State.RECORDING_STATE
                send(CrpRingRecordingCommands.queryState())
                true
            }
            State.RECORDING_STATE -> {
                if (message.command != CrpRingRecordingCommands.CMD_STATE) return false
                val recordingState = CrpRingRecordingParsers.recordingState(message.payload) ?: return true
                if (recordingState == 0) {
                    state = State.LISTING
                    send(CrpRingRecordingCommands.listFiles())
                } else {
                    skip("a recording is in progress (state $recordingState)")
                }
                true
            }
            State.LISTING -> {
                if (message.command != CrpRingRecordingCommands.CMD_LIST_FILES) return false
                val page = CrpRingRecordingParsers.fileListPage(message.payload) ?: return true
                listed.addAll(page.names)
                if (page.remaining <= 0) startDownloads()
                true
            }
            State.MAPPING -> {
                if (message.command != CrpRingRecordingCommands.CMD_TRANSFER) return false
                val page = CrpRingRecordingParsers.fileMappingPage(message.payload) ?: return false
                if (page.notFound) {
                    // The band can still be writing the note; it is tried again at the next sync.
                    failCurrent(sendEnd = false)
                    return true
                }
                mapping.addAll(page.segments)
                if (page.remaining <= 0) startSegments()
                true
            }
            State.SIZE, State.DATA, State.CRC -> {
                if (message.command != CrpRingRecordingCommands.CMD_TRANSFER) return false
                val reply = CrpRingRecordingParsers.transferReply(message.payload) ?: return false
                if (reply.name != transferName) return false
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
            send(CrpRingRecordingCommands.sendCrc(transferName!!, crc))
        }
    }

    fun onTimeout() {
        when (state) {
            State.STORAGE, State.RECORDING_STATE -> skip("no reply to the storage or recording state check")
            State.LISTING -> startDownloads()
            State.MAPPING -> failCurrent(sendEnd = false)
            State.SIZE, State.DATA, State.CRC -> failCurrent(sendEnd = true)
            else -> {}
        }
    }

    private fun skip(reason: String) {
        skipReason = reason
        state = State.DONE
    }

    private fun startDownloads() {
        queue = listed.filter { it !in alreadyDownloaded }.distinct()
        next = 0
        startNext()
    }

    private fun startNext() {
        parts.clear()
        buffer = ByteArray(0)
        if (next >= queue.size) {
            state = State.DONE
            current = null
            transferName = null
            return
        }
        val name = queue[next++]
        current = name
        if (streamOnly) {
            mapping.clear()
            transferName = null
            state = State.MAPPING
            send(CrpRingRecordingCommands.queryFileMapping(name))
        } else {
            startTransfer(name)
        }
    }

    private fun startSegments() {
        segments = mapping.sortedBy { it.startFrame }
        segmentIndex = 0
        if (segments.isEmpty()) {
            failCurrent(sendEnd = false)
            return
        }
        startTransfer(segments[0].name)
    }

    private fun startTransfer(name: String) {
        transferName = name
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
        send(CrpRingRecordingCommands.requestData(transferName!!, received, chunkExpected))
    }

    private fun onCrcResult(ok: Boolean) {
        if (!ok) {
            // The vendor app sends no end command after a failed CRC.
            failCurrent(sendEnd = false)
            return
        }
        if (!streamOnly) {
            finished.add(CrpRingRecording(current!!, buffer))
            startNext()
            return
        }
        parts.add(buffer)
        buffer = ByteArray(0)
        segmentIndex++
        if (segmentIndex < segments.size) {
            startTransfer(segments[segmentIndex].name)
            return
        }
        val total = parts.sumOf { it.size.toLong() }
        if (total > MAX_FILE_SIZE) {
            failCurrent(sendEnd = false)
            return
        }
        val joined = ByteArray(total.toInt())
        var offset = 0
        for (part in parts) {
            part.copyInto(joined, offset)
            offset += part.size
        }
        finished.add(CrpRingRecording(current!!, joined))
        startNext()
    }

    private fun failCurrent(sendEnd: Boolean) {
        val name = current ?: return
        failed.add(name)
        val transfer = transferName
        if (sendEnd && transfer != null) send(CrpRingRecordingCommands.endTransfer(transfer, false))
        startNext()
    }
}
