package com.dowdah.utilitytracker.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class BackupRecoveryTest {
    @Test fun checkpointAndRestorePreserveLedgerQueuesAndInvalidateOnlyDeviceState() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(directory.name + name, mode)
        }
        val db = Room.databaseBuilder(context, UtilityDatabase::class.java, LedgerRecovery.DATABASE_NAME).build()
        try {
            db.endpointDao().upsert(EndpointEntity("endpoint", "test", "http://localhost", true, "HEALTHY", "old", 1, 2))
            db.readingDao().upsert(ReadingEntity("reading", "meter", "42.125", "2026-10-02T00:00:00Z", "note", false, 8))
            val operation = OutboxEntity("op", "reading", "reading", "upsert", 8, "{\"value_decimal\":\"42.125\"}", "2026-10-02T00:00:00Z", groupId = "group", groupSize = 2, attemptCount = 1)
            db.outboxDao().upsert(operation)
            val conflict = ConflictEntity("conflict", "recharge", "credit", "{}", null, "group", "2026-10-02T00:00:00Z")
            db.conflictDao().upsert(conflict)
            db.syncStateDao().upsert(SyncStateEntity(backendInstanceId = "backend", cursorRevision = 12, lastSuccessAt = 5, serverStatusJson = "{}"))
            val setting = MeterReminderEntity("meter", false, 12, "20.5")
            db.reminderDao().saveMeter(setting)
            db.reminderDao().saveSchedule(ReminderScheduleEntity(hour=18,minute=25))
            File(context.noBackupFilesDir, com.dowdah.utilitytracker.reminders.ReminderDeviceStore.FILE_NAME).writeText("{\"enabled\":true,\"dates\":[\"2026-10-03\"]}")
            val secrets = SecretStore(context)
            secrets.saveToken("synthetic-backup-token")
            val oldIdentity = secrets.installationId()
            db.close()
            LedgerRecovery.checkpoint(context)
            File(context.noBackupFilesDir, LedgerRecovery.RESTORE_MARKER).writeText("restore")
            LedgerRecovery.completeRestore(context)
            val restored = Room.databaseBuilder(context, UtilityDatabase::class.java, LedgerRecovery.DATABASE_NAME).build()
            try {
                assertEquals(setting, restored.reminderDao().meters().single())
                assertEquals(ReminderScheduleEntity(hour=18,minute=25), restored.reminderDao().schedule())
                assertFalse(File(context.noBackupFilesDir, com.dowdah.utilitytracker.reminders.ReminderDeviceStore.FILE_NAME).exists())
                assertEquals(operation, restored.outboxDao().all().single())
                assertEquals(conflict, restored.conflictDao().all().single())
                assertEquals("42.125", restored.readingDao().byId("reading")!!.valueDecimal)
                val state = restored.syncStateDao().current()!!
                assertEquals("backend", state.backendInstanceId); assertEquals(12L, state.cursorRevision)
                assertNull(state.lastSuccessAt); assertNull(state.serverStatusJson)
                assertEquals("UNKNOWN", restored.endpointDao().active()!!.healthStatus)
                assertNull(SecretStore(context).token())
                assertNotEquals(oldIdentity, SecretStore(context).installationId())
                LedgerRecovery.completeRestore(context)
                assertEquals(operation, restored.outboxDao().all().single())
            } finally { restored.close() }
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun ordinaryUpgradeKeepsWorkingTokenAndLegacyIdentity() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "secret-upgrade-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(directory.name + name, mode)
        }
        val secrets = SecretStore(context)
        secrets.saveToken("synthetic-upgrade-token")
        context.getSharedPreferences("utility_tracker_secrets", Context.MODE_PRIVATE).edit().putString("installation_id", "legacy-id").commit()
        assertEquals("legacy-id", secrets.installationId())
        assertEquals("legacy-id", SecretStore(context).installationId())
        assertEquals("synthetic-upgrade-token", SecretStore(context).token())
        secrets.clearToken(); directory.deleteRecursively()
    }
}
