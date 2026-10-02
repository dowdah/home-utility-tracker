package com.dowdah.utilitytracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meter_reminders")
data class MeterReminderEntity(
    @PrimaryKey val meterId: String,
    val enabled: Boolean = true,
    val daysThreshold: Int = 7,
    val quantityThreshold: String? = null,
)

@Entity(tableName = "reminder_schedule")
data class ReminderScheduleEntity(@PrimaryKey val id: Int = 0, val hour: Int = 15, val minute: Int = 0)
