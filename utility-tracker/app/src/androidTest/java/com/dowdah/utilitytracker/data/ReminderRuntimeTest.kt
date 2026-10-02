package com.dowdah.utilitytracker.data

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.reminders.*
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.test.filters.MediumTest
class ReminderRuntimeTest {
    @Test fun realNotificationPermissionPersistenceAndRecovery() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName.contains(".acceptance"))
        val entry=EntryPointAccessors.fromApplication(context,LiveLedgerEntryPoint::class.java)
        val db=entry.database()
        val controller=entry.reminderController()
        val device=entry.reminderDevice()
        val manager=context.getSystemService(NotificationManager::class.java)
        val now=Instant.now()
        val id="runtime-reminder"
        val localDay=now.atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val oldSchedule=db.reminderDao().schedule()
        try {
            controller.createChannel()
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
            db.meterDao().upsertAll(listOf(MeterEntity(id,"ELECTRICITY",1,"kWh",true,false,1)))
            db.readingDao().upsert(ReadingEntity("runtime-a",id,"20",now.minus(Duration.ofDays(1)).toString(),null,false,0))
            db.readingDao().upsert(ReadingEntity("runtime-b",id,"10",now.toString(),null,false,0))
            db.reminderDao().saveSchedule(ReminderScheduleEntity(hour=0))
            device.update {it.copy(enabled=true,notifiedDates=emptySet())}
            // Application observation may have already posted; the same singleton serializes both paths.
            controller.evaluate(now)
            assertTrue(manager.activeNotifications.any {it.id==ReminderController.NOTIFICATION_ID})
            assertTrue(ReminderDeviceStore(context).state.value.notifiedDates.contains(localDay))
            assertEquals(ReminderAction.UPDATE,controller.evaluate(now))
            manager.cancel(ReminderController.NOTIFICATION_ID)
            assertEquals(ReminderAction.NONE,controller.evaluate(now))
            assertFalse(manager.activeNotifications.any {it.id==ReminderController.NOTIFICATION_ID})
            db.rechargeDao().upsert(RechargeEntity("runtime-credit",id,"200","1","200","CNY",now.plusSeconds(1).toString(),null,false,0))
            assertEquals(ReminderAction.CANCEL,controller.evaluate(now.plusSeconds(2)))
            assertTrue(device.state.value.notifiedDates.contains(localDay))
            device.update {it.copy(enabled=false)}
            assertEquals(ReminderAction.CANCEL,controller.evaluate(now))
        } finally {
            device.update {ReminderDeviceState()}
            manager.cancel(ReminderController.NOTIFICATION_ID)
            db.readingDao().delete("runtime-a");db.readingDao().delete("runtime-b");db.rechargeDao().delete("runtime-credit")
            db.meterDao().upsertAll(listOf(MeterEntity(id,"ELECTRICITY",1,"kWh",false,true,1)))
            db.reminderDao().saveSchedule(oldSchedule ?: ReminderScheduleEntity())
        }
    }
}
