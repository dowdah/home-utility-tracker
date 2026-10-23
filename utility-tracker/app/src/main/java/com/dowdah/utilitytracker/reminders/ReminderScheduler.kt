package com.dowdah.utilitytracker.reminders

import android.content.Context
import androidx.work.*
import androidx.hilt.work.HiltWorker
import com.dowdah.utilitytracker.data.ReminderScheduleEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val manager get() = WorkManager.getInstance(context)
    fun ensurePeriodic() = manager.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,
        PeriodicWorkRequestBuilder<ReminderWorker>(1,TimeUnit.HOURS).setInitialDelay(1,TimeUnit.HOURS).build())
    fun enqueue() = manager.enqueueUniqueWork(IMMEDIATE,ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<ReminderWorker>().build())
    fun scheduleNext(schedule: ReminderScheduleEntity, now: ZonedDateTime = ZonedDateTime.now()): Operation {
        var next = now.withHour(schedule.hour).withMinute(schedule.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return manager.enqueueUniqueWork(TIMED,ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(Duration.between(now,next).toMillis(),TimeUnit.MILLISECONDS).build())
    }
    companion object {
        const val PERIODIC = "utility-balance-hourly"
        const val IMMEDIATE = "utility-balance-evaluate"
        const val TIMED = "utility-balance-daily"
    }
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val controller: ReminderController,
    private val repository: com.dowdah.utilitytracker.data.ForecastRepository,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = try {
        val action = controller.evaluate()
        android.util.Log.i("BalanceReminder", "Background evaluation action=${action.name}")
        scheduler.scheduleNext(repository.snapshot().schedule)
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
