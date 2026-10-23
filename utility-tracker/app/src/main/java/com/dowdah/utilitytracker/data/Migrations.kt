package com.dowdah.utilitytracker.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recharges (id TEXT NOT NULL PRIMARY KEY, meterId TEXT NOT NULL, amountDecimal TEXT NOT NULL, unitPriceDecimal TEXT NOT NULL, quantityDecimal TEXT NOT NULL, currency TEXT NOT NULL, creditedAt TEXT NOT NULL, note TEXT, deleted INTEGER NOT NULL, serverRevision INTEGER NOT NULL)")
        db.execSQL("ALTER TABLE outbox ADD COLUMN previousOperationId TEXT")
        db.execSQL("ALTER TABLE outbox ADD COLUMN groupId TEXT")
        db.execSQL("ALTER TABLE outbox ADD COLUMN groupSize INTEGER")
        db.execSQL("ALTER TABLE conflicts ADD COLUMN groupId TEXT")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN serverStatusJson TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meter_reminders (meterId TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, daysThreshold INTEGER NOT NULL, quantityThreshold TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_schedule (id INTEGER NOT NULL PRIMARY KEY, hour INTEGER NOT NULL, minute INTEGER NOT NULL)")
    }
}
