package com.dowdah.utilitytracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.hilt.navigation.compose.hiltViewModel
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.ConflictEntity
import com.dowdah.utilitytracker.data.EndpointEntity
import com.dowdah.utilitytracker.data.MeterEntity
import com.dowdah.utilitytracker.data.ReadingEntity
import com.dowdah.utilitytracker.data.TariffEntity
import com.dowdah.utilitytracker.data.statisticsForRange
import com.dowdah.utilitytracker.data.remainingReadingIncreases
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(viewModel: AppViewModel) {
    val dashboard by viewModel.dashboard.collectAsState()
    val tariffs by viewModel.tariffs.collectAsState()
    val recharges by viewModel.recharges.collectAsState()
    val conflicts by viewModel.conflicts.collectAsState()
    val columns = integerResource(R.integer.dashboard_columns)
    var refreshing by remember { mutableStateOf(false) }
    val latest = dashboard.readings.groupBy { it.meterId }.mapValues { (_, rows) -> rows.maxByOrNull { Instant.parse(it.recordedAt) } }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; viewModel.sync { refreshing = false } }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 12.dp).verticalScroll(rememberScrollState())) {
            PersistentSyncSummary(dashboard, conflicts.size, viewModel.message)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                dashboard.meters.forEach { meter ->
                    Column(Modifier.fillMaxWidth(if (columns > 1) .31f else 1f)) {
                        MeterCard(meter, latest[meter.id], 1)
                        HomeMonthlySummary(meter, dashboard.readings, tariffs, recharges)
                    }
                }
            }
        }
    }
}

@Composable private fun SyncSummary(pending: Int, message: String?) = ElevatedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(R.string.pending_changes, pending), style = MaterialTheme.typography.titleMedium)
        Text(localizedMessage(message) ?: stringResource(R.string.pull_to_sync), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun MeterCard(meter: MeterEntity, reading: ReadingEntity?, columns: Int) = ElevatedCard(Modifier.fillMaxWidth(if (columns > 1) .31f else 1f)) {
    Column(Modifier.padding(16.dp)) {
        Text(meterLabel(meter), style = MaterialTheme.typography.titleMedium)
        Text(reading?.let { "${it.valueDecimal} ${meter.unit}" } ?: "—", style = MaterialTheme.typography.headlineSmall)
        Text(reading?.recordedAt?.localDisplay() ?: stringResource(R.string.no_reading))
        reading?.let { Text(stringResource(R.string.days_since_reading, java.time.Duration.between(Instant.parse(it.recordedAt), Instant.now()).toDays().coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordsScreen(viewModel: AppViewModel) {
    val readings by viewModel.readings.collectAsState()
    val meters by viewModel.meters.collectAsState()
    var deleteTarget by remember { mutableStateOf<ReadingEntity?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; viewModel.sync { refreshing = false } }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = viewModel.recordFilter == null, onClick = { viewModel.recordFilter = null }, label = { Text(stringResource(R.string.all)) })
                meters.forEach { meter -> FilterChip(selected = viewModel.recordFilter == meter.id, onClick = { viewModel.recordFilter = meter.id }, label = { Text(meterLabel(meter)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!viewModel.rechargeTab, { viewModel.rechargeTab = false }, label = { Text(stringResource(R.string.readings_tab)) })
                FilterChip(viewModel.rechargeTab, { viewModel.rechargeTab = true }, label = { Text(stringResource(R.string.recharges_tab)) })
            }
            if (viewModel.rechargeTab) RechargeRecords(viewModel, meters)
            else {
                Button(onClick = { viewModel.openNewReading() }) { Icon(Icons.Default.Add, stringResource(R.string.add_reading)); Text(stringResource(R.string.add_reading)) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (readings.isEmpty()) item { Text(stringResource(R.string.no_records)) }
                    items(readings.filter { viewModel.recordFilter == null || it.meterId == viewModel.recordFilter }.sortedByDescending { Instant.parse(it.recordedAt) }, key = { it.id }) { reading ->
                        ReadingCard(reading, meters.firstOrNull { it.id == reading.meterId }, onEdit = { viewModel.openReading(reading) }, onDelete = { deleteTarget = reading })
                    }
                }
            }
        }
    }
    if (viewModel.rechargeDraft.open) RechargeEditor(viewModel, meters)
    if (viewModel.readingEditorOpen) ReadingEditor(viewModel, meters, readings)
    deleteTarget?.let { reading -> ConfirmationDialog(stringResource(R.string.delete_reading), stringResource(R.string.delete_reading_message), { deleteTarget = null }) { viewModel.deleteReading(reading); deleteTarget = null } }
}

@Composable private fun ReadingCard(reading: ReadingEntity, meter: MeterEntity?, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) { ListItem(
        headlineContent = { Text("${reading.valueDecimal} ${meter?.unit.orEmpty()}") },
        supportingContent = { Column { Text(reading.recordedAt.localDisplay()); reading.note?.let { Text(it) } } },
        overlineContent = { Text(meter?.let { meterLabel(it) } ?: reading.meterId) },
        trailingContent = { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.record_actions)) }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Default.Edit, null) }); DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) }) } },
    ) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadingEditor(viewModel: AppViewModel, meters: List<MeterEntity>, existing: List<ReadingEntity>) {
    val recharges by viewModel.recharges.collectAsState()
    var confirmIncrease by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = viewModel::closeReadingEditor, title = { Text(if (viewModel.readingEditorId == null) stringResource(R.string.add_reading) else stringResource(R.string.edit_reading)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MeterChooser(meters, viewModel.readingMeterId) { viewModel.readingMeterId = it }
        if (meters.none { it.id == viewModel.readingMeterId }) Text(stringResource(R.string.reading_meter_required), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(viewModel.readingValue, { viewModel.readingValue = it }, label = { Text(stringResource(R.string.reading_value)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        DateTimeField(viewModel.readingRecordedAt) { viewModel.readingRecordedAt = it }
        viewModel.message?.let { Text(localizedMessage(it) ?: it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(viewModel.readingNote, { viewModel.readingNote = it }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.fillMaxWidth())
    } }, confirmButton = { Button(enabled = !viewModel.formBusy && meters.any { it.id == viewModel.readingMeterId } && (viewModel.readingValue.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true), onClick = {
        if (remainingReadingIncreases(existing, viewModel.readingEditorId, viewModel.readingMeterId, viewModel.readingValue, viewModel.readingRecordedAt, recharges)) confirmIncrease = true else viewModel.persistReadingDraft()
    }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = viewModel::closeReadingEditor) { Text(stringResource(R.string.cancel)) } })
    if (confirmIncrease) ConfirmationDialog(stringResource(R.string.remaining_increased), stringResource(R.string.increase_confirmation), { confirmIncrease = false }) { confirmIncrease = false; viewModel.persistReadingDraft() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MeterChooser(meters: List<MeterEntity>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = meters.firstOrNull { it.id == selected }?.let { meterLabel(it) } ?: stringResource(R.string.select_meter)
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        OutlinedTextField(label, {}, readOnly = true, label = { Text(stringResource(R.string.meter)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) { meters.forEach { meter -> DropdownMenuItem(text = { Text(meterLabel(meter)) }, onClick = { onSelect(meter.id); expanded = false }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DateTimeField(instantText: String, onChanged: (String) -> Unit) {
    var showDate by rememberSaveable { mutableStateOf(false) }; var showTime by rememberSaveable { mutableStateOf(false) }
    val local = remember(instantText) { runCatching { LocalDateTime.ofInstant(Instant.parse(instantText), ZoneId.systemDefault()) }.getOrElse { LocalDateTime.now() } }
    OutlinedTextField(local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())), {}, readOnly = true, label = { Text(stringResource(R.string.recorded_at)) }, modifier = Modifier.fillMaxWidth().padding(bottom = 0.dp), trailingIcon = { TextButton(onClick = { showDate = true }) { Text(stringResource(R.string.change)) } })
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = { TextButton(onClick = { val picked = state.selectedDateMillis ?: return@TextButton; val changed = LocalDateTime.ofInstant(Instant.ofEpochMilli(picked), java.time.ZoneOffset.UTC).withHour(local.hour).withMinute(local.minute); onChanged(changed.atZone(ZoneId.systemDefault()).toInstant().toString()); showDate = false; showTime = true }) { Text(stringResource(R.string.next)) } }) { DatePicker(state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(local.hour, local.minute, true)
        AlertDialog(onDismissRequest = { showTime = false }, confirmButton = { TextButton(onClick = { val changed = local.withHour(state.hour).withMinute(state.minute); onChanged(changed.atZone(ZoneId.systemDefault()).toInstant().toString()); showTime = false }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.cancel)) } }, text = { TimePicker(state) })
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel, onEndpoints: () -> Unit, onTariffs: () -> Unit, onConflicts: () -> Unit, onExport: () -> Unit) {
    val conflicts by viewModel.conflicts.collectAsState()
    LazyColumn(Modifier.padding(vertical = 12.dp)) {
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column { SettingsRow(stringResource(R.string.backend_urls), onEndpoints); HorizontalDivider(); SettingsRow(stringResource(R.string.tariff_history), onTariffs); HorizontalDivider(); SettingsRow("${stringResource(R.string.conflicts)} (${conflicts.size})", onConflicts); HorizontalDivider(); SettingsRow(stringResource(R.string.export_csv), onExport) } } }
    }
}
@Composable private fun SettingsRow(label: String, action: () -> Unit) = ListItem(headlineContent = { Text(label) }, modifier = Modifier.fillMaxWidth().clickable(onClick = action), trailingContent = { Text("›") })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndpointScreen(onBack: () -> Unit, viewModel: EndpointViewModel = hiltViewModel()) {
    val endpoints by viewModel.endpoints.collectAsState(); var deleteTarget by remember { mutableStateOf<EndpointEntity?>(null) }; var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.configuration)) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, actions = { IconButton(viewModel::refresh, enabled = !viewModel.busy, modifier = Modifier.semantics { testTag = "endpoint_refresh" }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }; IconButton(viewModel::openNew, enabled = !viewModel.busy, modifier = Modifier.semantics { testTag = "endpoint_add" }) { Icon(Icons.Default.Add, stringResource(R.string.add_url)) }; IconButton({ menu = true }, modifier = Modifier.semantics { testTag = "endpoint_more" }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.update_token)) }, onClick = { menu = false; viewModel.showTokenEditor = true }, modifier = Modifier.semantics { testTag = "token_update" }); DropdownMenuItem(text = { Text(stringResource(R.string.clear_token)) }, onClick = { menu = false; viewModel.clearToken() }) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { viewModel.error?.let { Text(localizedMessage(it) ?: it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { testTag = "endpoint_error" }) }; viewModel.notice?.let { Text(localizedMessage(it) ?: it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { testTag = "endpoint_notice" }) }; if (endpoints.isEmpty()) Text(stringResource(R.string.endpoint_setup_hint)) }
            items(endpoints, key = { it.id }) { endpoint -> EndpointCard(endpoint, !viewModel.busy, { viewModel.enable(endpoint.id) }, { viewModel.openEdit(endpoint) }, { deleteTarget = endpoint }) }
        }
    }
    if (viewModel.showEditor) EndpointEditor(viewModel); if (viewModel.showTokenEditor) TokenEditor(viewModel)
    deleteTarget?.let { endpoint -> ConfirmationDialog(stringResource(R.string.delete), stringResource(R.string.delete_endpoint_message), { deleteTarget = null }) { viewModel.delete(endpoint.id); deleteTarget = null } }
}

@Composable private fun EndpointCard(endpoint: EndpointEntity, enabled: Boolean, onActivate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) { var menu by remember { mutableStateOf(false) }; ElevatedCard(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onActivate).semantics { testTag = "endpoint_${endpoint.id}" }) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { RadioButton(endpoint.active, onActivate, enabled = enabled, modifier = Modifier.semantics { testTag = "endpoint_activate_${endpoint.id}" }); Column(Modifier.weight(1f)) { Text(endpoint.label, style = MaterialTheme.typography.titleMedium); Text(endpoint.baseUrl); Text("${endpointHealthLabel(endpoint.healthStatus)} · ${endpoint.healthLatencyMs?.let { "${it}ms" }.orEmpty()}", style = MaterialTheme.typography.bodySmall); if (endpoint.baseUrl.startsWith("http://")) Text(stringResource(R.string.http_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }; IconButton({ menu = true }, modifier = Modifier.semantics { testTag = "endpoint_menu_${endpoint.id}" }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() }); DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() }) } } } }
@Composable private fun EndpointEditor(viewModel: EndpointViewModel) = AlertDialog(onDismissRequest = viewModel::closeEditor, title = { Text(if (viewModel.editorId == null) stringResource(R.string.add_url) else stringResource(R.string.edit_url)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(viewModel.editorLabel, { viewModel.editorLabel = it }, label = { Text(stringResource(R.string.label)) }); OutlinedTextField(viewModel.editorUrl, { viewModel.editorUrl = it }, label = { Text(stringResource(R.string.base_url)) }); viewModel.error?.let { Text(localizedMessage(it) ?: it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(viewModel::save) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(viewModel::closeEditor) { Text(stringResource(R.string.cancel)) } })
@Composable private fun TokenEditor(viewModel: EndpointViewModel) { var token by rememberSaveable { mutableStateOf("") }; var visible by rememberSaveable { mutableStateOf(false) }; AlertDialog(onDismissRequest = { viewModel.showTokenEditor = false }, title = { Text(stringResource(R.string.token)) }, text = { OutlinedTextField(token, { token = it }, label = { Text(stringResource(R.string.token)) }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton({ visible = !visible }) { Text(stringResource(if (visible) R.string.hide else R.string.show)) } }, modifier = Modifier.semantics { testTag = "token_input" }) }, confirmButton = { Button({ viewModel.saveToken(token) }, enabled = token.isNotBlank()) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton({ viewModel.showTokenEditor = false }) { Text(stringResource(R.string.cancel)) } }) }

@Composable fun TariffScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val tariffs by viewModel.tariffs.collectAsState(); val meters by viewModel.meters.collectAsState()
    var deleteTarget by remember { mutableStateOf<TariffEntity?>(null) }
    Scaffold(topBar = { SimpleBackBar(stringResource(R.string.tariff_history), onBack, { viewModel.openNewTariff(meters.firstOrNull()?.id.orEmpty()) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(tariffs, key = { it.id }) { tariff -> TariffCard(tariff, meters.firstOrNull { it.id == tariff.meterId }, { viewModel.openTariff(tariff) }, { deleteTarget = tariff }) } }
    }
    if (viewModel.tariffEditorOpen) TariffEditor(viewModel, meters)
    deleteTarget?.let { tariff -> ConfirmationDialog(stringResource(R.string.delete), stringResource(R.string.delete_tariff_message), { deleteTarget = null }) { viewModel.deleteTariff(tariff); deleteTarget = null } }
}
@Composable private fun TariffCard(tariff: TariffEntity, meter: MeterEntity?, onEdit: () -> Unit, onDelete: () -> Unit) { var menu by remember { mutableStateOf(false) }; ElevatedCard(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(formatCny(tariff.priceDecimal.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)) }, supportingContent = { Text(tariff.effectiveFrom.localDisplay()) }, overlineContent = { Text(meter?.let { meterLabel(it) }.orEmpty()) }, trailingContent = { IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() }); DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() }) } }) } }
@Composable private fun TariffEditor(viewModel: AppViewModel, meters: List<MeterEntity>) = AlertDialog(onDismissRequest = viewModel::closeTariffEditor, title = { Text(if (viewModel.tariffEditorId == null) stringResource(R.string.add_tariff) else stringResource(R.string.edit)) }, text = { Column { MeterChooser(meters, viewModel.tariffMeterId) { viewModel.tariffMeterId = it }; OutlinedTextField(viewModel.tariffPrice, { viewModel.tariffPrice = it }, label = { Text(stringResource(R.string.price)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)); DateTimeField(viewModel.tariffEffectiveFrom) { viewModel.tariffEffectiveFrom = it } } }, confirmButton = { Button(viewModel::persistTariffDraft, enabled = !viewModel.formBusy && meters.any { it.id == viewModel.tariffMeterId } && viewModel.tariffPrice.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(viewModel::closeTariffEditor) { Text(stringResource(R.string.cancel)) } })

@Composable fun ConflictScreen(viewModel: AppViewModel, onBack: () -> Unit) { val conflicts by viewModel.conflicts.collectAsState(); Scaffold(topBar = { SimpleBackBar(stringResource(R.string.conflicts), onBack) }) { padding -> LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(conflicts, key = { it.operationId }) { conflict -> ConflictCard(conflict, { viewModel.keepServer(conflict) }, { viewModel.overrideServer(conflict) }) } } } }
@Composable private fun ConflictCard(conflict: ConflictEntity, keep: () -> Unit, override: () -> Unit) {
    val json = remember(conflict) { Json { ignoreUnknownKeys = true; explicitNulls = false } }
    val local = remember(conflict.localPayloadJson) { runCatching { json.parseToJsonElement(conflict.localPayloadJson).jsonObject }.getOrNull() }
    val server = remember(conflict.serverEntityJson) { conflict.serverEntityJson?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } }
    fun kotlinx.serialization.json.JsonObject?.value(): String? = this?.get("value_decimal")?.jsonPrimitive?.content
        ?: this?.get("price_decimal")?.jsonPrimitive?.content ?: this?.get("amount_decimal")?.jsonPrimitive?.content
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (conflict.entityType) { "reading" -> stringResource(R.string.reading_value); "recharge" -> stringResource(R.string.recharges_tab); else -> stringResource(R.string.price) }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.server_revision, server?.get("server_revision")?.jsonPrimitive?.content ?: stringResource(R.string.unknown)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.local_draft, local.value() ?: if (conflict.localPayloadJson == "{}") stringResource(R.string.delete) else stringResource(R.string.unavailable_value)))
            Text(stringResource(R.string.server_value, server.value() ?: if (server?.get("deleted")?.jsonPrimitive?.content == "true") stringResource(R.string.deleted) else stringResource(R.string.unavailable_value)))
            if (conflict.groupId != null) Text(stringResource(R.string.linked_conflict))
            Text(stringResource(R.string.conflict_description), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(keep) { Text(stringResource(R.string.keep_server)) }; Button(override) { Text(stringResource(R.string.override_server)) } }
        }
    }
}

@Composable fun ExportScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf("readings") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { viewModel.exportCsv(context, it, kind) } }
    Scaffold(topBar = { SimpleBackBar(stringResource(R.string.export_csv), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.export_description))
            listOf("readings" to R.string.readings_tab, "recharges" to R.string.recharges_tab, "tariffs" to R.string.tariff_history).forEach { (value, label) ->
                FilterChip(kind == value, { kind = value }, label = { Text(stringResource(label)) })
            }
            Button({ launcher.launch("$kind.csv") }, modifier = Modifier.semantics { testTag = "export_save" }) { Text(stringResource(R.string.export_csv)) }
            viewModel.message?.let { Text(localizedMessage(it) ?: it) }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SimpleBackBar(title: String, onBack: () -> Unit, onAdd: (() -> Unit)? = null) = CenterAlignedTopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, actions = { onAdd?.let { IconButton(it) { Icon(Icons.Default.Add, stringResource(R.string.add)) } } })
@Composable internal fun ConfirmationDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { Button({ onConfirm(); onDismiss() }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
@Composable internal fun meterLabel(meter: MeterEntity): String = when (meter.meterType.lowercase(Locale.ROOT)) {
    "cold_water" -> stringResource(R.string.cold_water)
    "hot_water" -> stringResource(R.string.hot_water)
    "electricity" -> stringResource(R.string.electricity)
    else -> meter.meterType.replace('_', ' ')
}

@Composable private fun endpointHealthLabel(status: String): String = when (status) {
    "HEALTHY" -> stringResource(R.string.healthy)
    "UNAVAILABLE" -> stringResource(R.string.unavailable)
    else -> status
}

@Composable internal fun localizedMessage(message: String?): String? = message?.let { value ->
    when {
        value == "Server or app upgrade required" -> stringResource(R.string.server_upgrade_required)
        value == "Enter a positive amount and purchase price" -> stringResource(R.string.positive_purchase)
        value == "Number exceeds supported precision" -> stringResource(R.string.precision_error)
        value == "Token is invalid or revoked" -> stringResource(R.string.auth_error)
        value == "Backend identity or revision changed" || value == "Active endpoint identity changed" -> stringResource(R.string.identity_error)
        value == "A pending change is invalid" -> stringResource(R.string.invalid_pending)
        value == "Server writes are temporarily disabled" -> stringResource(R.string.server_disk_low)
        value == "Conflict needs resolution" -> stringResource(R.string.conflict_detected)
        value == "Note must be at most 1000 characters" -> stringResource(R.string.note_too_long)
        value == "Saved locally" -> stringResource(R.string.saved_locally)
        value == "Export complete" -> stringResource(R.string.export_complete)
        value == "Sync complete" -> stringResource(R.string.sync_complete)
        value == "Conflict detected. Open Settings to resolve it." -> stringResource(R.string.conflict_detected)
        value == "Endpoint active" -> stringResource(R.string.endpoint_active)
        value == "Health updated" -> stringResource(R.string.health_updated)
        value == "Endpoint saved. Add or update a token, then activate it." -> stringResource(R.string.endpoint_saved)
        value == "Token saved. Select an endpoint to verify it." -> stringResource(R.string.token_saved)
        value == "No active endpoint" -> stringResource(R.string.no_active_endpoint)
        value == "No token configured" -> stringResource(R.string.no_token_configured)
        value.startsWith("Network connection failed:") -> stringResource(R.string.network_retry_detail)
        value.startsWith("Sync queued to retry:") -> stringResource(R.string.sync_retry, stringResource(R.string.network_retry_detail))
        else -> value
    }
}

internal fun formatCny(value: java.math.BigDecimal): String = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
    currency = Currency.getInstance("CNY")
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}.format(value)

internal fun String.localDisplay(): String = runCatching {
    LocalDateTime.ofInstant(Instant.parse(this), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
}.getOrDefault(this)
