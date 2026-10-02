package com.dowdah.utilitytracker.reminders

import com.dowdah.utilitytracker.data.ReminderScheduleEntity
import java.time.ZonedDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val schedule=ReminderScheduleEntity()
    private fun decide(time: String, dates: Set<String> = emptySet(), active: Boolean = false, enabled: Boolean = true, permitted: Boolean = true, low: Boolean = true, hour: Int = 15) =
        reminderAction(enabled,permitted,low,schedule.copy(hour=hour),ZonedDateTime.parse(time),dates,active)
    @Test fun waitsUntilTheSelectedTime() {
        assertEquals(ReminderAction.NONE,decide("2026-10-03T14:59:59+08:00"))
        assertEquals(ReminderAction.ALERT,decide("2026-10-03T15:00:00+08:00"))
        assertEquals(ReminderAction.NONE,decide("2026-10-03T15:00:00+08:00",hour=16))
    }
    @Test fun repeatedChecksOnlyUpdateExistingNotificationsAndRespectDismissal() {
        assertEquals(ReminderAction.UPDATE,decide("2026-10-03T16:00:00+08:00",setOf("2026-10-03"),true))
        assertEquals(ReminderAction.NONE,decide("2026-10-03T16:00:00+08:00",setOf("2026-10-03")))
        assertEquals(ReminderAction.ALERT,decide("2026-10-04T15:00:00+08:00",setOf("2026-10-03"),true))
    }
    @Test fun recoveryAndPermissionsCancelWithoutConsumingDailyAllowance() {
        val time="2026-10-03T16:00:00+08:00"
        assertEquals(ReminderAction.CANCEL,decide(time,low=false))
        assertEquals(ReminderAction.CANCEL,decide(time,permitted=false))
        assertEquals(ReminderAction.CANCEL,decide(time,enabled=false))
        assertEquals(ReminderAction.ALERT,decide(time))
    }
    @Test fun timezoneChangesUseLocalCalendarDaysWithoutRepeatingPreviouslyNotifiedDates() {
        val now=ZonedDateTime.parse("2026-10-04T16:00:00+08:00")
        val previous=now.withZoneSameInstant(ZoneId.of("Pacific/Honolulu"))
        assertEquals("2026-10-03",previous.toLocalDate().toString())
        assertEquals(ReminderAction.NONE,reminderAction(true,true,true,schedule,previous,setOf("2026-10-03","2026-10-04"),false))
    }
}
