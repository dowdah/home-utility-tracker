package com.dowdah.utilitytracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dowdah.utilitytracker.data.ForecastRepository
import com.dowdah.utilitytracker.data.ForecastSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ForecastViewModel @Inject constructor(repository: ForecastRepository) : ViewModel() {
    val state = combine(repository.snapshots, flow {
        while (true) { emit(Instant.now()); delay(60_000) }
    }) { snapshot, now -> snapshot to now }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
