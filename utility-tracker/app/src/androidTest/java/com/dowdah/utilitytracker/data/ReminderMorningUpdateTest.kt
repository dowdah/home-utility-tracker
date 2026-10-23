package com.dowdah.utilitytracker.data

import android.app.Notification
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dowdah.utilitytracker.UtilityTrackerApplication
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.reminders.*
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@androidx.test.filters.MediumTest
class ReminderMorningUpdateTest {
    @Test fun yesterdaySummaryDropsRecoveredAndInvalidMetersBeforeTheNextDailyAlert() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.contains(".acceptance"))
        // Exercise the real controller and NotificationManager against an independent ledger.
        // Automatic empty-ledger evaluations must not cancel this fixture's notification.
        val originalManager = WorkManager.getInstance(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: android.content.Context, workerClassName: String,
                    workerParameters: WorkerParameters): androidx.work.ListenableWorker? =
                    if (workerClassName == ReminderWorker::class.java.name) IgnoreReminderWorker(appContext, workerParameters)
                    else (context.applicationContext as UtilityTrackerApplication).workerFactory
                        .createWorker(appContext, workerClassName, workerParameters)
            }).build())
        withContext(Dispatchers.IO) { originalManager.cancelAllWork().result.get(5, TimeUnit.SECONDS) }
        // Serialize with any evaluation already running on the original controller.
        dagger.hilt.android.EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java)
            .reminderController().evaluate()
        val db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        val device = ReminderDeviceStore(context)
        val original = device.state.value
        val manager = context.getSystemService(NotificationManager::class.java)
        val controller = ReminderController(context, ForecastRepository(db), device)
        val zone = ZoneId.of("Asia/Shanghai")
        val yesterday = ZonedDateTime.now(zone).minusDays(1).withHour(15).withMinute(0).withSecond(0).withNano(0)
        val morning = yesterday.plusDays(1).withHour(9)
        val afternoon = morning.withHour(15)
        fun lines(): List<String> = manager.activeNotifications.single { it.id == ReminderController.NOTIFICATION_ID }
            .notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        suspend fun awaitLines(count: Int) = withTimeout(5_000) {
            while (manager.activeNotifications.none { it.id == ReminderController.NOTIFICATION_ID } || lines().size != count) delay(50)
        }
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            db.reminderDao().saveSchedule(ReminderScheduleEntity())
            for (type in listOf("ELECTRICITY", "COLD_WATER", "HOT_WATER")) {
                db.meterDao().upsertAll(listOf(MeterEntity(type, type, 1, "unit", true, false, 1)))
                db.readingDao().upsert(ReadingEntity("$type-a", type, "80", yesterday.minusDays(7).toInstant().toString(), null, false, 1))
                db.readingDao().upsert(ReadingEntity("$type-b", type, "10", yesterday.toInstant().toString(), null, false, 1))
            }
            device.update { ReminderDeviceState(enabled = true) }
            assertEquals(ReminderAction.ALERT, controller.evaluate(yesterday.toInstant(), zone))
            awaitLines(3)
            assertEquals(setOf(yesterday.toLocalDate().toString()), device.state.value.notifiedDates)

            db.rechargeDao().upsert(RechargeEntity("electric-credit", "ELECTRICITY", "200", "1", "200", "CNY", morning.minusSeconds(1).toInstant().toString(), null, false, 1))
            assertEquals(ReminderAction.UPDATE, controller.evaluate(morning.toInstant(), zone))
            awaitLines(2)
            assertFalse(lines().any { it.startsWith(context.getString(R.string.electricity) + ":") })
            assertTrue(manager.activeNotifications.single { it.id == ReminderController.NOTIFICATION_ID }
                .notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(setOf(yesterday.toLocalDate().toString()), ReminderDeviceStore(context).state.value.notifiedDates)

            // A single invalid forecast must disappear while hot water remains low.
            db.readingDao().upsert(ReadingEntity("water-future", "COLD_WATER", "10", morning.plusDays(1).toInstant().toString(), null, false, 1))
            assertEquals(ReminderAction.UPDATE, controller.evaluate(morning.toInstant(), zone))
            awaitLines(1)
            assertTrue(lines().single().startsWith(context.getString(R.string.hot_water) + ":"))

            // Complete recovery cancels an actually visible notification before the selected time.
            db.rechargeDao().upsert(RechargeEntity("hot-credit", "HOT_WATER", "200", "1", "200", "CNY", morning.minusSeconds(1).toInstant().toString(), null, false, 1))
            assertEquals(ReminderAction.CANCEL, controller.evaluate(morning.toInstant(), zone))
            withTimeout(5_000) { while (manager.activeNotifications.any { it.id == ReminderController.NOTIFICATION_ID }) delay(50) }
            // A newly low meter must wait for the daily time once the summary is absent.
            db.rechargeDao().delete("hot-credit")
            assertEquals(ReminderAction.NONE, controller.evaluate(morning.toInstant(), zone))
            assertEquals(ReminderAction.ALERT, controller.evaluate(afternoon.toInstant(), zone))
            awaitLines(1)
            assertTrue(device.state.value.notifiedDates.contains(afternoon.toLocalDate().toString()))
            manager.cancel(ReminderController.NOTIFICATION_ID)
            withTimeout(5_000) { while (manager.activeNotifications.any { it.id == ReminderController.NOTIFICATION_ID }) delay(50) }
            assertEquals(ReminderAction.NONE, controller.evaluate(afternoon.toInstant(), zone))

        } finally {
            manager.cancel(ReminderController.NOTIFICATION_ID)
            device.update { original }
            db.close()
            withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS) }
            WorkManagerImpl.setDelegate(null)
        }
    }

    private class IgnoreReminderWorker(context: android.content.Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork() = Result.success()
    }
}
