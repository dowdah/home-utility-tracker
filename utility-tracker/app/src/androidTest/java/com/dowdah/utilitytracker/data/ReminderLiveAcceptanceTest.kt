package com.dowdah.utilitytracker.data

import android.app.NotificationManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.reminders.*
import dagger.hilt.android.EntryPointAccessors
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@androidx.test.filters.MediumTest
class ReminderLiveAcceptanceTest {
    @Test fun runStep() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val step=args.getString("reminderStep")
        assumeTrue("Use tools/reminder_acceptance.py",step!=null)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".acceptancereminder"))
        val entry=EntryPointAccessors.fromApplication(context,LiveLedgerEntryPoint::class.java)
        val db=entry.database()
        val manager=context.getSystemService(NotificationManager::class.java)
        val now=Instant.now()
        when(step) {
            "denied", "seed" -> {
                entry.reminderDevice().update {ReminderDeviceState()}
                manager.cancel(ReminderController.NOTIFICATION_ID)
                db.meterDao().upsertAll(listOf(MeterEntity("live-meter","ELECTRICITY",1,"kWh",true,false,1)))
                db.readingDao().upsert(ReadingEntity("live-a","live-meter","20",now.minus(Duration.ofDays(2)).toString(),null,false,0))
                db.readingDao().upsert(ReadingEntity("live-b","live-meter","10",now.minusSeconds(1).toString(),null,false,0))
                val next=ZonedDateTime.now().plusMinutes(2).withSecond(0).withNano(0)
                check(next.toLocalDate()==ZonedDateTime.now().toLocalDate()) {"Retry this acceptance after midnight"}
                val schedule=if(step=="denied") ReminderScheduleEntity(hour=0) else ReminderScheduleEntity(hour=next.hour,minute=next.minute)
                db.reminderDao().saveSchedule(schedule)
                entry.reminderDevice().update {it.copy(enabled=true)}
                assertNull(entry.forecasts().snapshot().forecasts(now).single().issue)
                assertTrue(entry.forecasts().snapshot().forecasts(now).single().isLow(MeterReminderEntity("live-meter")))
                assertFalse(entry.repository().isConfigured())
                assertTrue(db.outboxDao().all().isEmpty())
                if(step=="denied") {
                    assertFalse(entry.reminderController().permitted())
                    entry.reminderController().evaluate()
                    assertTrue(manager.activeNotifications.none {it.id==ReminderController.NOTIFICATION_ID})
                    assertTrue(entry.reminderDevice().state.value.notifiedDates.isEmpty())
                } else {
                    assertTrue(entry.reminderController().permitted())
                    entry.reminderScheduler().scheduleNext(schedule).result.get()
                    instrumentation.sendStatus(0,Bundle().apply {putString("scheduled_for",next.toString())})
                }
            }
            "verify" -> {
                assertTrue(entry.reminderDevice().state.value.notifiedDates.contains(ZonedDateTime.now().toLocalDate().toString()))
                val active=manager.activeNotifications.single {it.id==ReminderController.NOTIFICATION_ID}
                assertTrue(active.notification.extras.getString(android.app.Notification.EXTRA_TITLE)!!.isNotBlank())
                assertEquals(ReminderAction.UPDATE,entry.reminderController().evaluate())
                manager.cancel(ReminderController.NOTIFICATION_ID)
                assertEquals(ReminderAction.NONE,entry.reminderController().evaluate())
            }
            "restart" -> {
                assertTrue(entry.reminderDevice().state.value.notifiedDates.isNotEmpty())
                assertEquals(ReminderAction.NONE,entry.reminderController().evaluate())
            }
            else -> error("Unknown reminder step")
        }
    }
}
