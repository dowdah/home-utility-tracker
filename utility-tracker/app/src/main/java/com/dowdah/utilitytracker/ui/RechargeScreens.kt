package com.dowdah.utilitytracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.*

@Composable
internal fun RechargeRecords(viewModel: AppViewModel, meters: List<MeterEntity>) {
    val rows by viewModel.recharges.collectAsState()
    var deleteTarget by remember { mutableStateOf<RechargeEntity?>(null) }
    Button(viewModel::openNewRecharge, modifier = Modifier.semantics { testTag = "add_recharge" }) { Text(stringResource(R.string.add_recharge)) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) item { Text(stringResource(R.string.no_recharges)) }
        items(rows.filter { viewModel.recordFilter == null || it.meterId == viewModel.recordFilter }.sortedByDescending { Instant.parse(it.creditedAt) }, key = { it.id }) { item ->
            var menu by remember { mutableStateOf(false) }
            ElevatedCard(Modifier.fillMaxWidth()) {
                ListItem(headlineContent = { Text(formatCny(item.amountDecimal.toBigDecimal())) },
                    overlineContent = { Text(meters.firstOrNull { it.id == item.meterId }?.let { meterLabel(it) }.orEmpty()) },
                    supportingContent = { Column {
                        Text(item.creditedAt.localDisplay())
                        Text(stringResource(R.string.credited_quantity, "${item.quantityDecimal.toBigDecimal().stripTrailingZeros().toPlainString()} ${meters.firstOrNull { it.id == item.meterId }?.unit.orEmpty()}"))
                        item.note?.let { Text(it) }
                    } }, trailingContent = {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.record_actions)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; viewModel.openRecharge(item) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleteTarget = item })
                        }
                    })
            }
        }
    }
    deleteTarget?.let { item -> ConfirmationDialog(stringResource(R.string.delete), stringResource(R.string.delete_recharge_message), { deleteTarget = null }) { viewModel.deleteRecharge(item); deleteTarget = null } }
}

@Composable
internal fun RechargeEditor(viewModel: AppViewModel, meters: List<MeterEntity>) {
    val draft = viewModel.rechargeDraft
    val selected = meters.firstOrNull { it.id == draft.meterId }
    val quantity = runCatching { rechargeQuantity(draft.amount, draft.price) }.getOrNull()
    AlertDialog(onDismissRequest = { if (!viewModel.formBusy) viewModel.closeRecharge() },
        title = { Text(stringResource(if (draft.id == null) R.string.add_recharge else R.string.edit)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MeterChooser(meters, draft.meterId) { viewModel.updateRecharge(draft.copy(meterId = it, remaining = "")) }
            OutlinedTextField(draft.amount, { viewModel.updateRecharge(draft.copy(amount = it)) }, label = { Text(stringResource(R.string.recharge_amount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.semantics { testTag = "recharge_amount" })
            OutlinedTextField(draft.price, { viewModel.updateRecharge(draft.copy(price = it, priceEdited = true)) }, label = { Text(stringResource(R.string.purchase_price)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.semantics { testTag = "recharge_price" })
            Text(stringResource(R.string.credited_quantity, quantity?.let { "${it.stripTrailingZeros().toPlainString()} ${selected?.unit.orEmpty()}" } ?: "—"))
            DateTimeField(draft.creditedAt) { viewModel.updateRecharge(draft.copy(creditedAt = it)) }
            if (selected?.meterType == "ELECTRICITY" && draft.id == null) {
                OutlinedTextField(draft.remaining, { viewModel.updateRecharge(draft.copy(remaining = it)) }, label = { Text(stringResource(R.string.post_recharge_reading)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.semantics { testTag = "recharge_remaining" })
            } else if (selected?.meterType != "ELECTRICITY") Text(stringResource(R.string.recharge_water_hint))
            OutlinedTextField(draft.note, { viewModel.updateRecharge(draft.copy(note = it)) }, label = { Text(stringResource(R.string.note)) }, modifier = Modifier.semantics { testTag = "recharge_note" })
            Text(stringResource(R.string.recharge_hint), style = MaterialTheme.typography.bodySmall)
            viewModel.message?.let { Text(localizedMessage(it) ?: it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(viewModel::persistRecharge, enabled = !viewModel.formBusy && selected != null && quantity != null && (draft.remaining.isBlank() || draft.remaining.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true), modifier = Modifier.semantics { testTag = "recharge_save" }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(viewModel::closeRecharge, enabled = !viewModel.formBusy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun PersistentSyncSummary(dashboard: DashboardData, conflictCount: Int, message: String?) {
    val state = dashboard.state
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.pending_changes, dashboard.pendingCount), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.last_sync, state?.lastSuccessAt?.let { Instant.ofEpochMilli(it).toString().localDisplay() } ?: stringResource(R.string.unknown)))
            if (conflictCount > 0) Text(stringResource(R.string.conflict_count, conflictCount), color = MaterialTheme.colorScheme.error)
            state?.lastError?.let { Text(localizedMessage(it) ?: it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(localizedMessage(it) ?: it) }
            val status = state?.serverStatusJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            status?.get("checked_at")?.jsonPrimitive?.content?.let { checked ->
                Text(stringResource(R.string.status_checked, checked.localDisplay()), style = MaterialTheme.typography.bodySmall)
                if (runCatching { Duration.between(Instant.parse(checked), Instant.now()).toMinutes() > 10 }.getOrDefault(true)) Text(stringResource(R.string.status_stale), color = MaterialTheme.colorScheme.error)
            }
            status?.get("alerts")?.jsonArray?.forEach { alert ->
                val label = when (alert.jsonPrimitive.content) {
                    "backup_overdue" -> R.string.backup_overdue
                    "backup_job_failed", "backup_checksum_failed", "backup_unverifiable", "backup_budget_exceeded" -> R.string.backup_failed
                    "disk_writes_disabled", "backup_low_space" -> R.string.server_disk_low
                    "service_unavailable", "tunnel_unavailable" -> R.string.server_unavailable
                    else -> R.string.monitor_unavailable
                }
                Text(stringResource(label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun HomeMonthlySummary(meter: MeterEntity, readings: List<ReadingEntity>, tariffs: List<TariffEntity>, recharges: List<RechargeEntity>) {
    val today = LocalDate.now()
    val start = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
    val summary = statisticsForRange(readings.filter { it.meterId == meter.id }, tariffs.filter { it.meterId == meter.id }, start, Instant.now().toString(), recharges.filter { it.meterId == meter.id })
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.monthly_summary), style = MaterialTheme.typography.labelLarge)
        SummaryValues(meter, summary)
    }
}

@Composable
private fun SummaryValues(meter: MeterEntity, summary: MeterStatistics) {
    Text("${stringResource(if (summary.hasIncrease) R.string.partial_consumption else R.string.consumption)}: ${summary.consumption?.stripTrailingZeros()?.toPlainString() ?: "—"} ${meter.unit}")
    Text("${stringResource(if (summary.costEstimated) R.string.estimated_cost else R.string.cost)}: ${summary.cost?.let(::formatCny) ?: "—"}")
    Text("${stringResource(R.string.recharge_spend)}: ${formatCny(summary.rechargeSpend)}")
}

private data class TrendPeriod(val label: String, val stats: MeterStatistics)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(viewModel: AppViewModel, onTariffs: () -> Unit) {
    val readings by viewModel.readings.collectAsState()
    val tariffs by viewModel.tariffs.collectAsState()
    val meters by viewModel.meters.collectAsState()
    val recharges by viewModel.recharges.collectAsState()
    val zone = ZoneId.systemDefault()
    val anchor = LocalDate.parse(viewModel.statisticsAnchor)
    val mode = viewModel.statisticsMode
    val startDate = if (mode == "year") anchor.withDayOfYear(1) else anchor.withDayOfMonth(1)
    val nextDate = if (mode == "year") startDate.plusYears(1) else startDate.plusMonths(1)
    val start = if (mode == "custom") viewModel.statisticsStart else startDate.atStartOfDay(zone).toInstant().toString()
    val end = if (mode == "custom") viewModel.statisticsEnd else nextDate.atStartOfDay(zone).toInstant().minusNanos(1).toString()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("month" to R.string.month_mode, "year" to R.string.year_mode, "custom" to R.string.custom_mode).forEach { (value, label) ->
                FilterChip(mode == value, { viewModel.statisticsMode = value; if (value == "custom") viewModel.rangePickerOpen = true }, label = { Text(stringResource(label)) })
            }
        }
        if (mode != "custom") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ viewModel.statisticsAnchor = (if (mode == "year") anchor.minusYears(1) else anchor.minusMonths(1)).toString() }) { Text(stringResource(R.string.previous_period)) }
                Text(if (mode == "year") anchor.year.toString() else anchor.format(DateTimeFormatter.ofPattern("yyyy MMM", Locale.getDefault())))
                TextButton({ viewModel.statisticsAnchor = (if (mode == "year") anchor.plusYears(1) else anchor.plusMonths(1)).toString() }) { Text(stringResource(R.string.next_period)) }
            }
        } else OutlinedButton({ viewModel.rangePickerOpen = true }) { Text(stringResource(R.string.range) + ": " + (start?.localDisplay() ?: stringResource(R.string.all_time)) + " – " + (end?.localDisplay() ?: stringResource(R.string.all_time))) }
        Text(stringResource(R.string.interval_attribution), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.recharge_hint), style = MaterialTheme.typography.bodySmall)
        meters.forEach { meter ->
            val rows = readings.filter { it.meterId == meter.id }
            val rates = tariffs.filter { it.meterId == meter.id }
            val credits = recharges.filter { it.meterId == meter.id }
            val summary = statisticsForRange(rows, rates, start, end, credits)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(meterLabel(meter), style = MaterialTheme.typography.titleMedium)
                    SummaryValues(meter, summary)
                    Text("${stringResource(R.string.credited_total)}: ${summary.creditedQuantity.stripTrailingZeros().toPlainString()} ${meter.unit}")
                    if (summary.consumption == null && !summary.hasIncrease) Text(stringResource(R.string.insufficient_readings))
                    if (summary.hasIncrease) Text(stringResource(R.string.incomplete_increase), color = MaterialTheme.colorScheme.error)
                    if (!summary.hasTariffs || summary.hasMissingTariff) {
                        Text(stringResource(if (!summary.hasTariffs) R.string.no_tariff else R.string.missing_period_tariff))
                        TextButton(onTariffs) { Text(stringResource(R.string.configure_tariffs)) }
                    }
                    if (summary.costEstimated) Text(stringResource(R.string.estimated_cost_hint))
                    val periods = if (mode == "year") (1..12).map { month ->
                        val date = LocalDate.of(anchor.year, month, 1)
                        TrendPeriod(date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())), statisticsForRange(rows, rates, date.atStartOfDay(zone).toInstant().toString(), date.plusMonths(1).atStartOfDay(zone).toInstant().minusNanos(1).toString(), credits))
                    } else rows.sortedBy { Instant.parse(it.recordedAt) }.zipWithNext().filter { (_, current) ->
                        val time = Instant.parse(current.recordedAt)
                        (start == null || time >= Instant.parse(start)) && (end == null || time <= Instant.parse(end))
                    }.map { (previous, current) -> TrendPeriod(previous.recordedAt.localDisplay() + " – " + current.recordedAt.localDisplay(), statisticsForRange(listOf(previous, current), rates, current.recordedAt, current.recordedAt, credits)) }
                    ConsumptionTrend(periods, meter.unit)
                }
            }
        }
    }
    if (viewModel.rangePickerOpen) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(onDismissRequest = { viewModel.rangePickerOpen = false }, confirmButton = {
            TextButton({
                picker.selectedStartDateMillis?.let { viewModel.statisticsStart = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toString() }
                picker.selectedEndDateMillis?.let { viewModel.statisticsEnd = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1).toString() }
                viewModel.statisticsMode = "custom"; viewModel.rangePickerOpen = false
            }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) { Text(stringResource(R.string.save)) }
        }, dismissButton = { TextButton({ viewModel.rangePickerOpen = false }) { Text(stringResource(R.string.cancel)) } }) { DateRangePicker(picker) }
    }
}

@Composable
private fun ConsumptionTrend(periods: List<TrendPeriod>, unit: String) {
    if (periods.isEmpty()) return
    Text(stringResource(R.string.trend), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.interval_trend), style = MaterialTheme.typography.bodySmall)
    val max = periods.mapNotNull { it.stats.consumption }.maxOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val color = MaterialTheme.colorScheme.primary
    // Float is used only for drawing geometry; all calculations above remain Decimal.
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val width = size.width / periods.size
        periods.forEachIndexed { index, point -> point.stats.consumption?.let {
            val height = it.divide(max, 8, RoundingMode.HALF_EVEN).toFloat() * size.height
            drawRect(color.copy(alpha = if (point.stats.hasIncrease) .45f else 1f), Offset(index * width + width * .15f, size.height - height), Size(width * .7f, height))
        } }
    }
    periods.forEach { period ->
        Text("${period.label}: ${period.stats.consumption?.stripTrailingZeros()?.toPlainString() ?: "—"} $unit" + if (period.stats.hasIncrease) " · " + stringResource(R.string.partial_consumption) else "", style = MaterialTheme.typography.bodySmall)
    }
}
