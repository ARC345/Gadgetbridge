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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrpRingRecordingDownloaderTest {
    private val sent = mutableListOf<ByteArray>()
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun name15(text: String) = text.toByteArray().copyOf(15)
    private fun le32(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF)
    private fun reply(cmd: Int, payload: ByteArray) = CrpRingMessage(10, cmd, payload)
    private fun list(vararg names: String, remaining: Int = 0) =
        reply(3, bytes(names.size, 0, remaining, 0) + names.fold(ByteArray(0)) { acc, n -> acc + name15(n) })
    private fun size(name: String, size: Int) = reply(5, bytes(1) + le32(size) + name15(name))
    private fun info(name: String, size: Int) = reply(5, bytes(2) + le32(size) + name15(name))
    private fun crcResult(name: String, result: Int) = reply(5, bytes(3, result) + name15(name))
    private fun storage(has: Boolean) = reply(11, bytes(if (has) 1 else 0))
    private fun state(state: Int) = reply(0, bytes(state, 0, 0, 0, 0, 0, 0, 0, 0))

    private fun downloader(done: Set<String> = emptySet()) = CrpRingRecordingDownloader(done) { sent.add(it) }

    /** Starts the downloader and passes the storage and recording state checks. */
    private fun started(done: Set<String> = emptySet()) = downloader(done).also {
        it.start()
        assertTrue(it.handle(storage(true)))
        assertTrue(it.handle(state(0)))
    }

    @Test
    fun startChecksStorageThenStateThenLists() {
        val d = downloader()
        d.start()
        assertArrayEquals(CrpRingRecordingCommands.queryStorage(), sent.last())
        d.handle(storage(true))
        assertArrayEquals(CrpRingRecordingCommands.queryState(), sent.last())
        d.handle(state(0))
        assertArrayEquals(CrpRingRecordingCommands.listFiles(), sent.last())
    }

    @Test
    fun noStorageSkipsTheDownload() {
        val d = downloader()
        d.start()
        d.handle(storage(false))
        assertTrue(d.isFinished)
        assertNotNull(d.skipReason)
        assertEquals(1, sent.size)
    }

    @Test
    fun recordingInProgressSkipsTheDownload() {
        val d = downloader()
        d.start()
        d.handle(storage(true))
        d.handle(state(1))
        assertTrue(d.isFinished)
        assertNotNull(d.skipReason)
        assertEquals(2, sent.size)
    }

    @Test
    fun noReplyToTheChecksSkipsTheDownload() {
        val d = downloader()
        d.start()
        d.onTimeout()
        assertTrue(d.isFinished)
        assertNotNull(d.skipReason)
    }

    @Test
    fun emptyListFinishes() {
        val d = started()
        assertTrue(d.handle(list()))
        assertTrue(d.isFinished)
        assertNull(d.skipReason)
    }

    @Test
    fun downloadsOneFileInChunks() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val d = started()
        d.handle(list("A.opus"))
        assertArrayEquals(CrpRingRecordingCommands.querySize("A.opus"), sent.last())
        d.handle(size("A.opus", 5000))
        assertArrayEquals(CrpRingRecordingCommands.requestData("A.opus", 0, 4096), sent.last())
        d.handle(info("A.opus", 4096))
        d.onData(data.copyOfRange(0, 2000))
        d.onData(data.copyOfRange(2000, 4096))
        assertArrayEquals(CrpRingRecordingCommands.requestData("A.opus", 4096, 904), sent.last())
        d.handle(info("A.opus", 904))
        d.onData(data.copyOfRange(4096, 5000))
        assertArrayEquals(CrpRingRecordingCommands.sendCrc("A.opus", CrpRingCrc16.compute(data)), sent.last())
        assertFalse(d.isFinished)
        d.handle(crcResult("A.opus", 1))
        assertTrue(d.isFinished)
        assertEquals(1, d.finished.size)
        assertEquals("A.opus", d.finished[0].name)
        assertArrayEquals(data, d.finished[0].data)
    }

    @Test
    fun skipsFilesAlreadyDownloaded() {
        val d = started(setOf("A.opus"))
        d.handle(list("A.opus", "B.opus"))
        assertArrayEquals(CrpRingRecordingCommands.querySize("B.opus"), sent.last())
    }

    @Test
    fun listOverSeveralFrames() {
        val d = started()
        val before = sent.size
        assertTrue(d.handle(list("A.opus", remaining = 1)))
        assertEquals(before, sent.size)
        assertTrue(d.handle(list("B.opus")))
        assertArrayEquals(CrpRingRecordingCommands.querySize("A.opus"), sent.last())
        assertEquals(listOf("A.opus", "B.opus"), d.queued)
    }

    @Test
    fun crcFailureDropsTheFileAndContinues() {
        val d = started()
        d.handle(list("A.opus", "B.opus"))
        d.handle(size("A.opus", 4))
        d.handle(info("A.opus", 4))
        d.onData(bytes(1, 2, 3, 4))
        d.handle(crcResult("A.opus", 0))
        assertTrue(d.finished.isEmpty())
        assertEquals(listOf("A.opus"), d.failed)
        assertArrayEquals(CrpRingRecordingCommands.querySize("B.opus"), sent.last())
    }

    @Test
    fun crcResultFfIsAFailure() {
        // The vendor reads the result as a signed byte: 0xFF is -1, so a failure.
        val d = started()
        d.handle(list("A.opus"))
        d.handle(size("A.opus", 4))
        d.onData(bytes(1, 2, 3, 4))
        d.handle(crcResult("A.opus", 0xFF))
        assertTrue(d.finished.isEmpty())
        assertEquals(listOf("A.opus"), d.failed)
    }

    @Test
    fun missingFileIsSkipped() {
        val d = started()
        d.handle(list("A.opus"))
        d.handle(size("A.opus", 0))
        assertEquals(listOf("A.opus"), d.failed)
        assertTrue(d.isFinished)
    }

    @Test
    fun oversizedFileIsSkipped() {
        val d = started()
        d.handle(list("A.opus"))
        d.handle(size("A.opus", (CrpRingRecordingDownloader.MAX_FILE_SIZE + 1).toInt()))
        assertEquals(listOf("A.opus"), d.failed)
        assertEquals(16L * 1024 * 1024, CrpRingRecordingDownloader.MAX_FILE_SIZE)
    }

    @Test
    fun timeoutDuringTransferEndsItAndContinues() {
        val d = started()
        d.handle(list("A.opus", "B.opus"))
        d.handle(size("A.opus", 4))
        d.onTimeout()
        assertEquals(listOf("A.opus"), d.failed)
        assertArrayEquals(CrpRingRecordingCommands.querySize("B.opus"), sent.last())
        assertArrayEquals(CrpRingRecordingCommands.endTransfer("A.opus", false), sent[sent.size - 2])
    }

    @Test
    fun timeoutDuringListUsesTheNamesSoFar() {
        val d = started()
        d.handle(list("A.opus", remaining = 2))
        d.onTimeout()
        assertArrayEquals(CrpRingRecordingCommands.querySize("A.opus"), sent.last())
    }

    @Test
    fun extraDataIsCutAtTheChunkSize() {
        val d = started()
        d.handle(list("A.opus"))
        d.handle(size("A.opus", 3))
        d.handle(info("A.opus", 3))
        d.onData(bytes(1, 2, 3, 4, 5))
        assertArrayEquals(CrpRingRecordingCommands.sendCrc("A.opus", CrpRingCrc16.compute(bytes(1, 2, 3))), sent.last())
    }

    @Test
    fun repliesForAnotherFileAreIgnored() {
        val d = started()
        d.handle(list("A.opus"))
        assertFalse(d.handle(size("B.opus", 10)))
        assertFalse(d.handle(CrpRingMessage(2, 18, bytes(0))))
    }
}
