package nodomain.freeyourgadget.gadgetbridge.database.repository

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.GenericWeightSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.GenericWeightSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BodyMeasurementRepositoryTest : TestBase() {
    private lateinit var repo: BodyMeasurementRepository

    private val waist = BodyMeasurementType.WAIST_NAVEL.code
    private val hips = BodyMeasurementType.HIPS.code
    private val thigh = BodyMeasurementType.THIGH_LEFT.code
    private val weight = BodyMeasurementType.WEIGHT.code
    private val unknownCode = 999

    @Before
    fun setUpRepository() {
        repo = BodyMeasurementRepository(daoSession)
    }

    private fun millis(text: String): Long =
        LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `saves a new entry with its values and note`() {
        val id = repo.save(null, 1_000L, " after a run ", mapOf(waist to 81.9f, hips to 98f), emptySet())
        val stored = repo.entry(id)!!
        assertEquals(1_000L, stored.timestamp)
        assertEquals("after a run", stored.note)
        assertEquals(mapOf(waist to 81.9f, hips to 98f), stored.values)
    }

    @Test
    fun `a blank note is stored as null`() {
        val id = repo.save(null, 1_000L, "   ", mapOf(waist to 81.9f), emptySet())
        assertNull(repo.entry(id)!!.note)
    }

    @Test
    fun `an edit replaces changed values and deletes cleared values`() {
        val id = repo.save(null, 1_000L, null, mapOf(waist to 81.9f, hips to 98f), emptySet())
        repo.save(id, 2_000L, null, mapOf(waist to 80.5f), setOf(hips))
        val stored = repo.entry(id)!!
        assertEquals(2_000L, stored.timestamp)
        assertEquals(mapOf(waist to 80.5f), stored.values)
    }

    @Test
    fun `an edit keeps values of hidden types and unknown codes`() {
        val id = repo.save(null, 1_000L, null, mapOf(waist to 81.9f, thigh to 55f, unknownCode to 1.5f), emptySet())
        repo.save(id, 1_000L, null, mapOf(waist to 81f), emptySet())
        assertEquals(mapOf(waist to 81f, thigh to 55f, unknownCode to 1.5f), repo.entry(id)!!.values)
    }

    @Test
    fun `delete removes the entry and its values`() {
        val id = repo.save(null, 1_000L, "x", mapOf(waist to 81.9f, unknownCode to 1f), emptySet())
        repo.delete(id)
        assertNull(repo.entry(id))
        assertEquals(0L, daoSession.bodyMeasurementValueDao.count())
    }

    @Test
    fun `entries are newest first and inside the range`() {
        repo.save(null, 1_000L, null, mapOf(waist to 1f), emptySet())
        repo.save(null, 3_000L, null, mapOf(waist to 3f), emptySet())
        repo.save(null, 2_000L, null, mapOf(waist to 2f), emptySet())
        repo.save(null, 9_000L, null, mapOf(waist to 9f), emptySet())
        assertEquals(listOf(3_000L, 2_000L, 1_000L), repo.entries(0L, 3_000L).map { it.timestamp })
    }

    @Test
    fun `latest value is the newest before the time and skips the excluded entry`() {
        repo.save(null, 1_000L, null, mapOf(waist to 80f), emptySet())
        val second = repo.save(null, 2_000L, null, mapOf(waist to 81f), emptySet())
        repo.save(null, 3_000L, null, mapOf(hips to 99f), emptySet())
        assertEquals(TimedValue(2_000L, 81f), repo.latestValue(waist, 5_000L, null))
        assertEquals(TimedValue(1_000L, 80f), repo.latestValue(waist, 5_000L, second))
        assertNull(repo.latestValue(waist, 1_000L, null))
    }

    @Test
    fun `weight series merges typed and scale weights and skips test devices`() {
        val scale = GBDevice("00:00:00:00:00:31", "Scale", null, null, DeviceType.GENERIC_WEIGHT_SCALE)
        DBHelper.getDevice(scale, daoSession)
        val sample = GenericWeightSample().apply {
            timestamp = 1_000_000L
            weightKg = 80f
        }
        GenericWeightSampleProvider(scale, daoSession).persistSamples(listOf(sample), context)
        // A test device makes a synthetic weight for every day; it must not show up.
        DBHelper.getDevice(createDummyGDevice("00:00:00:00:00:32"), daoSession)
        repo.save(null, 2_000_000L, null, mapOf(weight to 79.5f), emptySet())

        val series = repo.weightSeries(0L, 3_000_000L)
        assertEquals(listOf(WeightPoint(1_000_000L, 80f, true), WeightPoint(2_000_000L, 79.5f, false)), series)
    }

    @Test
    fun `scale weight on day picks the nearest sample of that local day`() {
        val scale = GBDevice("00:00:00:00:00:33", "Scale", null, null, DeviceType.GENERIC_WEIGHT_SCALE)
        DBHelper.getDevice(scale, daoSession)
        val provider = GenericWeightSampleProvider(scale, daoSession)
        provider.persistSamples(
            listOf(
                GenericWeightSample().apply { timestamp = millis("2026-11-03T23:30:00"); weightKg = 70f },
                GenericWeightSample().apply { timestamp = millis("2026-11-04T07:00:00"); weightKg = 81f },
                GenericWeightSample().apply { timestamp = millis("2026-11-04T20:00:00"); weightKg = 82f },
            ),
            context,
        )
        assertEquals(81f, repo.scaleWeightOnDay(millis("2026-11-04T08:00:00"))!!, 0f)
        assertNull(repo.scaleWeightOnDay(millis("2026-11-05T08:00:00")))
    }
}
