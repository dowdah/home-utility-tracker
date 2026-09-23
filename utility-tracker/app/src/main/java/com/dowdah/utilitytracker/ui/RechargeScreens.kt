package com.dowdah.utilitytracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
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
                    if (mode == "year") {
                        val periods = (1..12).map { month ->
                            val date = LocalDate.of(anchor.year, month, 1)
                            TrendPeriod(date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())), statisticsForRange(rows, rates, date.atStartOfDay(zone).toInstant().toString(), date.plusMonths(1).atStartOfDay(zone).toInstant().minusNanos(1).toString(), credits))
                        }
                        ConsumptionTrend(periods, meter.unit)
                    } else {
                        IntervalAverageTrend(intervalTrendForRange(rows, rates, start, end, credits), meter.unit)
                    }
                    DailyRemainingTrend(dailyRemainingForRange(meter.id, rows, credits, start, end, zone), meter.unit)
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
internal fun DailyRemainingTrend(points: List<DailyRemainingPoint>, unit: String) {
    Text(stringResource(R.string.daily_remaining_title, unit), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.daily_remaining_hint), style = MaterialTheme.typography.bodySmall)
    if (points.none { it.value != null }) {
        Text(stringResource(R.string.daily_remaining_empty), style = MaterialTheme.typography.bodySmall)
        return
    }

    var selectedIndex by remember(points) { mutableIntStateOf(points.indexOfLast { it.value != null }) }
    var showValues by remember(points) { mutableStateOf(false) }
    val maxValue = points.mapNotNull { it.value }.maxOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val primary = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val markerColor = MaterialTheme.colorScheme.secondary
    val axisColor = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.daily_remaining_chart_description)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
    val span = (points.size - 1).coerceAtLeast(1)
    fun position(index: Int): Float = if (points.size == 1) .5f else index.toFloat() / span

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("● " + stringResource(R.string.daily_remaining_actual), style = MaterialTheme.typography.labelSmall)
        Text("○ " + stringResource(R.string.daily_remaining_estimated), style = MaterialTheme.typography.labelSmall)
        Text("◆ " + stringResource(R.string.daily_remaining_recharge), style = MaterialTheme.typography.labelSmall)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val plotWidth = maxOf(maxWidth, minOf(2400.dp, 10.dp * points.size))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text("${formatTrendNumber(maxValue)} $unit", style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.width(plotWidth).height(144.dp).semantics { contentDescription = description; testTag = "daily_remaining_chart" }.pointerInput(points, plotWidth) {
                detectTapGestures { tap ->
                    val inset = 6.dp.toPx()
                    val fraction = ((tap.x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                    selectedIndex = (fraction * span).roundToInt().coerceIn(points.indices)
                }
            }) {
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                val inset = 6.dp.toPx()
                drawLine(axisColor.copy(alpha = .35f), Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
                drawLine(axisColor.copy(alpha = .2f), Offset(0f, (top + bottom) / 2f), Offset(size.width, (top + bottom) / 2f), 1.dp.toPx())
                fun x(index: Int): Float = inset + position(index) * (size.width - 2 * inset)
                fun pointOffset(index: Int, value: BigDecimal) = Offset(x(index),
                    bottom - value.divide(maxValue, 12, RoundingMode.HALF_EVEN).toFloat().coerceIn(0f, 1f) * (bottom - top))
                points.forEachIndexed { index, point ->
                    val previous = points.getOrNull(index - 1)
                    if (previous?.value != null && point.value != null && !point.breakBefore &&
                        previous.rechargeCount == 0 && point.rechargeCount == 0) {
                        drawLine(primary, pointOffset(index - 1, previous.value), pointOffset(index, point.value), 2.dp.toPx())
                    }
                }
                points.forEachIndexed { index, point ->
                    val x = x(index)
                    if (point.rechargeCount > 0) {
                        val side = 3.dp.toPx()
                        val centerY = bottom + 4.dp.toPx()
                        drawPath(Path().apply {
                            moveTo(x, centerY - side)
                            lineTo(x + side, centerY)
                            lineTo(x, centerY + side)
                            lineTo(x - side, centerY)
                            close()
                        }, markerColor)
                    }
                    val value = point.value ?: return@forEachIndexed
                    val offset = pointOffset(index, value)
                    val color = if (index == selectedIndex) selectedColor else primary
                    if (point.source == RemainingSource.ACTUAL) drawCircle(color, 4.dp.toPx(), offset)
                    else if (size.width / span >= 10.dp.toPx() || index == selectedIndex)
                        drawCircle(color, 4.dp.toPx(), offset, style = Stroke(2.dp.toPx()))
                }
            }
            Row(Modifier.width(plotWidth), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().date.format(dateFormat), style = MaterialTheme.typography.labelSmall)
                Text(points.last().date.format(dateFormat), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    points.getOrNull(selectedIndex)?.let { point ->
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "daily_remaining_detail" }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(point.date.format(dateFormat), style = MaterialTheme.typography.titleSmall)
                Text(dailyRemainingValue(point, unit))
                when (point.source) {
                    RemainingSource.ACTUAL -> Text(stringResource(R.string.daily_remaining_observed_at,
                        point.recordedAt?.toString()?.localDisplay() ?: "—"))
                    RemainingSource.ESTIMATED -> Text(stringResource(R.string.daily_remaining_estimated_end))
                    RemainingSource.UNAVAILABLE -> Text(stringResource(when (point.gap) {
                        RemainingGap.UNKNOWN_CONSUMPTION -> R.string.daily_remaining_unknown_interval
                        RemainingGap.ZERO_DURATION -> R.string.daily_remaining_zero_duration
                        RemainingGap.NEGATIVE_ESTIMATE -> R.string.daily_remaining_negative_estimate
                        else -> R.string.daily_remaining_no_bounds
                    }))
                }
                if (point.rechargeCount > 0) Text(stringResource(R.string.daily_remaining_recharge_detail,
                    point.rechargeCount, point.rechargeQuantity.stripTrailingZeros().toPlainString(), unit))
            }
        }
    }
    TextButton({ showValues = !showValues }) {
        Text(stringResource(if (showValues) R.string.daily_remaining_hide_values else R.string.daily_remaining_show_values))
    }
    if (showValues) {
        LazyColumn(Modifier.fillMaxWidth().height(240.dp).semantics { testTag = "daily_remaining_values" }) {
            items(points.size) { index ->
                val point = points[index]
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .semantics { selected = index == selectedIndex; testTag = "daily_remaining_item_$index" }
                    .clickable { selectedIndex = index }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(point.date.format(dateFormat), style = MaterialTheme.typography.bodySmall)
                    Text(dailyRemainingValue(point, unit), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun dailyRemainingValue(point: DailyRemainingPoint, unit: String): String {
    val value = point.value ?: return stringResource(R.string.daily_remaining_unavailable)
    val number = if (point.source == RemainingSource.ACTUAL) value.stripTrailingZeros().toPlainString()
        else "≈${formatTrendNumber(value)}"
    val source = stringResource(if (point.source == RemainingSource.ACTUAL) R.string.daily_remaining_actual else R.string.daily_remaining_estimated)
    return "$source: $number $unit"
}

@Composable
internal fun IntervalAverageTrend(periods: List<IntervalTrendPoint>, unit: String) {
    Text(stringResource(R.string.interval_average_title, unit), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.interval_average_hint), style = MaterialTheme.typography.bodySmall)
    if (periods.isEmpty()) {
        Text(stringResource(R.string.interval_average_empty), style = MaterialTheme.typography.bodySmall)
        return
    }

    var selectedIndex by remember(periods) { mutableIntStateOf(-1) }
    val axisStart = periods.minOf { it.end }
    val axisEnd = periods.maxOf { it.end }
    val axisMillis = max(1L, java.time.Duration.between(axisStart, axisEnd).toMillis())
    val maxAverage = periods.mapNotNull { it.averagePerDay }.maxOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val primary = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outline
    val chartDescription = stringResource(R.string.interval_average_chart_description)
    fun position(instant: Instant): Float = if (axisStart == axisEnd) .5f else
        (java.time.Duration.between(axisStart, instant).toMillis().toFloat() / axisMillis.toFloat()).coerceIn(0f, 1f)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val plotWidth = maxOf(maxWidth, 56.dp * periods.size)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text("${formatTrendNumber(maxAverage)} $unit/" + stringResource(R.string.day_unit), style = MaterialTheme.typography.labelSmall)
            Canvas(Modifier.width(plotWidth).height(128.dp).semantics { contentDescription = chartDescription; testTag = "interval_average_chart" }.pointerInput(periods, plotWidth) {
                detectTapGestures { tap ->
                    val inset = 6.dp.toPx()
                    val fraction = ((tap.x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                    selectedIndex = periods.indices.minByOrNull { abs(position(periods[it].end) - fraction) } ?: -1
                }
            }) {
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                val inset = 6.dp.toPx()
                fun x(instant: Instant): Float = inset + position(instant) * (size.width - 2 * inset)
                drawLine(axisColor.copy(alpha = .35f), Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
                drawLine(axisColor.copy(alpha = .2f), Offset(0f, (top + bottom) / 2f), Offset(size.width, (top + bottom) / 2f), 1.dp.toPx())
                fun y(value: BigDecimal): Float = bottom - value.divide(maxAverage, 12, RoundingMode.HALF_EVEN).toFloat().coerceIn(0f, 1f) * (bottom - top)
                periods.zipWithNext().forEach { (previous, current) ->
                    if (previous.averagePerDay != null && current.averagePerDay != null) {
                        drawLine(primary, Offset(x(previous.end), y(previous.averagePerDay)),
                            Offset(x(current.end), y(current.averagePerDay)), 2.dp.toPx())
                    }
                }
                periods.forEachIndexed { index, period ->
                    val average = period.averagePerDay ?: return@forEachIndexed
                    val color = if (index == selectedIndex) selectedColor else primary
                    drawCircle(color, if (index == selectedIndex) 5.dp.toPx() else 4.dp.toPx(),
                        Offset(x(period.end), y(average)))
                }
            }
            Row(Modifier.width(plotWidth), horizontalArrangement = Arrangement.SpaceBetween) {
                val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
                Text(axisStart.atZone(ZoneId.systemDefault()).format(dateFormat), style = MaterialTheme.typography.labelSmall)
                Text(axisEnd.atZone(ZoneId.systemDefault()).format(dateFormat), style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    periods.forEachIndexed { index, period ->
        val reason = when {
            period.hasZeroDuration -> stringResource(R.string.interval_zero_duration)
            period.statistics.hasIncrease -> stringResource(R.string.interval_unknown_increase)
            else -> null
        }
        val value = period.averagePerDay?.let { "${formatTrendNumber(it)} $unit/" + stringResource(R.string.day_unit) } ?: reason ?: "—"
        Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).semantics { selected = index == selectedIndex; testTag = "interval_item_$index" }.clickable { selectedIndex = index }.padding(vertical = 8.dp)) {
            Text("${period.start.toString().localDisplay()} – ${period.end.toString().localDisplay()}", style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (index == selectedIndex) selectedColor else MaterialTheme.colorScheme.onSurface)
        }
    }
    periods.getOrNull(selectedIndex)?.let { period ->
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().semantics { testTag = "interval_detail" }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.interval_detail), style = MaterialTheme.typography.titleSmall)
                Text("${period.start.toString().localDisplay()} – ${period.end.toString().localDisplay()}")
                Text(intervalDurationLabel(period))
                val total = period.statistics.consumption?.let { "${it.stripTrailingZeros().toPlainString()} $unit" }
                    ?: stringResource(if (period.hasZeroDuration) R.string.interval_zero_duration else R.string.interval_unknown_increase)
                Text(stringResource(R.string.interval_total, total))
                Text(stringResource(R.string.interval_average_value, period.averagePerDay?.let { "${formatTrendNumber(it)} $unit/" + stringResource(R.string.day_unit) } ?: "—"))
            }
        }
    }
}

private fun formatTrendNumber(value: BigDecimal): String {
    if (value.signum() == 0) return "0"
    val precision = if (value.abs() < BigDecimal("0.001")) 6 else 3
    val rounded = value.setScale(precision, RoundingMode.HALF_EVEN)
    return if (rounded.signum() == 0) "<0.000001" else rounded.stripTrailingZeros().toPlainString()
}

@Composable
private fun intervalDurationLabel(period: IntervalTrendPoint): String {
    val duration = java.time.Duration.between(period.start, period.end)
    val minutes = duration.toMinutes()
    return when {
        minutes >= 2880 -> stringResource(R.string.interval_duration_days, period.elapsedDays?.let(::formatTrendNumber) ?: "—")
        minutes >= 60 -> stringResource(R.string.interval_duration_hours, BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString())
        minutes > 0 || duration.isZero -> stringResource(R.string.interval_duration_minutes, minutes)
        else -> stringResource(R.string.interval_duration_seconds, BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong()).movePointLeft(9)).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString())
    }
}

@Composable
private fun ConsumptionTrend(periods: List<TrendPeriod>, unit: String) {
    if (periods.isEmpty()) return
    Text(stringResource(R.string.yearly_trend_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.yearly_trend_hint), style = MaterialTheme.typography.bodySmall)
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
