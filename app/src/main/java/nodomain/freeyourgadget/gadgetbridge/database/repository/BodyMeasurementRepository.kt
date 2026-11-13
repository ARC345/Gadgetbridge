/*  Copyright (C) 2026 ARC345

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

package nodomain.freeyourgadget.gadgetbridge.database.repository

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementEntry
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementEntryDao
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementValue
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementValueDao
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.model.BodyMeasurementType
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementEntryDao.Properties as EntryColumns
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementValueDao.Properties as ValueColumns

/** One time that the user measured, with all of its stored values (type code to value). */
data class BodyMeasurement(val id: Long, val timestamp: Long, val note: String?, val values: Map<Int, Float>)

data class TimedValue(val timestamp: Long, val value: Float)

/** A weight for the chart. [fromScale] is false for a weight that the user typed. */
data class WeightPoint(val timestamp: Long, val weightKg: Float, val fromScale: Boolean)

/**
 * All database work for the body measurements. The data belongs to the user, not to a device.
 * The caller opens the database and calls this off the main thread.
 */
class BodyMeasurementRepository(private val session: DaoSession) {
    private val entryDao get() = session.bodyMeasurementEntryDao
    private val valueDao get() = session.bodyMeasurementValueDao

    /** The entries in the range (both ends included), newest first, each with all value rows. */
    fun entries(fromMillis: Long, toMillis: Long): List<BodyMeasurement> {
        val rows = entryDao.queryBuilder()
            .where(EntryColumns.Timestamp.between(fromMillis, toMillis))
            .orderDesc(EntryColumns.Timestamp)
            .list()
        val values = valuesOf(rows.map { it.id!! })
        return rows.map { BodyMeasurement(it.id!!, it.timestamp, it.note, values[it.id].orEmpty()) }
    }

    fun entry(id: Long): BodyMeasurement? {
        val row = entryDao.load(id) ?: return null
        return BodyMeasurement(row.id!!, row.timestamp, row.note, valuesOf(listOf(row.id!!))[row.id!!].orEmpty())
    }

    private fun valuesOf(entryIds: List<Long>): Map<Long, Map<Int, Float>> {
        val result = HashMap<Long, MutableMap<Int, Float>>()
        // SQLite permits at most 999 parameters in one statement.
        for (chunk in entryIds.chunked(500)) {
            valueDao.queryBuilder().where(ValueColumns.EntryId.`in`(chunk)).list().forEach {
                result.getOrPut(it.entryId) { HashMap() }[it.type] = it.value
            }
        }
        return result
    }

    /** The newest value of one type before a time, for the "Last:" helper text. */
    fun latestValue(typeCode: Int, beforeMillis: Long, excludeEntryId: Long?): TimedValue? {
        val entryTable = BodyMeasurementEntryDao.TABLENAME
        val valueTable = BodyMeasurementValueDao.TABLENAME
        val ts = EntryColumns.Timestamp.columnName
        val id = EntryColumns.Id.columnName
        val sql = "SELECT e.$ts, v.${ValueColumns.Value.columnName} FROM $valueTable v" +
            " JOIN $entryTable e ON e.$id = v.${ValueColumns.EntryId.columnName}" +
            " WHERE v.${ValueColumns.Type.columnName} = ? AND e.$ts < ? AND e.$id != ?" +
            " ORDER BY e.$ts DESC LIMIT 1"
        val args = arrayOf(typeCode.toString(), beforeMillis.toString(), (excludeEntryId ?: -1L).toString())
        session.database.rawQuery(sql, args).use { cursor ->
            return if (cursor.moveToFirst()) TimedValue(cursor.getLong(0), cursor.getFloat(1)) else null
        }
    }

    /**
     * Inserts or updates an entry in one transaction. Only the [upserts] are written and only the
     * [clearedTypes] are deleted, so the values of hidden types and of unknown codes stay.
     *
     * @return the id of the entry
     */
    fun save(id: Long?, timestamp: Long, note: String?, upserts: Map<Int, Float>, clearedTypes: Set<Int>): Long {
        var entryId = 0L
        session.runInTx {
            val entry = id?.let { entryDao.load(it) } ?: BodyMeasurementEntry()
            entry.userId = DBHelper.getUser(session).id!!
            entry.timestamp = timestamp
            entry.note = note?.trim()?.takeIf { it.isNotEmpty() }
            entryId = entryDao.insertOrReplace(entry)
            if (clearedTypes.isNotEmpty()) {
                // The value key has two columns, so the DAO delete methods throw; use a query.
                valueDao.queryBuilder()
                    .where(ValueColumns.EntryId.eq(entryId), ValueColumns.Type.`in`(clearedTypes))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities()
            }
            if (upserts.isNotEmpty()) {
                valueDao.insertOrReplaceInTx(upserts.map { (type, value) -> BodyMeasurementValue(entryId, type, value) })
            }
        }
        return entryId
    }

    fun delete(entryId: Long) {
        session.runInTx {
            valueDao.queryBuilder()
                .where(ValueColumns.EntryId.eq(entryId))
                .buildDelete()
                .executeDeleteWithoutDetachingEntities()
            entryDao.deleteByKey(entryId)
        }
    }

    /** The typed weights and the weight samples of all scales in the range, oldest first. */
    fun weightSeries(fromMillis: Long, toMillis: Long): List<WeightPoint> {
        val typed = entries(fromMillis, toMillis).mapNotNull { entry ->
            entry.values[BodyMeasurementType.WEIGHT.code]?.let { WeightPoint(entry.timestamp, it, false) }
        }
        val scale = scaleProviders().flatMap { it.getAllSamples(fromMillis, toMillis) }
            .map { WeightPoint(it.timestamp, it.weightKg, true) }
        return (typed + scale).sortedBy { it.timestamp }
    }

    /** The scale weight nearest in time on the same local day, for BMI without a typed weight. */
    fun scaleWeightOnDay(timestampMillis: Long): Float? {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return scaleProviders().flatMap { it.getAllSamples(start, end) }
            .minByOrNull { abs(it.timestamp - timestampMillis) }
            ?.weightKg
    }

    /**
     * The weight providers of all devices in the database. The device manager is not used,
     * because its list is not complete in tests and it does not use this session.
     */
    private fun scaleProviders(): List<TimeSampleProvider<out WeightSample>> =
        DBHelper.getActiveDevices(session).mapNotNull { dbDevice ->
            val device = DeviceHelper.getInstance().toGBDevice(dbDevice) ?: return@mapNotNull null
            // A test device makes synthetic weights for every day, and an unknown type has no provider.
            if (device.type == DeviceType.TEST || device.type == DeviceType.UNKNOWN) {
                null
            } else {
                device.deviceCoordinator.getWeightSampleProvider(device, session)
            }
        }
}
