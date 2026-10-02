package com.dowdah.utilitytracker.data

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in host-driven bmgr test; the host clears ONLY the isolated acceptance package. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class BackupTransportAcceptanceTest {
    @Test fun runStep() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val step = args.getString("backupStep")
        assumeTrue("Run through tools/backup_acceptance.py", step != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.contains(".acceptance"))
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, LiveLedgerEntryPoint::class.java)
        val db = entry.database()
        val secrets = entry.secrets()
        val at = "2026-10-02T00:00:00Z"
        val reading = ReadingEntity("backup-reading", "backup-meter", "42.125", at, "synthetic\nfixture", false, 8)
        val operation = OutboxEntity("backup-operation", "reading", reading.id, "upsert", 8, "{\"value_decimal\":\"42.125\"}", at, groupId = "backup-group", groupSize = 2, attemptCount = 1)
        val credit = RechargeEntity("backup-credit", "backup-meter", "60", "0.6", "100", "CNY", at, null, false, 9)
        val second = OutboxEntity("backup-credit-operation", "recharge", credit.id, "upsert", 9, "{\"quantity_decimal\":\"100\"}", at, groupId = "backup-group", groupSize = 2, attemptCount = 1)
        val child = OutboxEntity("backup-child", "reading", reading.id, "tombstone", 8, "{}", at, previousOperationId = operation.operationId)
        val conflict = ConflictEntity("backup-conflict", "tariff", "backup-tariff", "{\"price_decimal\":\"0.7\"}", null, "conflict-group", at)
        when (step) {
            "seed" -> {
                assertTrue(db.outboxDao().all().isEmpty())
                db.meterDao().upsertAll(listOf(MeterEntity("backup-meter", "ELECTRICITY", 1, "kWh", true, false, 1)))
                db.endpointDao().upsert(EndpointEntity("backup-endpoint", "synthetic", "http://127.0.0.1:1", true, "HEALTHY", "old", 1, 2))
                db.readingDao().upsert(reading); db.rechargeDao().upsert(credit)
                db.tariffDao().upsert(TariffEntity("backup-tariff", "backup-meter", "0.7", "CNY", at, false, 10))
                listOf(operation, second, child).forEach { db.outboxDao().upsert(it) }
                db.conflictDao().upsert(conflict)
                db.syncStateDao().upsert(SyncStateEntity(backendInstanceId = "backup-backend", cursorRevision = 12, lastSuccessAt = 5, serverStatusJson = "{}"))
                secrets.saveToken("synthetic-transport-token")
                File(context.filesDir, "not-backed-up").writeText("synthetic")
                context.getSharedPreferences("not-backed-up", 0).edit().putString("sentinel", "synthetic").commit()
                instrumentation.sendStatus(0, Bundle().apply { putString("old_identity", secrets.installationId()) })
            }
            "upgrade" -> {
                assertEquals("synthetic-transport-token", secrets.token())
                assertEquals(args.getString("oldIdentity"), secrets.installationId())
                assertEquals(reading, db.readingDao().byId(reading.id))
            }
            "empty" -> {
                assertTrue(db.readingDao().active().isEmpty())
                assertTrue(db.outboxDao().all().isEmpty())
                assertNull(secrets.token())
            }
            "restored" -> {
                assertEquals(reading, db.readingDao().byId(reading.id)); assertEquals(credit, db.rechargeDao().byId(credit.id))
                assertEquals(listOf(operation, second, child), db.outboxDao().all())
                assertEquals(conflict, db.conflictDao().all().single())
                val state = db.syncStateDao().current()!!
                assertEquals("backup-backend", state.backendInstanceId); assertEquals(12L, state.cursorRevision)
                assertNull(state.lastSuccessAt); assertNull(state.serverStatusJson)
                assertEquals("UNKNOWN", db.endpointDao().active()!!.healthStatus)
                assertNull(secrets.token()); assertNotEquals(args.getString("oldIdentity"), secrets.installationId())
                assertFalse(File(context.filesDir, "not-backed-up").exists())
                assertTrue(context.getSharedPreferences("not-backed-up", 0).all.isEmpty())
            }
            else -> error("Unknown backup step")
        }
    }
}
