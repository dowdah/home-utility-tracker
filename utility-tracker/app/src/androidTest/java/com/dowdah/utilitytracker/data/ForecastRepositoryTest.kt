package com.dowdah.utilitytracker.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.test.filters.MediumTest
class ForecastRepositoryTest {
    @Test fun snapshotsIncludeLocalDraftsAndOnlyBlockMetersAffectedByConflicts() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,UtilityDatabase::class.java).build()
        try {
            val repo=ForecastRepository(db)
            for(id in listOf("a","b")) {
                db.meterDao().upsertAll(listOf(MeterEntity(id,"ELECTRICITY",1,"kWh",true,false,1)))
                db.readingDao().upsert(ReadingEntity("${id}1",id,"20","2026-10-01T00:00:00Z",null,false,0))
                db.readingDao().upsert(ReadingEntity("${id}2",id,"10","2026-10-02T00:00:00Z",null,false,0))
            }
            repo.save(MeterReminderEntity("a",daysThreshold=2,quantityThreshold="2.50"))
            repo.save(ReminderScheduleEntity(hour=15,minute=20))
            db.conflictDao().upsert(ConflictEntity("op","reading","a2","{}",null,createdAt="2026-10-03T00:00:00Z"))
            val snapshot=repo.snapshot()
            assertEquals(setOf("a"),snapshot.conflictedMeters)
            val forecasts=snapshot.forecasts(Instant.parse("2026-10-02T12:00:00Z")).associateBy {it.meter.id}
            assertEquals(ForecastIssue.CONFLICT,forecasts.getValue("a").issue)
            assertNull(forecasts.getValue("b").issue)
            assertEquals(0,forecasts.getValue("b").remaining!!.compareTo(java.math.BigDecimal("5")))
            assertEquals("2.5",snapshot.setting("a").quantityThreshold)
            assertEquals(7,snapshot.setting("b").daysThreshold)
            assertEquals(20,snapshot.schedule.minute)
            assertTrue(db.outboxDao().all().isEmpty())
        } finally {db.close()}
    }
}
