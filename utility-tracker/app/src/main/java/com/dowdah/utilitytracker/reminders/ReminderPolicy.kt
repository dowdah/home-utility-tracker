package com.dowdah.utilitytracker.reminders

import java.time.ZonedDateTime
import com.dowdah.utilitytracker.data.ReminderScheduleEntity

enum class ReminderAction { ALERT, UPDATE, CANCEL, NONE }

fun reminderAction(enabled: Boolean, permitted: Boolean, hasLowBalances: Boolean,
                   schedule: ReminderScheduleEntity, now: ZonedDateTime,
                   notifiedDates: Set<String>, activeNotification: Boolean): ReminderAction {
    if (!enabled || !permitted || !hasLowBalances) return ReminderAction.CANCEL
    if (now.hour * 60 + now.minute < schedule.hour * 60 + schedule.minute) return ReminderAction.NONE
    return if (now.toLocalDate().toString() !in notifiedDates) ReminderAction.ALERT
    else if (activeNotification) ReminderAction.UPDATE else ReminderAction.NONE
}
