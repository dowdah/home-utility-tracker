package com.dowdah.utilitytracker.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ForecastSnapshot(
    val meters: List<MeterEntity>, val readings: List<ReadingEntity>, val recharges: List<RechargeEntity>,
    val conflictedMeters: Set<String>, val settings: List<MeterReminderEntity>, val schedule: ReminderScheduleEntity,
) {
    fun forecasts(now: Instant) = meters.filter { it.active && !it.deleted }.map {
        balanceForecast(it, readings, recharges, it.id in conflictedMeters, now)
    }
    fun setting(meterId: String) = settings.firstOrNull { it.meterId == meterId } ?: MeterReminderEntity(meterId)
}

@Singleton
class ForecastRepository @Inject constructor(private val db: UtilityDatabase) {
    val snapshots: Flow<ForecastSnapshot> = merge(
        db.meterDao().observeActive().map { Unit }, db.readingDao().observeActive().map { Unit },
        db.rechargeDao().observeActive().map { Unit }, db.conflictDao().observeAll().map { Unit },
        db.reminderDao().observeMeters().map { Unit }, db.reminderDao().observeSchedule().map { Unit },
    ).conflate().map { snapshot() }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): ForecastSnapshot = db.withTransaction {
        val readings = db.readingDao().allForExport()
        val recharges = db.rechargeDao().allForExport()
        val affected = mutableSetOf<String>()
        db.conflictDao().all().filter { it.entityType in setOf("reading", "recharge") }.forEach { conflict ->
            listOfNotNull(conflict.localPayloadJson, conflict.serverEntityJson).forEach { payload ->
                runCatching { Json.parseToJsonElement(payload).jsonObject["meter_id"]?.jsonPrimitive?.content }.getOrNull()?.let(affected::add)
            }
            (if (conflict.entityType == "reading") readings.firstOrNull { it.id == conflict.entityId }?.meterId
            else recharges.firstOrNull { it.id == conflict.entityId }?.meterId)?.let(affected::add)
        }
        ForecastSnapshot(db.meterDao().active(), readings, recharges, affected, db.reminderDao().meters(), db.reminderDao().schedule() ?: ReminderScheduleEntity())
    }
    suspend fun save(setting: MeterReminderEntity) {
        require(setting.daysThreshold in 1..365)
        val quantity = setting.quantityThreshold?.takeIf { it.isNotBlank() }?.let {
            require(it.length <= 128)
            val value = it.toBigDecimal()
            require(value.signum() >= 0 && value.scale() <= 24 && value.precision() - value.scale() <= 25)
            value.stripTrailingZeros().toPlainString()
        }
        db.reminderDao().saveMeter(setting.copy(quantityThreshold = quantity))
    }
    suspend fun save(schedule: ReminderScheduleEntity) {
        require(schedule.id == 0 && schedule.hour in 0..23 && schedule.minute in 0..59)
        db.reminderDao().saveSchedule(schedule)
    }
}
