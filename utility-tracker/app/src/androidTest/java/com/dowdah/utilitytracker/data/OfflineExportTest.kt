package com.dowdah.utilitytracker.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class OfflineExportTest {
    @Test fun offlineSnapshotIncludesPendingConflictAndDeletionAndIsStableAfterCapture() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        try {
            val at = "2026-10-02T00:00:00Z"
            db.readingDao().upsert(ReadingEntity("pending", "meter", "123.123456789012", at, "中文,\"note\"\nline", false, 0))
            db.readingDao().upsert(ReadingEntity("deleted", "meter", "50", at, null, true, 7))
            db.readingDao().upsert(ReadingEntity("conflict", "meter", "40", at, "latest draft", false, 8))
            for (id in listOf("pending", "deleted", "conflict")) db.outboxDao().upsert(OutboxEntity(entityType = "reading", entityId = id, kind = "upsert", baseRevision = 0, payloadJson = "{}", createdAt = at))
            db.conflictDao().upsert(ConflictEntity("conflict-op", "reading", "conflict", "{}", null, createdAt = at))
            db.rechargeDao().upsert(RechargeEntity("credit", "meter", "60", "0.6", "100.000000000000", "CNY", at, null, true, 9))
            db.tariffDao().upsert(TariffEntity("tariff", "meter", "0.7", "CNY", at, false, 10))
            val snapshot = localCsvSnapshot(db, "readings")
            assertEquals(listOf("conflict", "pending", "pending"), snapshot.rows.map { it.last() })
            assertEquals("1", snapshot.rows[1][5])
            db.readingDao().upsert(ReadingEntity("pending", "meter", "999", at, null, false, 0))
            assertEquals("123.123456789012", snapshot.rows[2][2])
            val repo = BackendRepository(db, SecretStore(context))
            for (kind in listOf("readings", "recharges", "tariffs")) {
                val output = ByteArrayOutputStream()
                repo.exportCsv(output, kind, ExportSource.LOCAL)
                assertTrue(output.toString("UTF-8").lineSequence().first().endsWith(",sync_status"))
                assertFalse(output.toString("UTF-8").contains("Authorization"))
            }
            assertEquals("1", localCsvSnapshot(db, "recharges").rows.single()[8])
            assertEquals("synced", localCsvSnapshot(db, "tariffs").rows.single().last())
        } finally { db.close() }
    }
}
