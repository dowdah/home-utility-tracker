package com.dowdah.utilitytracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dowdah.utilitytracker.data.BalanceForecast
import com.dowdah.utilitytracker.data.ForecastRepository
import com.dowdah.utilitytracker.data.ForecastSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class MeterForecastState(val forecast: BalanceForecast, val low: Boolean)
data class ForecastUiState(val meters: Map<String, MeterForecastState>)

internal fun calculateForecastState(snapshot: ForecastSnapshot, now: Instant) = ForecastUiState(
    snapshot.forecasts(now).associate { it.meter.id to MeterForecastState(it, it.isLow(snapshot.setting(it.meter.id))) },
)

internal fun forecastTicks(now: () -> Instant = Instant::now): Flow<Instant> = flow {
    while (true) { emit(now()); delay(60_000) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun forecastStates(
    snapshots: Flow<ForecastSnapshot>,
    ticks: Flow<Instant>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    calculate: (ForecastSnapshot, Instant) -> ForecastUiState = ::calculateForecastState,
): Flow<ForecastUiState> = combine(snapshots.distinctUntilChanged(), ticks) { snapshot, now -> snapshot to now }
    .mapLatest { (snapshot, now) ->
        // withContext also discards a cancelled CPU calculation before it can publish.
        withContext(dispatcher) { calculate(snapshot, now) }
    }

@HiltViewModel
class ForecastViewModel @Inject constructor(repository: ForecastRepository) : ViewModel() {
    val state = forecastStates(repository.snapshots, forecastTicks())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
