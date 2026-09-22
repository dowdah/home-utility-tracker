package com.dowdah.utilitytracker.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue() = WorkManager.getInstance(context).enqueueUniqueWork(
        UNIQUE_SYNC,
        // KEEP loses a mutation enqueued while an earlier sync is running. Appending
        // preserves one named chain while guaranteeing a follow-up pass for new outbox work.
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        ).build(),
    )

    private companion object { const val UNIQUE_SYNC = "utility-tracker-sync" }
}
