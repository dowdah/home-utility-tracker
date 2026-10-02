package com.dowdah.utilitytracker.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.*
import com.dowdah.utilitytracker.reminders.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val repository: ForecastRepository,
    private val device: ReminderDeviceStore,
    private val controller: ReminderController,
) : ViewModel() {
    val snapshot = repository.snapshots.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val deviceState = device.state
    var failed by mutableStateOf(false); private set
    fun permitted() = controller.permitted()
    fun enable(value: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        try { device.update { it.copy(enabled=value) }; controller.evaluate(); failed=false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed=true }
    }
    fun save(setting: MeterReminderEntity) = viewModelScope.launch {
        try { repository.save(setting); failed=false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed=true }
    }
    fun save(schedule: ReminderScheduleEntity) = viewModelScope.launch {
        try { repository.save(schedule); failed=false }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed=true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit, viewModel: ReminderSettingsViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsState()
    val device by viewModel.deviceState.collectAsState()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(viewModel.permitted()) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted=viewModel.permitted()
        viewModel.enable(granted && permitted)
    }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_RESUME) permitted=viewModel.permitted() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = { SimpleBackBar(stringResource(R.string.reminder_settings),onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.reminder_enable),Modifier.weight(1f))
                    Switch(device.enabled,{ enable ->
                        if (!enable || permitted) viewModel.enable(enable)
                        else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },modifier=Modifier.semantics { testTag="reminder_enable" })
                }
                Text(stringResource(R.string.reminder_schedule_hint))
                if (!permitted) {
                    Text(stringResource(R.string.reminder_permission_hint))
                    TextButton({ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }) {
                        Text(stringResource(R.string.reminder_system_settings))
                    }
                }
                if (viewModel.failed) Text(stringResource(R.string.reminder_save_error),color=MaterialTheme.colorScheme.error)
            }
            item {
                val schedule=snapshot?.schedule ?: ReminderScheduleEntity()
                OutlinedButton({ timeOpen=true },modifier=Modifier.semantics { testTag="reminder_time" }) {
                    Text(stringResource(R.string.reminder_time,"%02d:%02d".format(schedule.hour,schedule.minute)))
                }
            }
            items(snapshot?.meters?.filter { it.active && !it.deleted }.orEmpty(), key={it.id}) { meter ->
                val setting=snapshot!!.setting(meter.id)
                MeterReminderEditor(meter,setting,viewModel::save)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if(timeOpen) {
        val schedule=snapshot?.schedule ?: ReminderScheduleEntity()
        val picker=rememberTimePickerState(schedule.hour,schedule.minute,true)
        AlertDialog(onDismissRequest={timeOpen=false},title={Text(stringResource(R.string.reminder_choose_time))},
            text={TimePicker(picker)},confirmButton={TextButton({viewModel.save(ReminderScheduleEntity(hour=picker.hour,minute=picker.minute));timeOpen=false}) {Text(stringResource(R.string.save))}},
            dismissButton={TextButton({timeOpen=false}) {Text(stringResource(R.string.cancel))}})
    }
}

@Composable
internal fun MeterReminderEditor(meter: MeterEntity, setting: MeterReminderEntity, save: (MeterReminderEntity) -> Unit) {
    var days by rememberSaveable(meter.id,setting.daysThreshold) { mutableStateOf(setting.daysThreshold.toString()) }
    var quantityEnabled by rememberSaveable(meter.id,setting.quantityThreshold) { mutableStateOf(setting.quantityThreshold != null) }
    var quantity by rememberSaveable(meter.id,setting.quantityThreshold) { mutableStateOf(setting.quantityThreshold.orEmpty()) }
    val parsedDays=days.toIntOrNull()
    val parsedQuantity=quantity.toBigDecimalOrNull()
    val valid=parsedDays != null && parsedDays in 1..365 && (!quantityEnabled || (quantity.length <= 128 && parsedQuantity != null && parsedQuantity.signum()>=0 && parsedQuantity.scale()<=24 && parsedQuantity.precision()-parsedQuantity.scale()<=25))
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(meterLabel(meter),style=MaterialTheme.typography.titleMedium)
                Switch(setting.enabled,{save(setting.copy(enabled=it))})
            }
            OutlinedTextField(days,{days=it},label={Text(stringResource(R.string.reminder_days))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().semantics { testTag="reminder_days_${meter.id}" })
            Row { Checkbox(quantityEnabled,{quantityEnabled=it}); Text(stringResource(R.string.reminder_quantity_enable)) }
            if(quantityEnabled) OutlinedTextField(quantity,{quantity=it},label={Text(stringResource(R.string.reminder_quantity,meter.unit))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().semantics { testTag="reminder_quantity_${meter.id}" })
            Text(stringResource(R.string.reminder_threshold_hint),style=MaterialTheme.typography.bodySmall)
            Button({save(setting.copy(daysThreshold=requireNotNull(parsedDays),quantityThreshold=if(quantityEnabled) quantity else null))},enabled=valid,modifier=Modifier.semantics { testTag="reminder_save_${meter.id}" }) { Text(stringResource(R.string.save)) }
        }
    }
}
