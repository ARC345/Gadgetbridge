# Pebble Qore Vox Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the Pebble Qore Vox band to Gadgetbridge and sync steps, sleep, heart rate and SpO2.

**Architecture:** A new "CRP ring" driver family. Pure Kotlin classes (frame codec, time conversion, parsers, commands, fetch state machine) hold all protocol logic and have JVM unit tests. Thin Android classes (device support, database store, coordinator, sample provider) connect them to Gadgetbridge.

**Tech Stack:** Kotlin, Android BLE through `AbstractBTLESingleDeviceSupport`, greenDAO entities from `GBDaoGenerator`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-16-pebble-qore-vox-design.md`

## Global Constraints

- Packages: `nodomain.freeyourgadget.gadgetbridge.devices.crpring` and `nodomain.freeyourgadget.gadgetbridge.service.devices.crpring`.
- Every new source file starts with the AGPL header from `PebbleQore2Coordinator.java` (`Copyright (C) 2026 Arnav Rastogi`).
- Frame: `FD DA <flags> <len> <group> <cmd> <payload>`, length 6..511, all numbers little-endian.
- Device timestamps: local wall-clock time written as GMT+8, in seconds.
- History: 7 days (6 = oldest, 0 = today). Each step has a 5 s timeout. A partial frame expires after 2 s.
- Valid values: heart rate 40..200, SpO2 1..100, steps > 0. Never save a timestamp after "now".
- Name pattern: `^PBL Qore Vox.*`. Schema version: 155 → 156.
- Low-memory machine: run Gradle with `--max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`. Run one Gradle job at a time.

## Review Focus

1. A reply split over several notifications (MTU 23). The parts must join into one message. — Task 1, `assemblerJoinsParts`.
2. A sleep night that crosses midnight, in a zone with DST. — Task 3, `sleepAcrossMidnight` and `sleepAcrossDstChange`.
3. A band that does not reply to one command (old firmware). The fetch must skip it and continue. — Task 4, `timeoutSkipsStep`.
4. A live event (for example a heart rate value) during the fetch. It must not move the fetch forward. — Task 4, `unrelatedMessageIsNotConsumed`.
5. "Today" data with zeros for the future. No future rows. — Task 4, `futureStepSlotsAreDropped`.

---

## File Structure

| File | Responsibility |
|---|---|
| `devices/crpring/CrpRingConstants.kt` | UUIDs, groups, commands, stages, limits |
| `service/devices/crpring/CrpRingFrame.kt` | Build, split, parse, join frames |
| `service/devices/crpring/CrpRingTime.kt` | Device time conversion, start of day |
| `service/devices/crpring/CrpRingParsers.kt` | Reply parsers and sleep stage builder |
| `service/devices/crpring/CrpRingCommands.kt` | Request frames |
| `service/devices/crpring/CrpRingHistoryFetcher.kt` | Fetch state machine and result |
| `service/devices/crpring/CrpRingHistoryStore.kt` | Save a fetch result to the database |
| `service/devices/crpring/CrpRingDeviceSupport.kt` | BLE connection, init, events |
| `devices/crpring/CrpRingActivitySampleProvider.kt` | Merge steps, HR, sleep for charts |
| `devices/crpring/AbstractCrpRingCoordinator.kt` | Common capabilities |
| `devices/crpring/PebbleQoreVoxCoordinator.kt` | Pebble Qore Vox device |
| `GBDaoGenerator/.../GBDaoGenerator.java` | `CrpRingStepsSample` entity, schema 156 |
| `model/DeviceType.java` | `PEBBLE_QORE_VOX` |
| `res/values/strings.xml` | Device name, PIN message |

Paths below use `$MAIN` = `app/src/main/java/nodomain/freeyourgadget/gadgetbridge` and `$TEST` = `app/src/test/java/nodomain/freeyourgadget/gadgetbridge`.

Test command (used by Tasks 1–4):

```bash
./gradlew --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testMainlineDebugUnitTest --tests 'nodomain.freeyourgadget.gadgetbridge.service.devices.crpring.*'
```

---

### Task 1: Constants and frame codec

**Files:**
- Create: `$MAIN/devices/crpring/CrpRingConstants.kt`
- Create: `$MAIN/service/devices/crpring/CrpRingFrame.kt`
- Test: `$TEST/service/devices/crpring/CrpRingFrameTest.kt`

**Interfaces:**
- Produces: `CrpRingConstants` (all constants below); `CrpRingMessage(group: Int, command: Int, payload: ByteArray)`; `CrpRingFrame.build(group, command, payload = ByteArray(0)): ByteArray`; `CrpRingFrame.frameLength(bytes): Int?`; `CrpRingFrame.split(frame, chunkSize): List<ByteArray>`; `CrpRingFrameAssembler(timeoutMillis = 2000).add(part, nowMillis): CrpRingMessage?` and `.reset()`.

- [ ] **Step 1: Write the failing test**

```kotlin
// File: app/src/test/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingFrameTest.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrpRingFrameTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun buildAddsHeaderAndLength() {
        assertArrayEquals(
            bytes(0xFD, 0xDA, 0x10, 0x07, 0x09, 0x02, 0x01),
            CrpRingFrame.build(9, 2, bytes(1))
        )
    }

    @Test
    fun buildSetsBitEightForLongFrames() {
        val frame = CrpRingFrame.build(2, 14, ByteArray(300))
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        assertEquals(306 and 0xFF, frame[3].toInt() and 0xFF)
        assertEquals(306, CrpRingFrame.frameLength(frame))
    }

    @Test
    fun frameLengthRejectsBadFlags() {
        assertNull(CrpRingFrame.frameLength(bytes(0xFD, 0xDA, 0x40, 0x07, 0x09, 0x02, 0x01)))
        assertNull(CrpRingFrame.frameLength(bytes(0xFE, 0xEA, 0x10, 0x07, 0x09, 0x02, 0x01)))
    }

    @Test
    fun assemblerReturnsSinglePartFrame() {
        val message = CrpRingFrameAssembler().add(bytes(0xFD, 0xDA, 0x10, 0x08, 0x01, 0x29, 0x01, 0x00), 0)
        assertNotNull(message)
        assertEquals(1, message!!.group)
        assertEquals(41, message.command)
        assertArrayEquals(bytes(1, 0), message.payload)
    }

    @Test
    fun assemblerJoinsParts() {
        val payload = ByteArray(44) { it.toByte() }
        val parts = CrpRingFrame.split(CrpRingFrame.build(2, 18, payload), 20)
        assertEquals(3, parts.size)
        val assembler = CrpRingFrameAssembler()
        assertNull(assembler.add(parts[0], 0))
        assertNull(assembler.add(parts[1], 10))
        val message = assembler.add(parts[2], 20)
        assertNotNull(message)
        assertEquals(18, message!!.command)
        assertArrayEquals(payload, message.payload)
    }

    @Test
    fun assemblerDropsContinuationWithoutStart() {
        assertNull(CrpRingFrameAssembler().add(bytes(0x01, 0x02, 0x03), 0))
    }

    @Test
    fun assemblerDropsStalePartialFrame() {
        val parts = CrpRingFrame.split(CrpRingFrame.build(2, 18, ByteArray(30)), 20)
        val assembler = CrpRingFrameAssembler()
        assertNull(assembler.add(parts[0], 0))
        assertNull(assembler.add(parts[1], 3000))
    }

    @Test
    fun newStartReplacesPartialFrame() {
        val longParts = CrpRingFrame.split(CrpRingFrame.build(2, 18, ByteArray(30)), 20)
        val assembler = CrpRingFrameAssembler()
        assertNull(assembler.add(longParts[0], 0))
        val message = assembler.add(CrpRingFrame.build(3, 6, bytes(80)), 10)
        assertNotNull(message)
        assertEquals(6, message!!.command)
    }

    @Test
    fun assemblerReadsCommand255() {
        val message = CrpRingFrameAssembler().add(bytes(0xFD, 0xDA, 0x10, 0x06, 0x07, 0xFF), 0)
        assertEquals(255, message!!.command)
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: the test command above. Expected: compile FAIL, `Unresolved reference: CrpRingFrame`.

- [ ] **Step 3: Write the implementation**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/crpring/CrpRingConstants.kt
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import java.util.UUID

/**
 * Constants of the MoYoung "CRP ring" BLE protocol (SDK com.crrepa.ring), as used by the
 * Pebble Qore Vox. Group 1 sets values, group 2 queries data.
 */
object CrpRingConstants {
    private fun uuid16(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

    val UUID_SERVICE: UUID = uuid16("fdda")
    val UUID_CHARACTERISTIC_STEPS: UUID = uuid16("fdd1")
    val UUID_CHARACTERISTIC_WRITE: UUID = uuid16("fdd2")
    val UUID_CHARACTERISTIC_NOTIFY: UUID = uuid16("fdd3")

    const val GROUP_SET = 1
    const val GROUP_QUERY = 2
    const val GROUP_SYSTEM = 3
    const val GROUP_ACTION = 9

    // Group 1
    const val CMD_SET_USER_INFO = 0
    const val CMD_SET_TIME = 1
    const val CMD_MEASURE_HEART_RATE = 9
    const val CMD_MEASURE_SPO2 = 11
    const val CMD_AUTH = 41

    // Group 2
    const val CMD_QUERY_MANUAL_HEART_RATE = 9
    const val CMD_QUERY_MANUAL_SPO2 = 11
    const val CMD_QUERY_SLEEP = 14
    const val CMD_QUERY_TIMED_HEART_RATE = 15
    const val CMD_QUERY_TIMED_SPO2 = 17
    const val CMD_QUERY_STEPS_DETAILS = 18

    // Group 3
    const val CMD_FIRMWARE_VERSION = 3
    const val CMD_BATTERY = 6

    // Group 9
    const val CMD_FIND_DEVICE = 2

    // First payload byte of CMD_AUTH
    const val AUTH_QUERY = 0
    const val AUTH_SEND = 1
    const val AUTH_DELETE = -1

    const val SLEEP_AWAKE = 0
    const val SLEEP_LIGHT = 1
    const val SLEEP_DEEP = 2
    const val SLEEP_REM = 3

    const val GENDER_MALE = 0
    const val GENDER_FEMALE = 1

    const val TIMED_INTERVAL_MINUTES = 5
    const val STEPS_SLOT_MINUTES = 30
    const val HISTORY_DAYS = 7
    const val MTU = 512

    val HEART_RATE_RANGE = 40..200
    val SPO2_RANGE = 1..100
}
```

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingFrame.kt
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
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: the test command. Expected: `CrpRingFrameTest` PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add $MAIN/devices/crpring/CrpRingConstants.kt $MAIN/service/devices/crpring/CrpRingFrame.kt $TEST/service/devices/crpring/CrpRingFrameTest.kt
git commit -m "CRP ring: frame codec and protocol constants"
```

---

### Task 2: Device time

**Files:**
- Create: `$MAIN/service/devices/crpring/CrpRingTime.kt`
- Test: `$TEST/service/devices/crpring/CrpRingTimeTest.kt`

**Interfaces:**
- Produces: `CrpRingTime.toDeviceSeconds(utcMillis: Long, zone: ZoneId): Long`; `CrpRingTime.fromDeviceSeconds(deviceSeconds: Long, zone: ZoneId): Long` (UTC millis); `CrpRingTime.startOfDay(daysAgo: Int, nowMillis: Long, zone: ZoneId): Long` (UTC millis of local midnight).

- [ ] **Step 1: Write the failing test**

```kotlin
// File: app/src/test/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingTimeTest.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CrpRingTimeTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun toDeviceWritesLocalClockAsGmt8() {
        // 06:30Z is 12:00 in Kolkata. 12:00 at +08:00 is 04:00Z.
        val expected = Instant.parse("2026-11-16T04:00:00Z").epochSecond
        assertEquals(expected, CrpRingTime.toDeviceSeconds(ms("2026-11-16T06:30:00Z"), kolkata))
    }

    @Test
    fun fromDeviceIsTheInverse() {
        val device = Instant.parse("2026-11-16T04:00:00Z").epochSecond
        assertEquals(ms("2026-11-16T06:30:00Z"), CrpRingTime.fromDeviceSeconds(device, kolkata))
    }

    @Test
    fun roundTripInBerlinSummerAndWinter() {
        for (text in listOf("2026-07-01T10:15:00Z", "2026-12-01T10:15:00Z")) {
            val utc = ms(text)
            assertEquals(utc, CrpRingTime.fromDeviceSeconds(CrpRingTime.toDeviceSeconds(utc, berlin), berlin))
        }
    }

    @Test
    fun startOfDayUsesLocalMidnight() {
        // 2026-03-30 10:00 in Berlin (summer time). One day ago is 2026-03-29, which starts at +01:00.
        val now = ms("2026-03-30T08:00:00Z")
        assertEquals(ms("2026-03-28T23:00:00Z"), CrpRingTime.startOfDay(1, now, berlin))
        assertEquals(ms("2026-03-29T22:00:00Z"), CrpRingTime.startOfDay(0, now, berlin))
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: the test command. Expected: compile FAIL, `Unresolved reference: CrpRingTime`.

- [ ] **Step 3: Write the implementation**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingTime.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The band keeps the local wall-clock time as if the zone is GMT+8. A device timestamp is
 * that wall-clock time in seconds.
 */
object CrpRingTime {
    private val DEVICE_OFFSET: ZoneOffset = ZoneOffset.ofHours(8)

    fun toDeviceSeconds(utcMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDateTime().toEpochSecond(DEVICE_OFFSET)

    fun fromDeviceSeconds(deviceSeconds: Long, zone: ZoneId): Long =
        LocalDateTime.ofEpochSecond(deviceSeconds, 0, DEVICE_OFFSET).atZone(zone).toInstant().toEpochMilli()

    fun startOfDay(daysAgo: Int, nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            .minusDays(daysAgo.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
}
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: the test command. Expected: `CrpRingTimeTest` PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add $MAIN/service/devices/crpring/CrpRingTime.kt $TEST/service/devices/crpring/CrpRingTimeTest.kt
git commit -m "CRP ring: device time conversion"
```

---

### Task 3: Parsers and commands

**Files:**
- Create: `$MAIN/service/devices/crpring/CrpRingParsers.kt`
- Create: `$MAIN/service/devices/crpring/CrpRingCommands.kt`
- Test: `$TEST/service/devices/crpring/CrpRingParsersTest.kt`

**Interfaces:**
- Consumes: `CrpRingFrame.build`, `CrpRingTime.toDeviceSeconds`, `CrpRingConstants`.
- Produces: data classes `CrpRingDayValues(day, values)`, `CrpRingPage(day, page, values)`, `CrpRingTimedValue(deviceSeconds: Long, value)`, `CrpRingSleepEntry(stage, hour, minute)`, `CrpRingSleep(day, entries)`, `CrpRingSleepStage(startMillis: Long, durationMinutes: Int, stage: Int)`, `CrpRingAuthResult(operation, result)` with `isOk`. Functions `CrpRingParsers.stepsDetails`, `page`, `timedValues`, `sleep`, `sleepStages(sleep, nowMillis, zone)`, `text`, `authResult`, `firstByte`. `CrpRingCommands.setTime(utcMillis, zone)`, `userInfo(heightCm, weightKg, age, gender, stepLengthCm)`, `sendPin(pin)`, `firmwareVersion()`, `findDevice(start)`, `measureHeartRate(start)`, `stepsDetails(day)`, `sleep(day)`, `timedHeartRate(day, page)`, `timedSpo2(day, page)`, `manualHeartRate()`, `manualSpo2()`.

- [ ] **Step 1: Write the failing test**

```kotlin
// File: app/src/test/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingParsersTest.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import nodomain.freeyourgadget.gadgetbridge.devices.crpring.CrpRingConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CrpRingParsersTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun stepsDetailsReadsUint16Values() {
        val result = CrpRingParsers.stepsDetails(bytes(1, 0x10, 0x00, 0x00, 0x01))!!
        assertEquals(1, result.day)
        assertEquals(listOf(16, 256), result.values)
    }

    @Test
    fun stepsDetailsRejectsEvenLength() {
        assertNull(CrpRingParsers.stepsDetails(bytes(1, 0x10)))
    }

    @Test
    fun pageReadsDayPageAndValues() {
        val result = CrpRingParsers.page(bytes(2, 1, 70, 0, 72))!!
        assertEquals(CrpRingPage(2, 1, listOf(70, 0, 72)), result)
    }

    @Test
    fun timedValuesReadValueAndTimestamp() {
        val result = CrpRingParsers.timedValues(bytes(0, 72, 0x78, 0x56, 0x34, 0x12))!!
        assertEquals(listOf(CrpRingTimedValue(0x12345678L, 72)), result)
        assertNull(CrpRingParsers.timedValues(bytes(0, 72, 1)))
    }

    @Test
    fun authResult() {
        assertTrue(CrpRingParsers.authResult(bytes(1, 0))!!.isOk)
        assertFalse(CrpRingParsers.authResult(bytes(1, 1))!!.isOk)
        assertEquals(CrpRingConstants.AUTH_DELETE, CrpRingParsers.authResult(bytes(0xFF, 0))!!.operation)
        assertNull(CrpRingParsers.authResult(bytes(1)))
    }

    @Test
    fun textDropsTrailingZeros() {
        assertEquals("MOY-ABC1-1.2.3", CrpRingParsers.text("MOY-ABC1-1.2.3".toByteArray() + bytes(0, 0)))
    }

    @Test
    fun sleepAcrossMidnight() {
        val now = ms("2026-11-16T04:30:00Z") // 10:00 in Kolkata
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 23, 0, 2, 0, 30, 3, 2, 0, 0, 6, 0))!!
        val stages = CrpRingParsers.sleepStages(sleep, now, kolkata)
        assertEquals(
            listOf(
                CrpRingSleepStage(ms("2026-11-15T17:30:00Z"), 90, CrpRingConstants.SLEEP_LIGHT),
                CrpRingSleepStage(ms("2026-11-15T19:00:00Z"), 90, CrpRingConstants.SLEEP_DEEP),
                CrpRingSleepStage(ms("2026-11-15T20:30:00Z"), 240, CrpRingConstants.SLEEP_REM),
            ),
            stages
        )
    }

    @Test
    fun sleepAcrossDstChange() {
        // Night of 2026-10-24/25 in Berlin: clocks go back at 03:00 to 02:00.
        val berlin = ZoneId.of("Europe/Berlin")
        val now = ms("2026-10-25T09:00:00Z")
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 23, 0, 0, 7, 0))!!
        val stages = CrpRingParsers.sleepStages(sleep, now, berlin)
        assertEquals(listOf(CrpRingSleepStage(ms("2026-10-24T21:00:00Z"), 540, CrpRingConstants.SLEEP_LIGHT)), stages)
    }

    @Test
    fun sleepStartingAfterMidnightStaysOnTheDay() {
        val now = ms("2026-11-16T04:30:00Z")
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 1, 0, 0, 5, 0))!!
        assertEquals(
            listOf(CrpRingSleepStage(ms("2026-11-15T19:30:00Z"), 240, CrpRingConstants.SLEEP_LIGHT)),
            CrpRingParsers.sleepStages(sleep, now, kolkata)
        )
    }

    @Test
    fun sleepWithBadClockValuesIsEmpty() {
        val sleep = CrpRingParsers.sleep(bytes(0, 1, 25, 0, 0, 6, 0))!!
        assertTrue(CrpRingParsers.sleepStages(sleep, ms("2026-11-16T04:30:00Z"), kolkata).isEmpty())
    }

    @Test
    fun sleepRejectsBadLength() {
        assertNull(CrpRingParsers.sleep(bytes(0, 1, 23)))
    }

    @Test
    fun commandsSetTime() {
        val frame = CrpRingCommands.setTime(ms("2026-11-16T06:30:00Z"), kolkata)
        val seconds = Instant.parse("2026-11-16T04:00:00Z").epochSecond
        val le = bytes(
            (seconds and 0xFF).toInt(), ((seconds shr 8) and 0xFF).toInt(),
            ((seconds shr 16) and 0xFF).toInt(), ((seconds shr 24) and 0xFF).toInt()
        )
        assertArrayEquals(CrpRingFrame.build(1, 1, le + bytes(8)), frame)
    }

    @Test
    fun commandsSendPin() {
        assertArrayEquals(
            CrpRingFrame.build(1, 41, bytes(1, 8) + "12345678".toByteArray()),
            CrpRingCommands.sendPin("12345678")
        )
    }

    @Test
    fun commandsUserInfoClampsValues() {
        assertArrayEquals(
            CrpRingFrame.build(1, 0, bytes(180, 255, 30, 1, 75)),
            CrpRingCommands.userInfo(180, 300, 30, CrpRingConstants.GENDER_FEMALE, 75)
        )
    }

    @Test
    fun commandsQueries() {
        assertArrayEquals(CrpRingFrame.build(2, 18, bytes(3)), CrpRingCommands.stepsDetails(3))
        assertArrayEquals(CrpRingFrame.build(2, 14, bytes(3)), CrpRingCommands.sleep(3))
        assertArrayEquals(CrpRingFrame.build(2, 15, bytes(3, 1)), CrpRingCommands.timedHeartRate(3, 1))
        assertArrayEquals(CrpRingFrame.build(2, 17, bytes(3, 0)), CrpRingCommands.timedSpo2(3, 0))
        assertArrayEquals(CrpRingFrame.build(2, 9), CrpRingCommands.manualHeartRate())
        assertArrayEquals(CrpRingFrame.build(2, 11), CrpRingCommands.manualSpo2())
        assertArrayEquals(CrpRingFrame.build(9, 2, bytes(1)), CrpRingCommands.findDevice(true))
        assertArrayEquals(CrpRingFrame.build(1, 9, bytes(0)), CrpRingCommands.measureHeartRate(false))
        assertArrayEquals(CrpRingFrame.build(3, 3), CrpRingCommands.firmwareVersion())
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: the test command. Expected: compile FAIL, `Unresolved reference: CrpRingParsers`.

- [ ] **Step 3: Write the implementation**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingParsers.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import java.time.Instant
import java.time.ZoneId

data class CrpRingDayValues(val day: Int, val values: List<Int>)
data class CrpRingPage(val day: Int, val page: Int, val values: List<Int>)
data class CrpRingTimedValue(val deviceSeconds: Long, val value: Int)
data class CrpRingSleepEntry(val stage: Int, val hour: Int, val minute: Int)
data class CrpRingSleep(val day: Int, val entries: List<CrpRingSleepEntry>)
data class CrpRingSleepStage(val startMillis: Long, val durationMinutes: Int, val stage: Int)

data class CrpRingAuthResult(val operation: Int, val result: Int) {
    val isOk: Boolean get() = result == 0
}

/** Parsers for reply payloads (the frame header is already removed). */
object CrpRingParsers {
    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    /** 2,18: [day, u16 * 48], steps for each 30 minutes. */
    fun stepsDetails(payload: ByteArray): CrpRingDayValues? {
        if (payload.isEmpty() || payload.size % 2 != 1) return null
        return CrpRingDayValues(u8(payload, 0), (1 until payload.size step 2).map { u16(payload, it) })
    }

    /** 2,15 and 2,17: [day, page, u8 * n], one value for each 5 minutes. */
    fun page(payload: ByteArray): CrpRingPage? {
        if (payload.size < 2) return null
        return CrpRingPage(u8(payload, 0), u8(payload, 1), (2 until payload.size).map { u8(payload, it) })
    }

    /** 2,9 and 2,11: [x, (value u8, device timestamp u32) * n]. */
    fun timedValues(payload: ByteArray): List<CrpRingTimedValue>? {
        if (payload.isEmpty() || payload.size % 5 != 1) return null
        return (1 until payload.size step 5).map { CrpRingTimedValue(u32(payload, it + 1), u8(payload, it)) }
    }

    /** 2,14: [day, (stage, hour, minute) * n]. */
    fun sleep(payload: ByteArray): CrpRingSleep? {
        if (payload.isEmpty() || payload.size % 3 != 1) return null
        val entries = (1 until payload.size step 3).map {
            CrpRingSleepEntry(u8(payload, it), u8(payload, it + 1), u8(payload, it + 2))
        }
        return CrpRingSleep(u8(payload, 0), entries)
    }

    /**
     * Each entry starts a stage that ends at the next entry. The last entry has no end, so it
     * is not returned. A clock time before the previous one is on the next date. The first
     * entry is on the date before the day if its hour is 12 or more.
     */
    fun sleepStages(sleep: CrpRingSleep, nowMillis: Long, zone: ZoneId): List<CrpRingSleepStage> {
        val entries = sleep.entries
        if (entries.size < 2) return emptyList()
        if (entries.any { it.hour > 23 || it.minute > 59 }) return emptyList()
        val anchor = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().minusDays(sleep.day.toLong())
        var date = if (entries[0].hour >= 12) anchor.minusDays(1) else anchor
        var previousMinuteOfDay = -1
        val starts = entries.map { entry ->
            val minuteOfDay = entry.hour * 60 + entry.minute
            if (previousMinuteOfDay >= 0 && minuteOfDay < previousMinuteOfDay) date = date.plusDays(1)
            previousMinuteOfDay = minuteOfDay
            date.atTime(entry.hour, entry.minute).atZone(zone).toInstant().toEpochMilli()
        }
        val stages = ArrayList<CrpRingSleepStage>()
        for (i in 0 until entries.size - 1) {
            val duration = ((starts[i + 1] - starts[i]) / 60_000L).toInt()
            if (duration > 0) stages.add(CrpRingSleepStage(starts[i], duration, entries[i].stage))
        }
        return stages
    }

    fun text(payload: ByteArray): String = String(payload, Charsets.UTF_8).trimEnd('\u0000').trim()

    /** 1,41: [operation, result]. The operation byte is signed (-1 = delete). */
    fun authResult(payload: ByteArray): CrpRingAuthResult? {
        if (payload.size < 2) return null
        return CrpRingAuthResult(payload[0].toInt(), u8(payload, 1))
    }

    fun firstByte(payload: ByteArray): Int? = if (payload.isEmpty()) null else u8(payload, 0)
}
```

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingCommands.kt
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
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: the test command. Expected: `CrpRingParsersTest` PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
git add $MAIN/service/devices/crpring/CrpRingParsers.kt $MAIN/service/devices/crpring/CrpRingCommands.kt $TEST/service/devices/crpring/CrpRingParsersTest.kt
git commit -m "CRP ring: reply parsers and request commands"
```

---

### Task 4: History fetch state machine

**Files:**
- Create: `$MAIN/service/devices/crpring/CrpRingHistoryFetcher.kt`
- Test: `$TEST/service/devices/crpring/CrpRingHistoryFetcherTest.kt`

**Interfaces:**
- Consumes: `CrpRingCommands`, `CrpRingParsers`, `CrpRingTime`, `CrpRingMessage`.
- Produces: `CrpRingValueSample(timestampMillis: Long, value: Int)`; `CrpRingHistoryResult` with `steps`, `heartRate`, `spo2: MutableList<CrpRingValueSample>` and `sleep: MutableList<CrpRingSleepStage>`; `CrpRingHistoryFetcher(nowMillis, zone, days = 7, send: (ByteArray) -> Unit)` with `start()`, `handle(message): Boolean`, `onTimeout()`, `isFinished`, `result`, `skipped: List<String>`.

- [ ] **Step 1: Write the failing test**

```kotlin
// File: app/src/test/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingHistoryFetcherTest.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CrpRingHistoryFetcherTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-16T06:30:00Z").toEpochMilli() // 12:00 local
    private val midnight = Instant.parse("2026-11-15T18:30:00Z").toEpochMilli() // 00:00 local
    private val sent = mutableListOf<ByteArray>()
    private val fetcher = CrpRingHistoryFetcher(now, zone, 1) { sent.add(it) }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun reply(group: Int, command: Int, payload: ByteArray) = CrpRingMessage(group, command, payload)
    private fun emptyReplies() = listOf(
        reply(2, 18, bytes(0)), reply(2, 14, bytes(0)),
        reply(2, 15, bytes(0, 0)), reply(2, 15, bytes(0, 1)),
        reply(2, 17, bytes(0, 0)), reply(2, 17, bytes(0, 1)),
        reply(2, 9, bytes(0)), reply(2, 11, bytes(0)),
    )

    @Test
    fun sendsRequestsInOrderAndFinishes() {
        fetcher.start()
        for (message in emptyReplies()) {
            assertFalse(fetcher.isFinished)
            assertTrue(fetcher.handle(message))
        }
        assertTrue(fetcher.isFinished)
        val expected = listOf(
            CrpRingCommands.stepsDetails(0), CrpRingCommands.sleep(0),
            CrpRingCommands.timedHeartRate(0, 0), CrpRingCommands.timedHeartRate(0, 1),
            CrpRingCommands.timedSpo2(0, 0), CrpRingCommands.timedSpo2(0, 1),
            CrpRingCommands.manualHeartRate(), CrpRingCommands.manualSpo2(),
        )
        assertEquals(expected.size, sent.size)
        expected.forEachIndexed { i, frame -> assertArrayEquals(frame, sent[i]) }
    }

    @Test
    fun oldestDayComesFirst() {
        CrpRingHistoryFetcher(now, zone, 3) { sent.add(it) }.start()
        assertArrayEquals(CrpRingCommands.stepsDetails(2), sent[0])
    }

    @Test
    fun futureStepSlotsAreDropped() {
        fetcher.start()
        val payload = ByteArray(97)
        for (i in 0 until 48) payload[1 + i * 2] = 100
        fetcher.handle(reply(2, 18, payload))
        // Slots 0..24 start at or before 12:00.
        assertEquals(25, fetcher.result.steps.size)
        assertEquals(CrpRingValueSample(midnight, 100), fetcher.result.steps.first())
        assertEquals(CrpRingValueSample(now, 100), fetcher.result.steps.last())
    }

    @Test
    fun zeroStepSlotsAreDropped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0, 0, 0, 5, 0)))
        assertEquals(listOf(CrpRingValueSample(midnight + 30 * 60_000L, 5)), fetcher.result.steps)
    }

    @Test
    fun timedHeartRateUsesPageOffset() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0)))
        val page0 = ByteArray(146)
        page0[2] = 70
        fetcher.handle(reply(2, 15, page0))
        fetcher.handle(reply(2, 15, bytes(0, 1, 80)))
        assertEquals(
            listOf(CrpRingValueSample(midnight, 70), CrpRingValueSample(midnight + 144 * 5 * 60_000L, 80)),
            fetcher.result.heartRate
        )
    }

    @Test
    fun invalidHeartRateValuesAreDropped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0)))
        fetcher.handle(reply(2, 15, bytes(0, 0, 30, 210, 60)))
        assertEquals(listOf(CrpRingValueSample(midnight + 2 * 5 * 60_000L, 60)), fetcher.result.heartRate)
    }

    @Test
    fun pageOneWithoutPageZeroIsSkipped() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0)))
        fetcher.onTimeout()
        assertTrue(fetcher.handle(reply(2, 15, bytes(0, 1, 80))))
        assertTrue(fetcher.result.heartRate.isEmpty())
    }

    @Test
    fun timeoutSkipsStep() {
        fetcher.start()
        fetcher.onTimeout()
        assertArrayEquals(CrpRingCommands.sleep(0), sent.last())
        assertEquals(1, fetcher.skipped.size)
    }

    @Test
    fun unrelatedMessageIsNotConsumed() {
        fetcher.start()
        assertFalse(fetcher.handle(reply(1, 9, bytes(72))))
        assertFalse(fetcher.handle(reply(2, 18, bytes(1))))
        assertEquals(1, sent.size)
    }

    @Test
    fun manualHeartRateUsesDeviceTimestamps() {
        fetcher.start()
        emptyReplies().take(6).forEach { fetcher.handle(it) }
        val nineLocal = Instant.parse("2026-11-16T03:30:00Z").toEpochMilli()
        val seconds = CrpRingTime.toDeviceSeconds(nineLocal, zone)
        val payload = bytes(0, 75, (seconds and 0xFF).toInt(), ((seconds shr 8) and 0xFF).toInt(),
            ((seconds shr 16) and 0xFF).toInt(), ((seconds shr 24) and 0xFF).toInt())
        fetcher.handle(reply(2, 9, payload))
        assertEquals(listOf(CrpRingValueSample(nineLocal, 75)), fetcher.result.heartRate)
    }

    @Test
    fun sleepStagesAreCollected() {
        fetcher.start()
        fetcher.handle(reply(2, 18, bytes(0)))
        fetcher.handle(reply(2, 14, bytes(0, 1, 23, 0, 0, 6, 0)))
        assertEquals(1, fetcher.result.sleep.size)
        assertEquals(420, fetcher.result.sleep[0].durationMinutes)
    }

    @Test
    fun messagesAfterFinishAreNotConsumed() {
        fetcher.start()
        emptyReplies().forEach { fetcher.handle(it) }
        assertFalse(fetcher.handle(reply(2, 11, bytes(0))))
    }
}
```

- [ ] **Step 2: Run the test to make sure it fails**

Run: the test command. Expected: compile FAIL, `Unresolved reference: CrpRingHistoryFetcher`.

- [ ] **Step 3: Write the implementation**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingHistoryFetcher.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import nodomain.freeyourgadget.gadgetbridge.devices.crpring.CrpRingConstants
import java.time.ZoneId

data class CrpRingValueSample(val timestampMillis: Long, val value: Int)

class CrpRingHistoryResult {
    val steps = mutableListOf<CrpRingValueSample>()
    val heartRate = mutableListOf<CrpRingValueSample>()
    val spo2 = mutableListOf<CrpRingValueSample>()
    val sleep = mutableListOf<CrpRingSleepStage>()
}

/**
 * Sends one history request at a time and collects the replies. The caller gives each
 * reply to [handle] and calls [onTimeout] when a reply does not come. Not thread-safe.
 */
class CrpRingHistoryFetcher(
    private val nowMillis: Long,
    private val zone: ZoneId,
    private val days: Int = CrpRingConstants.HISTORY_DAYS,
    private val send: (ByteArray) -> Unit,
) {
    private class Step(val request: ByteArray, val command: Int, val day: Int?, val page: Int?) {
        val description: String get() = "command=$command day=$day page=$page"

        fun matches(message: CrpRingMessage): Boolean {
            if (message.group != CrpRingConstants.GROUP_QUERY || message.command != command) return false
            val payload = message.payload
            if (day != null && (payload.isEmpty() || (payload[0].toInt() and 0xFF) != day)) return false
            if (page != null && (payload.size < 2 || (payload[1].toInt() and 0xFF) != page)) return false
            return true
        }
    }

    val result = CrpRingHistoryResult()
    private val skippedSteps = mutableListOf<String>()
    val skipped: List<String> get() = skippedSteps

    private val steps: List<Step> = buildSteps()
    private var index = -1
    private val pageZeroSize = HashMap<Pair<Int, Int>, Int>()

    val isFinished: Boolean get() = index >= steps.size
    private val current: Step? get() = steps.getOrNull(index)

    fun start() {
        index = -1
        advance()
    }

    fun handle(message: CrpRingMessage): Boolean {
        val step = current ?: return false
        if (!step.matches(message)) return false
        consume(step, message.payload)
        advance()
        return true
    }

    fun onTimeout() {
        current?.let { skippedSteps.add(it.description) }
        advance()
    }

    private fun advance() {
        index++
        current?.let { send(it.request) }
    }

    private fun buildSteps(): List<Step> {
        val list = ArrayList<Step>()
        for (day in days - 1 downTo 0) {
            list.add(Step(CrpRingCommands.stepsDetails(day), CrpRingConstants.CMD_QUERY_STEPS_DETAILS, day, null))
            list.add(Step(CrpRingCommands.sleep(day), CrpRingConstants.CMD_QUERY_SLEEP, day, null))
            for (page in 0..1) {
                list.add(Step(CrpRingCommands.timedHeartRate(day, page), CrpRingConstants.CMD_QUERY_TIMED_HEART_RATE, day, page))
            }
            for (page in 0..1) {
                list.add(Step(CrpRingCommands.timedSpo2(day, page), CrpRingConstants.CMD_QUERY_TIMED_SPO2, day, page))
            }
        }
        list.add(Step(CrpRingCommands.manualHeartRate(), CrpRingConstants.CMD_QUERY_MANUAL_HEART_RATE, null, null))
        list.add(Step(CrpRingCommands.manualSpo2(), CrpRingConstants.CMD_QUERY_MANUAL_SPO2, null, null))
        return list
    }

    private fun consume(step: Step, payload: ByteArray) {
        when (step.command) {
            CrpRingConstants.CMD_QUERY_STEPS_DETAILS -> consumeSteps(payload)
            CrpRingConstants.CMD_QUERY_SLEEP -> consumeSleep(payload)
            CrpRingConstants.CMD_QUERY_TIMED_HEART_RATE ->
                consumePage(step.command, payload, CrpRingConstants.HEART_RATE_RANGE, result.heartRate)
            CrpRingConstants.CMD_QUERY_TIMED_SPO2 ->
                consumePage(step.command, payload, CrpRingConstants.SPO2_RANGE, result.spo2)
            CrpRingConstants.CMD_QUERY_MANUAL_HEART_RATE ->
                consumeTimed(payload, CrpRingConstants.HEART_RATE_RANGE, result.heartRate)
            CrpRingConstants.CMD_QUERY_MANUAL_SPO2 ->
                consumeTimed(payload, CrpRingConstants.SPO2_RANGE, result.spo2)
        }
    }

    private fun consumeSteps(payload: ByteArray) {
        val details = CrpRingParsers.stepsDetails(payload) ?: return
        val start = CrpRingTime.startOfDay(details.day, nowMillis, zone)
        details.values.forEachIndexed { slot, steps ->
            val timestamp = start + slot * CrpRingConstants.STEPS_SLOT_MINUTES * 60_000L
            if (steps > 0 && timestamp <= nowMillis) result.steps.add(CrpRingValueSample(timestamp, steps))
        }
    }

    private fun consumeSleep(payload: ByteArray) {
        val sleep = CrpRingParsers.sleep(payload) ?: return
        CrpRingParsers.sleepStages(sleep, nowMillis, zone)
            .filter { it.startMillis <= nowMillis }
            .forEach { result.sleep.add(it) }
    }

    private fun consumePage(command: Int, payload: ByteArray, valid: IntRange, target: MutableList<CrpRingValueSample>) {
        val page = CrpRingParsers.page(payload) ?: return
        val key = Pair(command, page.day)
        val offset = if (page.page == 0) {
            pageZeroSize[key] = page.values.size
            0
        } else {
            pageZeroSize[key] ?: return
        }
        val start = CrpRingTime.startOfDay(page.day, nowMillis, zone)
        page.values.forEachIndexed { i, value ->
            val timestamp = start + (offset + i) * CrpRingConstants.TIMED_INTERVAL_MINUTES * 60_000L
            if (value in valid && timestamp <= nowMillis) target.add(CrpRingValueSample(timestamp, value))
        }
    }

    private fun consumeTimed(payload: ByteArray, valid: IntRange, target: MutableList<CrpRingValueSample>) {
        val values = CrpRingParsers.timedValues(payload) ?: return
        for (value in values) {
            val timestamp = CrpRingTime.fromDeviceSeconds(value.deviceSeconds, zone)
            if (value.value in valid && timestamp <= nowMillis) target.add(CrpRingValueSample(timestamp, value.value))
        }
    }
}
```

- [ ] **Step 4: Run the test to make sure it passes**

Run: the test command. Expected: all `crpring` tests PASS (40 tests).

- [ ] **Step 5: Commit**

```bash
git add $MAIN/service/devices/crpring/CrpRingHistoryFetcher.kt $TEST/service/devices/crpring/CrpRingHistoryFetcherTest.kt
git commit -m "CRP ring: history fetch state machine"
```

---

### Task 5: Database entity, coordinator and sample provider

**Files:**
- Modify: `GBDaoGenerator/src/nodomain/freeyourgadget/gadgetbridge/daogen/GBDaoGenerator.java` (schema version line 113; add the entity next to `addGloryFitStepsSample` at line 239; add the method)
- Create: `$MAIN/devices/crpring/CrpRingActivitySampleProvider.kt`
- Create: `$MAIN/devices/crpring/AbstractCrpRingCoordinator.kt`
- Create: `$MAIN/devices/crpring/PebbleQoreVoxCoordinator.kt`
- Modify: `$MAIN/model/DeviceType.java` (import, and `PEBBLE_QORE_VOX` before `TEST`)
- Modify: `app/src/main/res/values/strings.xml` (after `devicetype_nx_wear_b8`)

**Interfaces:**
- Consumes: `CrpRingConstants`.
- Produces: generated `CrpRingStepsSample` (`timestamp: Long`, `steps: Int`), `CrpRingStepsSampleDao`, `CrpRingStepsSampleProvider(device, session)` in package `devices`; `PebbleQoreVoxCoordinator`; strings `devicetype_pebble_qore_vox`, `crpring_pin_rejected`. Task 6 defines `CrpRingDeviceSupport`; this task references it, so Task 5 and Task 6 compile together.

- [ ] **Step 1: Add the entity**

In `GBDaoGenerator.java`, change `new Schema(155,` to `new Schema(156,`. After the line `sampleProvidersToGenerate.add(addGloryFitStepsSample(schema, user, device));` add:

```java
        sampleProvidersToGenerate.add(addCrpRingStepsSample(schema, user, device));
```

After the method `addGloryFitStepsSample`, add:

```java
    private static Entity addCrpRingStepsSample(Schema schema, Entity user, Entity device) {
        Entity stepsSample = addEntity(schema, "CrpRingStepsSample");
        addCommonTimeSampleProperties("AbstractTimeSample", stepsSample, user, device);
        stepsSample.addIntProperty(SAMPLE_STEPS).notNull();
        return stepsSample;
    }
```

- [ ] **Step 2: Write the sample provider**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/crpring/CrpRingActivitySampleProvider.kt
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import nodomain.freeyourgadget.gadgetbridge.devices.CrpRingStepsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample

/**
 * Read-only provider. It merges the 30-minute step samples, the heart rate samples and the
 * sleep stages into one activity sample for each minute that has data.
 */
class CrpRingActivitySampleProvider(
    private val device: GBDevice,
    private val session: DaoSession,
) : SampleProvider<GenericActivitySample> {

    override fun normalizeType(rawType: Int): ActivityKind = ActivityKind.fromCode(rawType)

    override fun toRawActivityKind(activityKind: ActivityKind): Int = activityKind.code

    override fun normalizeIntensity(rawIntensity: Int): Float = rawIntensity.toFloat()

    override fun getAllActivitySamples(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> {
        val byTimestamp = HashMap<Int, GenericActivitySample>()
        fun sampleAt(timestamp: Int): GenericActivitySample = byTimestamp.getOrPut(timestamp) {
            GenericActivitySample().also {
                it.provider = this
                it.timestamp = timestamp
            }
        }

        val fromMillis = timestampFrom * 1000L
        val toMillis = timestampTo * 1000L
        for (sample in CrpRingStepsSampleProvider(device, session).getAllSamples(fromMillis, toMillis)) {
            sampleAt(toMinute(sample.timestamp)).steps = sample.steps
        }
        for (sample in GenericHeartRateSampleProvider(device, session).getAllSamples(fromMillis, toMillis)) {
            sampleAt(toMinute(sample.timestamp)).heartRate = sample.heartRate
        }

        val sleepProvider = GenericSleepStageSampleProvider(device, session)
        val stages = sleepProvider.getAllSamples(fromMillis, toMillis).toMutableList()
        sleepProvider.getLastSampleBefore(fromMillis)?.let {
            if (it.timestamp + it.duration * 60_000L > fromMillis) stages.add(0, it)
        }
        for (stage in stages) {
            val kind = sleepStageToActivityKind(stage.stage)
            val start = toMinute(stage.timestamp)
            for (timestamp in start until start + stage.duration * 60 step 60) {
                if (timestamp < timestampFrom || timestamp > timestampTo) continue
                val sample = sampleAt(timestamp)
                sample.rawKind = kind.code
                sample.rawIntensity = ActivitySample.NOT_MEASURED
            }
        }

        return byTimestamp.values
            .filter { it.timestamp in timestampFrom..timestampTo }
            .sortedBy { it.timestamp }
            .toMutableList()
    }

    override fun getAllActivitySamplesHighRes(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> =
        getAllActivitySamples(timestampFrom, timestampTo)

    override fun hasHighResData(): Boolean = false

    override fun getActivitySamples(timestampFrom: Int, timestampTo: Int): MutableList<GenericActivitySample> =
        getAllActivitySamples(timestampFrom, timestampTo)
            .filter { it.kind == ActivityKind.ACTIVITY }
            .toMutableList()

    override fun addGBActivitySample(activitySample: GenericActivitySample) {
        throw UnsupportedOperationException("Read-only sample provider")
    }

    override fun addGBActivitySamples(activitySamples: List<GenericActivitySample>) {
        throw UnsupportedOperationException("Read-only sample provider")
    }

    override fun createActivitySample(): GenericActivitySample = GenericActivitySample()

    override fun getLatestActivitySample(): GenericActivitySample? = null

    override fun getLatestActivitySample(until: Int): GenericActivitySample? = null

    override fun getFirstActivitySample(): GenericActivitySample? = null

    override fun getFirstActivitySample(after: Int): GenericActivitySample? = null

    private fun toMinute(timestampMillis: Long): Int = ((timestampMillis / 1000L) / 60L * 60L).toInt()

    private fun sleepStageToActivityKind(stage: Int): ActivityKind = when (stage) {
        CrpRingConstants.SLEEP_AWAKE -> ActivityKind.AWAKE_SLEEP
        CrpRingConstants.SLEEP_LIGHT -> ActivityKind.LIGHT_SLEEP
        CrpRingConstants.SLEEP_DEEP -> ActivityKind.DEEP_SLEEP
        CrpRingConstants.SLEEP_REM -> ActivityKind.REM_SLEEP
        else -> ActivityKind.UNKNOWN
    }
}
```

- [ ] **Step 3: Write the coordinators**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/crpring/AbstractCrpRingCoordinator.kt
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import de.greenrobot.dao.AbstractDao
import de.greenrobot.dao.Property
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.CrpRingStepsSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.crpring.CrpRingDeviceSupport

/** Devices that use the MoYoung "CRP ring" SDK protocol (service fdda). */
abstract class AbstractCrpRingCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        CrpRingDeviceSupport::class.java

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.FITNESS_BAND

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_miband6

    override fun getAllDeviceDao(session: DaoSession): MutableMap<AbstractDao<*, *>, Property> =
        hashMapOf<AbstractDao<*, *>, Property>(
            session.crpRingStepsSampleDao to CrpRingStepsSampleDao.Properties.DeviceId,
            session.genericHeartRateSampleDao to GenericHeartRateSampleDao.Properties.DeviceId,
            session.genericSpo2SampleDao to GenericSpo2SampleDao.Properties.DeviceId,
            session.genericSleepStageSampleDao to GenericSleepStageSampleDao.Properties.DeviceId,
        )

    override fun getSampleProvider(device: GBDevice, session: DaoSession): SampleProvider<out AbstractActivitySample> =
        CrpRingActivitySampleProvider(device, session)

    override fun getSpo2SampleProvider(device: GBDevice, session: DaoSession): TimeSampleProvider<out Spo2Sample> =
        GenericSpo2SampleProvider(device, session)

    override fun getSupportedDeviceSpecificSettings(device: GBDevice): IntArray =
        intArrayOf(R.xml.devicesettings_pairingkey)

    override fun supportsDataFetching(device: GBDevice): Boolean = true
    override fun supportsActivityTracking(device: GBDevice): Boolean = true
    override fun supportsSleepMeasurement(device: GBDevice): Boolean = true
    override fun supportsRemSleep(device: GBDevice): Boolean = true
    override fun supportsAwakeSleep(device: GBDevice): Boolean = true
    override fun supportsHeartRateMeasurement(device: GBDevice): Boolean = true
    override fun supportsManualHeartRateMeasurement(device: GBDevice): Boolean = true
    override fun supportsSpo2(device: GBDevice): Boolean = true
    override fun supportsFindDevice(device: GBDevice): Boolean = true
}
```

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/crpring/PebbleQoreVoxCoordinator.kt
package nodomain.freeyourgadget.gadgetbridge.devices.crpring

import nodomain.freeyourgadget.gadgetbridge.R
import java.util.regex.Pattern

/** Pebble Qore Vox: screenless band with voice recording. Phase 1 syncs health data only. */
class PebbleQoreVoxCoordinator : AbstractCrpRingCoordinator() {
    override fun getSupportedDeviceName(): Pattern = Pattern.compile("^PBL Qore Vox.*")

    override fun getManufacturer(): String = "Pebble"

    override fun getDeviceNameResource(): Int = R.string.devicetype_pebble_qore_vox
}
```

- [ ] **Step 4: Register the device and add strings**

In `DeviceType.java`, add `import nodomain.freeyourgadget.gadgetbridge.devices.crpring.PebbleQoreVoxCoordinator;` after the `nxwear` import. Before `TEST(TestDeviceCoordinator.class);` add:

```java
    PEBBLE_QORE_VOX(PebbleQoreVoxCoordinator.class),
```

In `strings.xml`, after `devicetype_nx_wear_b8`, add:

```xml
    <string name="devicetype_pebble_qore_vox" translatable="false">Pebble Qore Vox</string>
    <string name="crpring_pin_rejected">The band did not accept the PIN. Enter the PIN of the Pebble Vox app in the device settings, or unpair the band in that app.</string>
```

- [ ] **Step 5: Commit after Task 6 compiles** (Task 6, Step 3 builds both tasks).

---

### Task 6: Device support and database store

**Files:**
- Create: `$MAIN/service/devices/crpring/CrpRingHistoryStore.kt`
- Create: `$MAIN/service/devices/crpring/CrpRingDeviceSupport.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: `CrpRingDeviceSupport` (the class named by `AbstractCrpRingCoordinator`); `CrpRingHistoryStore.save(device, context, result)`.

- [ ] **Step 1: Write the store**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingHistoryStore.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.CrpRingStepsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.CrpRingStepsSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.slf4j.LoggerFactory

/** Saves a fetch result. Rows with the same device and timestamp are replaced. */
object CrpRingHistoryStore {
    private val LOG = LoggerFactory.getLogger(CrpRingHistoryStore::class.java)

    fun save(device: GBDevice, context: Context, result: CrpRingHistoryResult) {
        LOG.info(
            "Saving {} steps, {} heart rate, {} SpO2 and {} sleep samples",
            result.steps.size, result.heartRate.size, result.spo2.size, result.sleep.size
        )
        try {
            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                CrpRingStepsSampleProvider(device, session).persistSamples(
                    result.steps.map { s -> CrpRingStepsSample().apply { timestamp = s.timestampMillis; steps = s.value } },
                    context
                )
                GenericHeartRateSampleProvider(device, session).persistSamples(
                    result.heartRate.map { s -> GenericHeartRateSample().apply { timestamp = s.timestampMillis; heartRate = s.value } },
                    context
                )
                GenericSpo2SampleProvider(device, session).persistSamples(
                    result.spo2.map { s -> GenericSpo2Sample().apply { timestamp = s.timestampMillis; spo2 = s.value } },
                    context
                )
                GenericSleepStageSampleProvider(device, session).persistSamples(
                    result.sleep.map { s ->
                        GenericSleepStageSample().apply {
                            timestamp = s.startMillis
                            duration = s.durationMinutes
                            stage = s.stage
                        }
                    },
                    context
                )
            }
        } catch (e: Exception) {
            LOG.error("Failed to save CRP ring history", e)
        }
    }

    fun saveHeartRate(device: GBDevice, context: Context, sample: GenericHeartRateSample) {
        try {
            GBApplication.acquireDB().use { db ->
                GenericHeartRateSampleProvider(device, db.daoSession).persistSamples(listOf(sample), context)
            }
        } catch (e: Exception) {
            LOG.error("Failed to save CRP ring heart rate", e)
        }
    }
}
```

- [ ] **Step 2: Write the device support**

```kotlin
// File: app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/crpring/CrpRingDeviceSupport.kt
package nodomain.freeyourgadget.gadgetbridge.service.devices.crpring

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo
import nodomain.freeyourgadget.gadgetbridge.devices.crpring.CrpRingConstants
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic
import nodomain.freeyourgadget.gadgetbridge.service.btle.GattService
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.time.ZoneId
import kotlin.random.Random

class CrpRingDeviceSupport : AbstractBTLESingleDeviceSupport(LOG) {
    companion object {
        private val LOG = LoggerFactory.getLogger(CrpRingDeviceSupport::class.java)
        private const val FETCH_STEP_TIMEOUT_MILLIS = 5_000L
        private const val MEASUREMENT_TIMEOUT_MILLIS = 60_000L
    }

    private val lock = Any()
    private val assembler = CrpRingFrameAssembler()
    private val handler = Handler(Looper.getMainLooper())
    private var fetcher: CrpRingHistoryFetcher? = null
    private var chunkSize = 20
    private val fetchTimeout = Runnable { onFetchStepTimeout() }
    private val measurementTimeout = Runnable { stopHeartRateMeasurement() }

    init {
        addSupportedService(CrpRingConstants.UUID_SERVICE)
        addSupportedService(GattService.UUID_SERVICE_BATTERY_SERVICE)
    }

    override fun useAutoConnect(): Boolean = false

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        builder.setDeviceState(GBDevice.State.INITIALIZING)
        builder.requestMtu(CrpRingConstants.MTU)
        builder.notify(CrpRingConstants.UUID_CHARACTERISTIC_NOTIFY, true)
        builder.notify(CrpRingConstants.UUID_CHARACTERISTIC_STEPS, true)
        builder.notify(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL, true)
        builder.read(GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL)
        write(builder, CrpRingCommands.firmwareVersion())
        write(builder, CrpRingCommands.sendPin(getOrCreatePin()))
        write(builder, CrpRingCommands.setTime(System.currentTimeMillis(), ZoneId.systemDefault()))
        write(builder, userInfoCommand())
        builder.setDeviceState(GBDevice.State.INITIALIZED)
        return builder
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
            chunkSize = mtu - 3
            LOG.info("MTU is {}, write chunk size is {}", mtu, chunkSize)
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        if (super.onCharacteristicChanged(gatt, characteristic, value)) return true
        when (characteristic.uuid) {
            CrpRingConstants.UUID_CHARACTERISTIC_NOTIFY -> {
                val message = synchronized(lock) { assembler.add(value, System.currentTimeMillis()) }
                if (message != null) handleMessage(message)
                return true
            }
            CrpRingConstants.UUID_CHARACTERISTIC_STEPS -> {
                LOG.debug("Live steps: {}", GB.hexdump(value))
                return true
            }
            GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL -> {
                CrpRingParsers.firstByte(value)?.let { reportBattery(it) }
                return true
            }
        }
        return false
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ): Boolean {
        if (super.onCharacteristicRead(gatt, characteristic, value, status)) return true
        if (characteristic.uuid == GattCharacteristic.UUID_CHARACTERISTIC_BATTERY_LEVEL && status == BluetoothGatt.GATT_SUCCESS) {
            CrpRingParsers.firstByte(value)?.let { reportBattery(it) }
            return true
        }
        return false
    }

    override fun onSetTime() {
        send("Set time", CrpRingCommands.setTime(System.currentTimeMillis(), ZoneId.systemDefault()))
    }

    override fun onFindDevice(start: Boolean) {
        send("Find device", CrpRingCommands.findDevice(start))
    }

    override fun onHeartRateTest() {
        handler.removeCallbacks(measurementTimeout)
        send("Start heart rate measurement", CrpRingCommands.measureHeartRate(true))
        handler.postDelayed(measurementTimeout, MEASUREMENT_TIMEOUT_MILLIS)
    }

    override fun onFetchRecordedData(dataTypes: Int) {
        synchronized(lock) {
            if (fetcher != null) {
                LOG.info("History fetch is already running")
                return
            }
            device.setBusyTask(R.string.busy_task_fetch_activity_data, context)
            device.sendDeviceUpdateIntent(context)
            val active = CrpRingHistoryFetcher(System.currentTimeMillis(), ZoneId.systemDefault()) { frame ->
                send("Fetch history", frame)
            }
            fetcher = active
            active.start()
            scheduleFetchTimeout()
        }
    }

    override fun dispose() {
        handler.removeCallbacksAndMessages(null)
        synchronized(lock) {
            if (fetcher != null) {
                fetcher = null
                device.unsetBusyTask()
            }
            assembler.reset()
        }
        super.dispose()
    }

    private fun handleMessage(message: CrpRingMessage) {
        LOG.debug("Received {}", message)
        synchronized(lock) {
            val active = fetcher
            if (active != null && active.handle(message)) {
                onFetchProgress(active)
                return
            }
        }
        when (message.group to message.command) {
            CrpRingConstants.GROUP_SYSTEM to CrpRingConstants.CMD_FIRMWARE_VERSION -> handleFirmware(message.payload)
            CrpRingConstants.GROUP_SYSTEM to CrpRingConstants.CMD_BATTERY ->
                CrpRingParsers.firstByte(message.payload)?.let { reportBattery(it) }
            CrpRingConstants.GROUP_SET to CrpRingConstants.CMD_AUTH -> handleAuth(message.payload)
            CrpRingConstants.GROUP_SET to CrpRingConstants.CMD_MEASURE_HEART_RATE -> handleHeartRate(message.payload)
            else -> LOG.debug("Unhandled message group={} command={}", message.group, message.command)
        }
    }

    private fun handleFirmware(payload: ByteArray) {
        val version = CrpRingParsers.text(payload)
        if (version.isEmpty()) return
        LOG.info("Firmware version: {}", version)
        evaluateGBDeviceEvent(GBDeviceEventVersionInfo().apply { fwVersion = version })
    }

    private fun handleAuth(payload: ByteArray) {
        val result = CrpRingParsers.authResult(payload) ?: return
        if (result.operation != CrpRingConstants.AUTH_SEND) return
        if (result.isOk) {
            LOG.info("The band accepted the PIN")
        } else {
            LOG.warn("The band did not accept the PIN, result {}", result.result)
            GB.toast(context, context.getString(R.string.crpring_pin_rejected), Toast.LENGTH_LONG, GB.WARN)
        }
    }

    private fun handleHeartRate(payload: ByteArray) {
        val bpm = CrpRingParsers.firstByte(payload) ?: return
        if (bpm !in CrpRingConstants.HEART_RATE_RANGE) {
            LOG.debug("Heart rate measurement value {} is not valid", bpm)
            return
        }
        val sample = GenericHeartRateSample().apply {
            timestamp = System.currentTimeMillis()
            heartRate = bpm
        }
        CrpRingHistoryStore.saveHeartRate(device, context, sample)
        val intent = Intent(DeviceService.ACTION_REALTIME_SAMPLES)
            .putExtra(GBDevice.EXTRA_DEVICE, device)
            .putExtra(DeviceService.EXTRA_REALTIME_SAMPLE, sample)
            .putExtra(DeviceService.EXTRA_TIMESTAMP, sample.timestamp)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        stopHeartRateMeasurement()
    }

    private fun stopHeartRateMeasurement() {
        handler.removeCallbacks(measurementTimeout)
        send("Stop heart rate measurement", CrpRingCommands.measureHeartRate(false))
    }

    private fun reportBattery(level: Int) {
        if (level !in 0..100) return
        evaluateGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
            this.level = level
            state = BatteryState.BATTERY_NORMAL
        })
    }

    /** Call with [lock] held. */
    private fun onFetchProgress(active: CrpRingHistoryFetcher) {
        if (active.isFinished) finishFetch(active) else scheduleFetchTimeout()
    }

    private fun onFetchStepTimeout() {
        synchronized(lock) {
            val active = fetcher ?: return
            LOG.warn("History step timed out")
            active.onTimeout()
            onFetchProgress(active)
        }
    }

    private fun scheduleFetchTimeout() {
        handler.removeCallbacks(fetchTimeout)
        handler.postDelayed(fetchTimeout, FETCH_STEP_TIMEOUT_MILLIS)
    }

    /** Call with [lock] held. */
    private fun finishFetch(active: CrpRingHistoryFetcher) {
        handler.removeCallbacks(fetchTimeout)
        fetcher = null
        if (active.skipped.isNotEmpty()) LOG.warn("Skipped history steps: {}", active.skipped)
        CrpRingHistoryStore.save(device, context, active.result)
        device.unsetBusyTask()
        device.sendDeviceUpdateIntent(context)
        GB.signalActivityDataFinish(device)
    }

    private fun getOrCreatePin(): String {
        val prefs = GBApplication.getDeviceSpecificSharedPrefs(device.address)
        val stored = prefs.getString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, null)?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        val pin = (10_000_000 + Random.nextInt(90_000_000)).toString()
        prefs.edit().putString(DeviceSettingsPreferenceConst.PREF_AUTH_KEY, pin).apply()
        LOG.info("Created a new PIN for the band")
        return pin
    }

    private fun userInfoCommand(): ByteArray {
        val user = ActivityUser()
        val gender = if (user.gender == ActivityUser.GENDER_FEMALE) CrpRingConstants.GENDER_FEMALE else CrpRingConstants.GENDER_MALE
        return CrpRingCommands.userInfo(user.heightCm, user.weightKg, user.age, gender, user.stepLengthCm)
    }

    private fun write(builder: TransactionBuilder, frame: ByteArray) {
        for (chunk in CrpRingFrame.split(frame, chunkSize)) {
            builder.write(CrpRingConstants.UUID_CHARACTERISTIC_WRITE, *chunk)
        }
    }

    private fun send(name: String, frame: ByteArray) {
        val builder = createTransactionBuilder(name)
        write(builder, frame)
        builder.queue()
    }
}
```

- [ ] **Step 3: Build the app and run the unit tests**

```bash
cp /home/arc/Dev/Gadgetbridge/local.properties .
./gradlew --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testMainlineDebugUnitTest --tests 'nodomain.freeyourgadget.gadgetbridge.service.devices.crpring.*'
```

Expected: BUILD SUCCESSFUL, 40 tests PASS. If the compiler reports a wrong name (for example a generated property), fix the name in the file that the error names.

- [ ] **Step 4: Commit Tasks 5 and 6**

```bash
git add GBDaoGenerator app/src/main
git commit -m "Pebble Qore Vox: CRP ring device support with health data sync"
```

---

### Task 7: Test on the real band

**Files:** none (manual test).

- [ ] **Step 1: Build the APK for the installed variant**

```bash
./gradlew --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:assembleMainlineNopebble
```

Expected: `app/build/outputs/apk/mainline/nopebble/app-mainline-nopebble.apk`.

- [ ] **Step 2: Install it over the current Gadgetbridge**

```bash
adb install -r app/build/outputs/apk/mainline/nopebble/app-mainline-nopebble.apk
```

If the signature is different, stop and ask the user. Do not uninstall: that deletes the Gadgetbridge database.

- [ ] **Step 3: Connect and check**

1. Close the Pebble Vox app (force stop), so it does not hold the connection.
2. In Gadgetbridge, add `PBL Qore Vox`.
3. Check the battery level and the firmware version on the device card.
4. If the PIN toast shows, record it. The user decides to enter the Vox PIN or to unbind in the Vox app.
5. Pull down to fetch data. Check steps, sleep, heart rate and SpO2 in the charts.
6. Test "Find device" and the heart rate measurement.
7. Collect the log: `adb logcat -d | grep -i crpring`.

- [ ] **Step 4: Record the results in the spec** (a short "Hardware test" section), then commit.
