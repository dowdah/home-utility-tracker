package com.dowdah.utilitytracker.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {
    private var lastForegroundAt: Long? = null
    fun ensurePeriodic() = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_SYNC, ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf("periodic" to true)).build(),
    )
    @Synchronized
    fun onForeground(now: Long = SystemClock.elapsedRealtime()) {
        if (lastForegroundAt == null || now - requireNotNull(lastForegroundAt) >= 60_000) {
            lastForegroundAt = now
            enqueue()
        }
    }
    fun enqueue() = WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_SYNC,
        // KEEP loses a mutation enqueued while an earlier sync is running. Appending
        // preserves one named chain while guaranteeing a follow-up pass for new outbox work.
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        ).build(),
    )

    companion object {
        const val UNIQUE_SYNC = "utility-tracker-sync"
        const val PERIODIC_SYNC = "utility-tracker-periodic-sync"
    }
}
