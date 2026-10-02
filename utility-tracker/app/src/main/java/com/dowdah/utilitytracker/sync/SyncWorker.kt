package com.dowdah.utilitytracker.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dowdah.utilitytracker.data.BackendRepository
import com.dowdah.utilitytracker.data.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** A unique constrained worker; expected configuration and auth failures remain visible to the UI. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: BackendRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!repository.isConfigured()) {
            repository.sync() // Persist the configuration prompt without a network request.
            return Result.success()
        }
        val syncResult = repository.sync()
        Log.i(TAG, "Completed sync worker periodic=${inputData.getBoolean("periodic", false)} with ${syncResult.javaClass.simpleName}")
        return when (syncResult) {
            SyncResult.Success -> Result.success()
            SyncResult.ConflictDetected -> if (inputData.getBoolean("periodic", false)) Result.success() else Result.failure()
            is SyncResult.Retryable -> Result.retry()
            is SyncResult.ActionRequired -> if (inputData.getBoolean("periodic", false)) Result.success() else Result.failure()
        }
    }

    private companion object { const val TAG = "UtilityTrackerSync" }
}
