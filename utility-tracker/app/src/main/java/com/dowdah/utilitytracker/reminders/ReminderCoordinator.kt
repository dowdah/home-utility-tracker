package com.dowdah.utilitytracker.reminders

import com.dowdah.utilitytracker.data.ForecastRepository
import com.dowdah.utilitytracker.data.ReminderScheduleEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class ReminderCoordinator @Inject constructor(
    private val repository: ForecastRepository, private val scheduler: ReminderScheduler,
    private val controller: ReminderController, private val device: ReminderDeviceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    @Synchronized fun start() {
        if (started) return
        started = true
        controller.createChannel()
        scheduler.ensurePeriodic()
        scope.launch {
            var schedule: ReminderScheduleEntity? = null
            repository.snapshots.collect { snapshot ->
                if (schedule != snapshot.schedule) { schedule = snapshot.schedule; scheduler.scheduleNext(snapshot.schedule) }
                scheduler.enqueue()
            }
        }
        scope.launch { device.state.map { it.enabled }.distinctUntilChanged().collect { scheduler.enqueue() } }
    }
    fun onForeground() { scheduler.enqueue() }
}
