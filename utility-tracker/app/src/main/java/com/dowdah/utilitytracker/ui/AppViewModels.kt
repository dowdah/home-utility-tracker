package com.dowdah.utilitytracker.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dowdah.utilitytracker.data.BackendRepository
import com.dowdah.utilitytracker.data.DashboardData
import com.dowdah.utilitytracker.data.EndpointEntity
import com.dowdah.utilitytracker.data.SyncResult
import com.dowdah.utilitytracker.sync.SyncScheduler
import java.time.Instant
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: BackendRepository,
    private val scheduler: SyncScheduler,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val dashboard = repository.dashboard().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardData(emptyList(), emptyList(), null, 0))
    val readings = repository.readings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val meters = repository.meters.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tariffs = repository.tariffs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val conflicts = repository.conflicts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val messageState = mutableStateOf<String?>(savedState["message"])
    var message: String?
        get() = messageState.value
        private set(value) { messageState.value = value; savedState["message"] = value }

    init {
        // A force-stop can cancel a constrained pending job. Re-enqueueing is idempotent
        // and makes persisted outbox work recover when the app is opened again.
        scheduler.enqueue()
    }

    private val recordFilterState = mutableStateOf<String?>(savedState["recordFilter"])
    var recordFilter: String?
        get() = recordFilterState.value
        set(value) { recordFilterState.value = value; savedState["recordFilter"] = value }

    private val readingEditorOpenState = mutableStateOf(savedState["readingEditorOpen"] ?: false)
    private val readingEditorIdState = mutableStateOf<String?>(savedState["readingEditorId"])
    private val readingMeterIdState = mutableStateOf(savedState["readingMeterId"] ?: "")
    private val readingValueState = mutableStateOf(savedState["readingValue"] ?: "")
    private val readingRecordedAtState = mutableStateOf(savedState["readingRecordedAt"] ?: Instant.now().toString())
    private val readingNoteState = mutableStateOf(savedState["readingNote"] ?: "")
    var readingEditorOpen: Boolean
        get() = readingEditorOpenState.value
        private set(value) { readingEditorOpenState.value = value; savedState["readingEditorOpen"] = value }
    var readingEditorId: String?
        get() = readingEditorIdState.value
        private set(value) { readingEditorIdState.value = value; savedState["readingEditorId"] = value }
    var readingMeterId: String
        get() = readingMeterIdState.value
        set(value) { readingMeterIdState.value = value; savedState["readingMeterId"] = value }
    var readingValue: String
        get() = readingValueState.value
        set(value) { readingValueState.value = value; savedState["readingValue"] = value }
    var readingRecordedAt: String
        get() = readingRecordedAtState.value
        set(value) { readingRecordedAtState.value = value; savedState["readingRecordedAt"] = value }
    var readingNote: String
        get() = readingNoteState.value
        set(value) { readingNoteState.value = value; savedState["readingNote"] = value }
    fun openNewReading(defaultMeterId: String) { readingEditorId = null; readingMeterId = defaultMeterId; readingValue = ""; readingRecordedAt = Instant.now().toString(); readingNote = ""; readingEditorOpen = true }
    fun openReading(reading: com.dowdah.utilitytracker.data.ReadingEntity) { readingEditorId = reading.id; readingMeterId = reading.meterId; readingValue = reading.valueDecimal; readingRecordedAt = reading.recordedAt; readingNote = reading.note.orEmpty(); readingEditorOpen = true }
    fun closeReadingEditor() { readingEditorOpen = false; readingEditorId = null }
    fun persistReadingDraft() = saveReading(readingEditorId, readingMeterId, readingValue, readingRecordedAt, readingNote.ifBlank { null }).also { closeReadingEditor() }

    private val tariffEditorOpenState = mutableStateOf(savedState["tariffEditorOpen"] ?: false)
    private val tariffEditorIdState = mutableStateOf<String?>(savedState["tariffEditorId"])
    private val tariffMeterIdState = mutableStateOf(savedState["tariffMeterId"] ?: "")
    private val tariffPriceState = mutableStateOf(savedState["tariffPrice"] ?: "")
    private val tariffEffectiveFromState = mutableStateOf(savedState["tariffEffectiveFrom"] ?: Instant.now().toString())
    var tariffEditorOpen: Boolean
        get() = tariffEditorOpenState.value
        private set(value) { tariffEditorOpenState.value = value; savedState["tariffEditorOpen"] = value }
    var tariffEditorId: String?
        get() = tariffEditorIdState.value
        private set(value) { tariffEditorIdState.value = value; savedState["tariffEditorId"] = value }
    var tariffMeterId: String
        get() = tariffMeterIdState.value
        set(value) { tariffMeterIdState.value = value; savedState["tariffMeterId"] = value }
    var tariffPrice: String
        get() = tariffPriceState.value
        set(value) { tariffPriceState.value = value; savedState["tariffPrice"] = value }
    var tariffEffectiveFrom: String
        get() = tariffEffectiveFromState.value
        set(value) { tariffEffectiveFromState.value = value; savedState["tariffEffectiveFrom"] = value }
    fun openNewTariff(defaultMeterId: String) { tariffEditorId = null; tariffMeterId = defaultMeterId; tariffPrice = ""; tariffEffectiveFrom = Instant.now().toString(); tariffEditorOpen = true }
    fun openTariff(tariff: com.dowdah.utilitytracker.data.TariffEntity) { tariffEditorId = tariff.id; tariffMeterId = tariff.meterId; tariffPrice = tariff.priceDecimal; tariffEffectiveFrom = tariff.effectiveFrom; tariffEditorOpen = true }
    fun closeTariffEditor() { tariffEditorOpen = false; tariffEditorId = null }
    fun persistTariffDraft() = saveTariff(tariffEditorId, tariffMeterId, tariffPrice, tariffEffectiveFrom).also { closeTariffEditor() }

    private val rangePickerOpenState = mutableStateOf(savedState["rangePickerOpen"] ?: false)
    private val statisticsStartState = mutableStateOf<String?>(savedState["statisticsStart"])
    private val statisticsEndState = mutableStateOf<String?>(savedState["statisticsEnd"])
    var rangePickerOpen: Boolean
        get() = rangePickerOpenState.value
        set(value) { rangePickerOpenState.value = value; savedState["rangePickerOpen"] = value }
    var statisticsStart: String?
        get() = statisticsStartState.value
        set(value) { statisticsStartState.value = value; savedState["statisticsStart"] = value }
    var statisticsEnd: String?
        get() = statisticsEndState.value
        set(value) { statisticsEndState.value = value; savedState["statisticsEnd"] = value }

    fun saveReading(id: String? = null, meterId: String, value: String, recordedAt: String, note: String?) = viewModelScope.launch {
        runCatching { repository.saveReading(id, meterId, value, recordedAt, note) }
            .onSuccess { scheduler.enqueue(); message = "Saved locally" }.onFailure { message = it.message }
    }
    fun deleteReading(reading: com.dowdah.utilitytracker.data.ReadingEntity) = viewModelScope.launch { runCatching { repository.deleteReading(reading) }.onSuccess { scheduler.enqueue() }.onFailure { message = it.message } }
    fun saveTariff(id: String? = null, meterId: String, price: String, effectiveFrom: String) = viewModelScope.launch { runCatching { repository.saveTariff(id, meterId, price, effectiveFrom) }.onSuccess { scheduler.enqueue() }.onFailure { message = it.message } }
    fun deleteTariff(tariff: com.dowdah.utilitytracker.data.TariffEntity) = viewModelScope.launch { runCatching { repository.deleteTariff(tariff) }.onSuccess { scheduler.enqueue() }.onFailure { message = it.message } }
    fun keepServer(conflict: com.dowdah.utilitytracker.data.ConflictEntity) = viewModelScope.launch { runCatching { repository.keepServerConflict(conflict) }.onFailure { message = it.message } }
    fun overrideServer(conflict: com.dowdah.utilitytracker.data.ConflictEntity) = viewModelScope.launch { runCatching { repository.overrideConflict(conflict) }.onSuccess { scheduler.enqueue() }.onFailure { message = it.message } }
    fun exportCsv(context: android.content.Context, uri: android.net.Uri) = viewModelScope.launch {
        runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).use { repository.exportCsv(it) } }
            .onSuccess { message = "Export complete" }.onFailure { message = it.message }
    }
    fun sync(onComplete: (() -> Unit)? = null) = viewModelScope.launch {
        message = when (val result = repository.sync()) {
            SyncResult.Success -> "Sync complete"
            SyncResult.ConflictDetected -> "Conflict detected. Open Settings to resolve it."
            is SyncResult.Retryable -> "Sync queued to retry: ${result.detail}"
            is SyncResult.ActionRequired -> result.detail
        }
        onComplete?.invoke()
    }
    fun clearMessage() { message = null }
}

@HiltViewModel
class EndpointViewModel @Inject constructor(
    private val repository: BackendRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val endpoints = repository.endpoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val editorIdState = mutableStateOf<String?>(savedState["editorId"])
    private val showEditorState = mutableStateOf(savedState["showEditor"] ?: false)
    private val editorLabelState = mutableStateOf(savedState["editorLabel"] ?: "")
    private val editorUrlState = mutableStateOf(savedState["editorUrl"] ?: "")
    private val errorState = mutableStateOf<String?>(savedState["endpointError"])
    private val noticeState = mutableStateOf<String?>(savedState["endpointNotice"])
    private val busyState = mutableStateOf(false)
    private val showTokenEditorState = mutableStateOf(savedState["showTokenEditor"] ?: false)
    var editorId: String?
        get() = editorIdState.value
        private set(value) { editorIdState.value = value; savedState["editorId"] = value }
    var showEditor: Boolean
        get() = showEditorState.value
        private set(value) { showEditorState.value = value; savedState["showEditor"] = value }
    var editorLabel: String
        get() = editorLabelState.value
        set(value) { editorLabelState.value = value; savedState["editorLabel"] = value }
    var editorUrl: String
        get() = editorUrlState.value
        set(value) { editorUrlState.value = value; savedState["editorUrl"] = value }
    var error: String?
        get() = errorState.value
        private set(value) { errorState.value = value; savedState["endpointError"] = value }
    var notice: String?
        get() = noticeState.value
        private set(value) { noticeState.value = value; savedState["endpointNotice"] = value }
    var busy: Boolean
        get() = busyState.value
        private set(value) { busyState.value = value }
    var showTokenEditor: Boolean
        get() = showTokenEditorState.value
        set(value) { showTokenEditorState.value = value; savedState["showTokenEditor"] = value }
    fun openNew() { editorId = null; editorLabel = ""; editorUrl = ""; error = null; notice = null; showEditor = true }
    fun openEdit(endpoint: EndpointEntity) { editorId = endpoint.id; editorLabel = endpoint.label; editorUrl = endpoint.baseUrl; error = null; notice = null; showEditor = true }
    fun closeEditor() { editorId = null; error = null; showEditor = false }
    fun save() = viewModelScope.launch { busy = true; runCatching { repository.saveEndpoint(editorId, editorLabel, editorUrl) }
        .onSuccess { closeEditor(); notice = "Endpoint saved. Add or update a token, then activate it." }.onFailure { error = it.message }.also { busy = false } }
    fun refresh() = viewModelScope.launch { busy = true; error = null; runCatching { repository.refreshHealth() }.onSuccess { notice = "Health updated" }.onFailure { error = it.message }.also { busy = false } }
    fun enable(id: String) = viewModelScope.launch { busy = true; error = null; notice = null; runCatching { repository.enableEndpoint(id) }.onSuccess { notice = "Endpoint active" }.onFailure { error = it.message }.also { busy = false } }
    fun delete(id: String) = viewModelScope.launch { repository.deleteEndpoint(id) }
    fun saveToken(token: String) { repository.saveToken(token); showTokenEditor = false; notice = "Token saved. Select an endpoint to verify it." }
    fun clearToken() { repository.clearToken(); error = null }
}
