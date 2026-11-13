package nodomain.freeyourgadget.gadgetbridge.database

import android.database.sqlite.SQLiteDatabase
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementEntryDao
import nodomain.freeyourgadget.gadgetbridge.entities.BodyMeasurementValueDao
import nodomain.freeyourgadget.gadgetbridge.entities.DaoMaster
import nodomain.freeyourgadget.gadgetbridge.entities.GarminEstimatedSleepStageSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GarminRawSleepSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.GenericAltitudeSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationAppConfigEntryDao
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationAppFilterEntryDao
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationAppReplacementEntryDao
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DatabaseUpgradeFrom155Test : TestBase() {
    // DBOpenHelper finds the upgrade classes as "<dbName>Update_N", so the name must be the real one.
    private val dbName = "Gadgetbridge"

    /** The tables that upstream versions 157, 159 and 160 add. They have no update class. */
    private val upstreamTablesAfter155 = listOf(
        GarminRawSleepSampleDao.TABLENAME,
        GarminEstimatedSleepStageSampleDao.TABLENAME,
        NotificationAppConfigEntryDao.TABLENAME,
        NotificationAppFilterEntryDao.TABLENAME,
        NotificationAppReplacementEntryDao.TABLENAME,
        GenericAltitudeSampleDao.TABLENAME,
    )

    @Before
    fun removeOldFile() {
        context.deleteDatabase(dbName)
    }

    @After
    fun removeFile() {
        context.deleteDatabase(dbName)
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    @Test
    fun `an upgrade from 155 adds the upstream columns and tables and the body measurement tables`() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            DaoMaster.createAllTables(db, false)
            // Rebuild the two tables that upstream versions 156 and 158 change, as they were at 155.
            db.execSQL("DROP TABLE CYCLING_SAMPLE")
            db.execSQL(
                "CREATE TABLE \"CYCLING_SAMPLE\" (\"TIMESTAMP\" INTEGER NOT NULL, \"DEVICE_ID\" INTEGER NOT NULL, " +
                    "\"USER_ID\" INTEGER NOT NULL, \"REVOLUTION_COUNT\" INTEGER, \"DISTANCE\" REAL, \"SPEED\" REAL, " +
                    "PRIMARY KEY (\"TIMESTAMP\", \"DEVICE_ID\") ON CONFLICT REPLACE) WITHOUT ROWID"
            )
            db.execSQL("DROP TABLE XIAOMI_SLEEP_TIME_SAMPLE")
            db.execSQL(
                "CREATE TABLE \"XIAOMI_SLEEP_TIME_SAMPLE\" (\"TIMESTAMP\" INTEGER NOT NULL, \"DEVICE_ID\" INTEGER NOT NULL, " +
                    "\"USER_ID\" INTEGER NOT NULL, \"WAKEUP_TIME\" INTEGER, \"IS_AWAKE\" INTEGER, \"TOTAL_DURATION\" INTEGER, " +
                    "\"DEEP_SLEEP_DURATION\" INTEGER, \"LIGHT_SLEEP_DURATION\" INTEGER, \"REM_SLEEP_DURATION\" INTEGER, " +
                    "\"AWAKE_DURATION\" INTEGER, PRIMARY KEY (\"TIMESTAMP\", \"DEVICE_ID\") ON CONFLICT REPLACE) WITHOUT ROWID"
            )
            // Remove the tables that came after 155: upstream 157, 159 and 160, and this feature (161).
            GarminRawSleepSampleDao.dropTable(db, false)
            GarminEstimatedSleepStageSampleDao.dropTable(db, false)
            NotificationAppReplacementEntryDao.dropTable(db, false)
            NotificationAppFilterEntryDao.dropTable(db, false)
            NotificationAppConfigEntryDao.dropTable(db, false)
            GenericAltitudeSampleDao.dropTable(db, false)
            BodyMeasurementValueDao.dropTable(db, false)
            BodyMeasurementEntryDao.dropTable(db, false)
            db.version = 155
            assertFalse(DBHelper.existsColumn("CYCLING_SAMPLE", "CADENCE", db))
            assertFalse(DBHelper.existsColumn("XIAOMI_SLEEP_TIME_SAMPLE", "INTO_BED_TIME", db))
            upstreamTablesAfter155.forEach { assertFalse(it, tableExists(db, it)) }
        }

        val helper = DBOpenHelper(context, dbName, null)
        try {
            val db = helper.writableDatabase
            assertEquals(161, db.version)
            assertEquals(DaoMaster.SCHEMA_VERSION, db.version)
            // 156 and 158: GadgetbridgeUpdate_156 and _158 add the columns.
            assertTrue(DBHelper.existsColumn("CYCLING_SAMPLE", "CADENCE", db))
            assertTrue(DBHelper.existsColumn("XIAOMI_SLEEP_TIME_SAMPLE", "INTO_BED_TIME", db))
            // 157, 159 and 160: DaoMaster.createAllTables(db, true) makes the tables, with their current columns.
            upstreamTablesAfter155.forEach { assertTrue(it, tableExists(db, it)) }
            assertTrue(
                DBHelper.existsColumn(
                    NotificationAppConfigEntryDao.TABLENAME,
                    NotificationAppConfigEntryDao.Properties.ForwardLocalOnly.columnName,
                    db,
                )
            )
            // 161: this feature.
            assertTrue(tableExists(db, BodyMeasurementEntryDao.TABLENAME))
            assertTrue(tableExists(db, BodyMeasurementValueDao.TABLENAME))
        } finally {
            helper.close()
        }
    }
}
