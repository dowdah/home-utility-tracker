package com.dowdah.utilitytracker.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dowdah.utilitytracker.UtilityTrackerApplication
import com.dowdah.utilitytracker.sync.SyncScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class WorkSchedulerTest {
    @Test fun periodicRegistrationPreservesIdentityAndForegroundEventsAreDebounced() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as UtilityTrackerApplication
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setWorkerFactory(app.workerFactory).setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        try {
            val scheduler = SyncScheduler(context)
            scheduler.ensurePeriodic().result.get()
            val original = manager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC).get().single()
            scheduler.ensurePeriodic().result.get()
            val updated = manager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC).get().single()
            assertEquals(original.id, updated.id)
            assertEquals(15 * 60 * 1000L, updated.periodicityInfo!!.repeatIntervalMillis)
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            driver.setAllConstraintsMet(updated.id)
            driver.setInitialDelayMet(updated.id)
            driver.setPeriodDelayMet(updated.id)
            assertEquals(1, manager.getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_SYNC).get().size)
            scheduler.onForeground(1000)
            scheduler.onForeground(2000)
            assertEquals(1, manager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_SYNC).get().size)
            scheduler.onForeground(61_000)
            assertEquals(2, manager.getWorkInfosForUniqueWork(SyncScheduler.UNIQUE_SYNC).get().size)
        } finally {
            manager.cancelAllWork().result.get()
            WorkManagerImpl.setDelegate(null)
        }
    }
}
