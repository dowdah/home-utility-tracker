package com.dowdah.utilitytracker.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.reminders.ReminderController
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@MediumTest
class NotificationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun isolatedPackageOnly() {
        check(context.packageName.contains(".acceptance"))
    }

    @Test fun coldNotificationDoesNotOverrideRestoredTabsOrSecondaryPage() {
        ActivityScenario.launch<MainActivity>(launchIntent(notification = true)).use { scenario ->
            assertTab("HOME")
            for (tab in listOf("STATISTICS", "SETTINGS")) {
                compose.onNodeWithTag("app_tab_$tab").performClick()
                scenario.recreate()
                assertTab(tab)
            }
            openReminderSettings()
            scenario.recreate()
            assertSecondaryPage()
            scenario.onActivity { assertFalse(it.intent.hasExtra(ReminderController.OPEN_HOME)) }
        }
    }

    @Test fun savedStateIgnoresAnOriginalNotificationIntentRetainedByTheSystem() {
        ActivityScenario.launch<MainActivity>(launchIntent(notification = true)).use { scenario ->
            assertTab("HOME")
            compose.onNodeWithTag("app_tab_SETTINGS").performClick()
            openReminderSettings()
            // Android may restore the original launch Intent, including an already consumed extra.
            scenario.onActivity { it.intent.putExtra(ReminderController.OPEN_HOME, true) }
            scenario.recreate()
            assertSecondaryPage()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            assertTab("SETTINGS")
        }
    }

    @Test fun newNotificationsStillOpenHomeAfterRecreationAndCanBeRepeated() {
        ActivityScenario.launch<MainActivity>(launchIntent(notification = false)).use { scenario ->
            assertTab("HOME")
            compose.onNodeWithTag("app_tab_SETTINGS").performClick()
            openReminderSettings()
            deliverIntent(scenario, notification = true)
            assertTab("HOME")
            compose.onNodeWithContentDescription(context.getString(R.string.back)).assertDoesNotExist()

            compose.onNodeWithTag("app_tab_STATISTICS").performClick()
            deliverIntent(scenario, notification = false)
            assertTab("STATISTICS")
            scenario.recreate()
            assertTab("STATISTICS")
            deliverIntent(scenario, notification = true)
            assertTab("HOME")

            compose.onNodeWithTag("app_tab_SETTINGS").performClick()
            scenario.recreate()
            assertTab("SETTINGS")
        }
    }

    private fun launchIntent(notification: Boolean) = Intent(context, MainActivity::class.java).apply {
        if (notification) putExtra(ReminderController.OPEN_HOME, true)
    }

    private fun deliverIntent(scenario: ActivityScenario<MainActivity>, notification: Boolean) {
        scenario.onActivity {
            it.startActivity(launchIntent(notification).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertTab(tab: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("app_tab_$tab") and isSelected()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("app_tab_$tab").assertIsSelected()
    }

    private fun openReminderSettings() {
        compose.onNodeWithText(context.getString(R.string.reminder_settings)).performScrollTo().performClick()
        assertSecondaryPage()
    }

    private fun assertSecondaryPage() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("app_main_top_bar")).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                compose.onAllNodes(hasContentDescription(context.getString(R.string.back))).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("app_main_top_bar").assertDoesNotExist()
    }
}
