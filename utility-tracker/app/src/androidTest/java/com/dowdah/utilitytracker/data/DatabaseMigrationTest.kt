package com.dowdah.utilitytracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class DatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UtilityDatabase::class.java)
    @Test fun migrationPreservesOfflineWorkConflictsAndCursor() {
        val name = "migration-ledger-test"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO meters VALUES ('meter', 'ELECTRICITY', 1, 'kWh', 1, 0, 1)")
            execSQL("INSERT INTO readings VALUES ('reading', 'meter', '100', '2026-09-22T00:00:00Z', 'local draft', 0, 7)")
            execSQL("INSERT INTO outbox VALUES ('operation', 'reading', 'reading', 'upsert', 7, '{}', '2026-09-22T00:00:00Z', 2, 'network')")
            execSQL("INSERT INTO conflicts VALUES ('conflict', 'tariff', 'rate', '{}', NULL, '2026-09-22T00:00:00Z')")
            execSQL("INSERT INTO sync_state VALUES (0, 'backend', 9, 123, 'network')")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT valueDecimal FROM readings WHERE id='reading'").use { assertTrue(it.moveToFirst()); assertEquals("100", it.getString(0)) }
            db.query("SELECT baseRevision,attemptCount,previousOperationId FROM outbox").use { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)); assertEquals(2, it.getInt(1)); assertTrue(it.isNull(2)) }
            db.query("SELECT cursorRevision,backendInstanceId FROM sync_state").use { assertTrue(it.moveToFirst()); assertEquals(9, it.getInt(0)); assertEquals("backend", it.getString(1)) }
            db.query("SELECT COUNT(*) FROM conflicts").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM recharges").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }
}
