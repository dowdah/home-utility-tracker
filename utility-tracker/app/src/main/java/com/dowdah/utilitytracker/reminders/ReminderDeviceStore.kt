package com.dowdah.utilitytracker.reminders

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ReminderDeviceState(val enabled: Boolean = false, val notifiedDates: Set<String> = emptySet())

@Singleton
class ReminderDeviceStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read(): ReminderDeviceState = runCatching {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        val dates = json.optJSONArray("dates") ?: JSONArray()
        ReminderDeviceState(json.optBoolean("enabled",false), (0 until dates.length()).map { dates.getString(it) }.toSet())
    }.getOrDefault(ReminderDeviceState())
    @Synchronized fun update(transform: (ReminderDeviceState) -> ReminderDeviceState) {
        val next = transform(mutable.value)
        val json = JSONObject().put("enabled",next.enabled).put("dates",JSONArray(next.notifiedDates.sorted().takeLast(32)))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        mutable.value = next.copy(notifiedDates = next.notifiedDates.sorted().takeLast(32).toSet())
    }
    companion object { const val FILE_NAME = "balance-reminder-device.json" }
}
