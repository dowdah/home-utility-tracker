package com.dowdah.utilitytracker.data

import android.app.backup.BackupAgentHelper
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** The framework stops application writes before invoking full backup. */
class LedgerBackupAgent : BackupAgentHelper() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        val allowed = FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or FLAG_DEVICE_TO_DEVICE_TRANSFER
        if (data.transportFlags and allowed == 0) return
        LedgerRecovery.checkpoint(this)
        // Use framework filtering: the XML allowlist includes only the closed ledger.
        super.onFullBackup(data)
    }

    override fun onRestoreFinished() {
        File(noBackupFilesDir, LedgerRecovery.RESTORE_MARKER).writeText("restore")
        LedgerRecovery.completeRestore(this)
    }
}

object LedgerRecovery {
    const val DATABASE_NAME = "utility-tracker.db"
    const val RESTORE_MARKER = "ledger-restore-pending"

    fun checkpoint(context: Context) {
        val path = context.getDatabasePath(DATABASE_NAME)
        if (!path.exists()) return
        SQLiteDatabase.openDatabase(path.path, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { result ->
                check(result.moveToFirst() && result.getInt(0) == 0 && result.getInt(1) == result.getInt(2)) {
                    "Ledger checkpoint did not finish; backup aborted"
                }
            }
        }
    }

    @Synchronized
    fun completeRestore(context: Context) {
        val marker = File(context.noBackupFilesDir, RESTORE_MARKER)
        if (!marker.exists()) return
        // Clear historical preferences too: older backup payloads included them.
        check(context.getSharedPreferences("utility_tracker_secrets", Context.MODE_PRIVATE).edit().clear().commit())
        val identity = File(context.noBackupFilesDir, "installation-id")
        if (identity.exists()) check(identity.delete())
        android.util.AtomicFile(File(context.noBackupFilesDir, "balance-reminder-device.json")).delete()
        context.getSystemService(android.app.NotificationManager::class.java).cancel(com.dowdah.utilitytracker.reminders.ReminderController.NOTIFICATION_ID)
        val path = context.getDatabasePath(DATABASE_NAME)
        if (path.exists()) SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("UPDATE endpoints SET healthStatus='UNKNOWN', healthDetail=NULL, healthCheckedAt=NULL, healthLatencyMs=NULL")
                db.execSQL("UPDATE sync_state SET lastSuccessAt=NULL, serverStatusJson=NULL, lastError='No token configured'")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(marker.delete())
    }
}
