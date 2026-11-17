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
    private fun mapping(remaining: Int, vararg segments: Triple<Int, Int, String>) =
        reply(5, bytes(5, segments.size, 0, remaining, 0) +
            segments.fold(ByteArray(0)) { acc, s -> acc + le32(s.first) + le32(s.second) + name15(s.third) })
    private fun notFound() = reply(5, bytes(5, 0xFF, 0xFF, 0xFF, 0xFF))

    /** Starts the downloader on a band without file storage (stream-only, as the Qore Vox). */
    private fun startedStreamOnly() = downloader().also {
        it.start()
        assertTrue(it.handle(storage(false)))
        assertArrayEquals(CrpRingRecordingCommands.queryState(), sent.last())
        assertTrue(it.handle(state(0)))
    }

    /** Answers one full transfer of [data] for the band file [name]. */
    private fun transfer(d: CrpRingRecordingDownloader, name: String, data: ByteArray) {
        assertArrayEquals(CrpRingRecordingCommands.querySize(name), sent.last())
        assertTrue(d.handle(size(name, data.size)))
        d.onData(data)
        assertArrayEquals(CrpRingRecordingCommands.sendCrc(name, CrpRingCrc16.compute(data)), sent.last())
        assertTrue(d.handle(crcResult(name, 1)))
    }
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

    @Test
    fun streamOnlyBandStillListsFiles() {
        startedStreamOnly()
        assertArrayEquals(CrpRingRecordingCommands.listFiles(), sent.last())
    }

    @Test
    fun streamOnlyMapsAndJoinsSegmentsInFrameOrder() {
        val d = startedStreamOnly()
        d.handle(list("1795000000.opus"))
        assertArrayEquals(CrpRingRecordingCommands.queryFileMapping("1795000000.opus"), sent.last())
        // Segments arrive out of frame order: SEG2 holds frames 100..149, SEG1 holds 0..99.
        assertTrue(d.handle(mapping(0, Triple(100, 149, "SEG2"), Triple(0, 99, "SEG1"))))
        val first = ByteArray(4000) { 1 }
        val second = ByteArray(2000) { 2 }
        transfer(d, "SEG1", first)
        transfer(d, "SEG2", second)
        assertTrue(d.isFinished)
        assertEquals(1, d.finished.size)
        assertEquals("1795000000.opus", d.finished[0].name)
        assertArrayEquals(first + second, d.finished[0].data)
    }

    @Test
    fun mappingOverSeveralPages() {
        val d = startedStreamOnly()
        d.handle(list("A.opus"))
        val before = sent.size
        assertTrue(d.handle(mapping(1, Triple(0, 9, "SEG1"))))
        assertEquals(before, sent.size)
        assertTrue(d.handle(mapping(0, Triple(10, 19, "SEG2"))))
        assertArrayEquals(CrpRingRecordingCommands.querySize("SEG1"), sent.last())
    }

    @Test
    fun mappingNotFoundFailsTheNoteForNow() {
        val d = startedStreamOnly()
        d.handle(list("A.opus", "B.opus"))
        assertTrue(d.handle(notFound()))
        assertEquals(listOf("A.opus"), d.failed)
        assertArrayEquals(CrpRingRecordingCommands.queryFileMapping("B.opus"), sent.last())
    }

    @Test
    fun invalidSegmentsAreIgnored() {
        val d = startedStreamOnly()
        d.handle(list("A.opus"))
        d.handle(mapping(0, Triple(-1, -1, "BAD"), Triple(0, 9, "SEG1")))
        assertArrayEquals(CrpRingRecordingCommands.querySize("SEG1"), sent.last())
    }

    @Test
    fun aFailedSegmentFailsTheWholeNote() {
        val d = startedStreamOnly()
        d.handle(list("A.opus"))
        d.handle(mapping(0, Triple(0, 9, "SEG1"), Triple(10, 19, "SEG2")))
        d.handle(size("SEG1", 4))
        d.onData(bytes(1, 2, 3, 4))
        d.handle(crcResult("SEG1", 0))
        assertTrue(d.finished.isEmpty())
        assertEquals(listOf("A.opus"), d.failed)
        assertTrue(d.isFinished)
    }

    @Test
    fun mappingTimeoutFailsTheNote() {
        val d = startedStreamOnly()
        d.handle(list("A.opus"))
        d.onTimeout()
        assertEquals(listOf("A.opus"), d.failed)
        assertTrue(d.isFinished)
    }
}
