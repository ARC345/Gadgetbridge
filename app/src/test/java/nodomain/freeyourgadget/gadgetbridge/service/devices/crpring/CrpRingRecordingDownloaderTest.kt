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
    private fun crcResult(name: String, ok: Boolean) = reply(5, bytes(3, if (ok) 1 else 0) + name15(name))

    private fun downloader(done: Set<String> = emptySet()) = CrpRingRecordingDownloader(done) { sent.add(it) }

    @Test
    fun startListsFiles() {
        val d = downloader()
        d.start()
        assertArrayEquals(CrpRingRecordingCommands.listFiles(), sent.last())
    }

    @Test
    fun emptyListFinishes() {
        val d = downloader()
        d.start()
        assertTrue(d.handle(list()))
        assertTrue(d.isFinished)
    }

    @Test
    fun downloadsOneFileInChunks() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val d = downloader()
        d.start()
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
        d.handle(crcResult("A.opus", true))
        assertTrue(d.isFinished)
        assertEquals(1, d.finished.size)
        assertEquals("A.opus", d.finished[0].name)
        assertArrayEquals(data, d.finished[0].data)
    }

    @Test
    fun skipsFilesAlreadyDownloaded() {
        val d = downloader(setOf("A.opus"))
        d.start()
        d.handle(list("A.opus", "B.opus"))
        assertArrayEquals(CrpRingRecordingCommands.querySize("B.opus"), sent.last())
    }

    @Test
    fun listOverSeveralFrames() {
        val d = downloader()
        d.start()
        assertTrue(d.handle(list("A.opus", remaining = 1)))
        assertEquals(1, sent.size)
        assertTrue(d.handle(list("B.opus")))
        assertArrayEquals(CrpRingRecordingCommands.querySize("A.opus"), sent.last())
        assertEquals(listOf("A.opus", "B.opus"), d.queued)
    }

    @Test
    fun crcFailureDropsTheFileAndContinues() {
        val d = downloader()
        d.start()
        d.handle(list("A.opus", "B.opus"))
        d.handle(size("A.opus", 4))
        d.handle(info("A.opus", 4))
        d.onData(bytes(1, 2, 3, 4))
        d.handle(crcResult("A.opus", false))
        assertTrue(d.finished.isEmpty())
        assertEquals(listOf("A.opus"), d.failed)
        assertArrayEquals(CrpRingRecordingCommands.querySize("B.opus"), sent.last())
        assertArrayEquals(CrpRingRecordingCommands.endTransfer("A.opus", false), sent[sent.size - 2])
    }

    @Test
    fun missingFileIsSkipped() {
        val d = downloader()
        d.start()
        d.handle(list("A.opus"))
        d.handle(size("A.opus", 0))
        assertEquals(listOf("A.opus"), d.failed)
        assertTrue(d.isFinished)
    }

    @Test
    fun timeoutDuringTransferEndsItAndContinues() {
        val d = downloader()
        d.start()
        d.handle(list("A.opus", "B.opus"))
        d.handle(size("A.opus", 4))
        d.onTimeout()
        assertEquals(listOf("A.opus"), d.failed)
        assertArrayEquals(CrpRingRecordingCommands.querySize("B.opus"), sent.last())
    }

    @Test
    fun timeoutDuringListUsesTheNamesSoFar() {
        val d = downloader()
        d.start()
        d.handle(list("A.opus", remaining = 2))
        d.onTimeout()
        assertArrayEquals(CrpRingRecordingCommands.querySize("A.opus"), sent.last())
    }

    @Test
    fun extraDataIsCutAtTheChunkSize() {
        val d = downloader()
        d.start()
        d.handle(list("A.opus"))
        d.handle(size("A.opus", 3))
        d.handle(info("A.opus", 3))
        d.onData(bytes(1, 2, 3, 4, 5))
        assertArrayEquals(CrpRingRecordingCommands.sendCrc("A.opus", CrpRingCrc16.compute(bytes(1, 2, 3))), sent.last())
    }

    @Test
    fun repliesForAnotherFileAreIgnored() {
        val d = downloader()
        d.start()
        d.handle(list("A.opus"))
        assertFalse(d.handle(size("B.opus", 10)))
        assertFalse(d.handle(CrpRingMessage(2, 18, bytes(0))))
    }
}
