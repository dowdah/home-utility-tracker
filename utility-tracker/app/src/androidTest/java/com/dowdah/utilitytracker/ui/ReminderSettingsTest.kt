package com.dowdah.utilitytracker.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.MediumTest
class ReminderSettingsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun reminderDefaultsAndDraftSurviveRecreationAndSaveLocally() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.contains(".acceptance"))
        val entry=EntryPointAccessors.fromApplication(context,LiveLedgerEntryPoint::class.java)
        runBlocking {entry.database().meterDao().upsertAll(listOf(MeterEntity("settings-meter","ELECTRICITY",1,"kWh",true,false,1)))}
        fun label(id: Int)=compose.activity.getString(id)
        compose.onAllNodesWithText(label(R.string.settings)).onLast().performClick()
        compose.onNodeWithText(label(R.string.reminder_settings)).performClick()
        compose.onNodeWithTag("reminder_time").assertTextContains(compose.activity.getString(R.string.reminder_time,"15:00"))
        compose.onNodeWithTag("reminder_days_settings-meter").performScrollTo().performTextReplacement("12")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reminder_days_settings-meter").performScrollTo().assertTextContains("12")
        compose.onNodeWithTag("reminder_save_settings-meter").performScrollTo().performClick()
        compose.waitUntil(10000) { runBlocking { entry.database().reminderDao().meters().any {it.meterId=="settings-meter" && it.daysThreshold==12} } }
        compose.onNodeWithTag("reminder_days_settings-meter").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("reminder_save_settings-meter").performScrollTo().assertIsNotEnabled()
        assertFalse(entry.reminderDevice().state.value.enabled)
        runBlocking {entry.database().meterDao().upsertAll(listOf(MeterEntity("settings-meter","ELECTRICITY",1,"kWh",false,true,1)))}
    }
}
