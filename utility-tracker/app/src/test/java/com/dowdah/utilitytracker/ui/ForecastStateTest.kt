package com.dowdah.utilitytracker.ui

import com.dowdah.utilitytracker.data.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForecastStateTest {
    private val now = Instant.parse("2026-10-03T00:00:00Z")
    private val snapshot = ForecastSnapshot(
        listOf(MeterEntity("m", "ELECTRICITY", 1, "kWh", true, false, 1)),
        listOf(ReadingEntity("a", "m", "20", now.minusSeconds(86_400).toString(), null, false, 1),
            ReadingEntity("b", "m", "10", now.toString(), null, false, 1)),
        emptyList(), emptySet(), emptyList(), ReminderScheduleEntity(),
    )

    @Test fun cachedCollectorsAndEqualSnapshotsDoNotRecalculateButClockAndInputsDo() = runTest {
        val snapshots = MutableSharedFlow<ForecastSnapshot>(replay = 1).apply { tryEmit(snapshot) }
        var calculations = 0
        val state = forecastStates(snapshots, forecastTicks { now.plusMillis(testScheduler.currentTime) },
            StandardTestDispatcher(testScheduler)) { input, time ->
            calculations++
            calculateForecastState(input, time)
        }.stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), null)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        runCurrent()
        assertEquals(1, calculations)
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        snapshots.emit(snapshot.copy(readings = snapshot.readings.toList()))
        repeat(20) { assertNotNull(state.value) }
        runCurrent()
        assertEquals(1, calculations)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calculations)
        assertTrue(state.value!!.meters.getValue("m").forecast.remaining!! < java.math.BigDecimal.TEN)
        snapshots.emit(snapshot.copy(recharges = listOf(RechargeEntity("credit", "m", "200", "1", "200", "CNY", now.plusSeconds(1).toString(), null, false, 1))))
        runCurrent()
        assertEquals(3, calculations)
        assertFalse(state.value!!.meters.getValue("m").low)
        snapshots.emit(snapshot.copy(conflictedMeters = setOf("m")))
        runCurrent()
        assertEquals(ForecastIssue.CONFLICT, state.value!!.meters.getValue("m").forecast.issue)
        assertFalse(state.value!!.meters.getValue("m").low)
        first.cancel(); second.cancel()
        advanceTimeBy(5_001); runCurrent()
        val count = calculations
        advanceTimeBy(120_000); runCurrent()
        assertEquals(count, calculations)
        val returning = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
        runCurrent()
        assertEquals(count + 1, calculations)
        returning.cancel()
    }

    @Test fun thresholdsAreTakenFromTheSameSnapshotAsTheForecast() = runTest {
        val settings = snapshot.copy(settings = listOf(MeterReminderEntity("m", enabled = false)))
        val result = forecastStates(flowOf(settings), flowOf(now), StandardTestDispatcher(testScheduler)).first()
        assertFalse(result.meters.getValue("m").low)
        assertTrue(calculateForecastState(snapshot, now).meters.getValue("m").low)
    }

    @Test fun defaultCalculationLeavesCollectorThread() = runBlocking {
        val collector = Thread.currentThread()
        var worker: Thread? = null
        forecastStates(flowOf(snapshot), flowOf(now)) { input, time ->
            worker = Thread.currentThread()
            calculateForecastState(input, time)
        }.first()
        assertNotSame(collector, worker)
    }

    @Test fun cancelledNonCooperativeCalculationCannotPublishOverNewerInput() = runTest {
        val inputs = MutableStateFlow(snapshot)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = mutableListOf<ForecastUiState>()
        val delivered = CompletableDeferred<Unit>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            forecastStates(inputs, flowOf(now)) { input, time ->
                if (input.conflictedMeters.isEmpty()) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                calculateForecastState(input, time)
            }.collect { results += it; delivered.complete(Unit) }
        }
        try {
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            inputs.value = snapshot.copy(conflictedMeters = setOf("m"))
            runCurrent() // Cancel old work while its CPU calculation is still running.
            release.countDown()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { delivered.await() }
            }
            runCurrent()
            assertEquals(1, results.size)
            assertEquals(ForecastIssue.CONFLICT, results.single().meters.getValue("m").forecast.issue)
        } finally { release.countDown(); job.cancel() }
    }
}
