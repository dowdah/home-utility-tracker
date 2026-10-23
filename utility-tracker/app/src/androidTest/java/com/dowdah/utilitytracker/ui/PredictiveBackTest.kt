package com.dowdah.utilitytracker.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.LiveLedgerEntryPoint
import com.dowdah.utilitytracker.data.MeterEntity
import com.dowdah.utilitytracker.reminders.ReminderController
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Deterministic Navigation Compose progress checks, separate from real edge-touch acceptance. */
@MediumTest
class PredictiveBackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var fixtureMeterId: String? = null

    @Before fun isolatedPackageOnly() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.contains(".acceptance"))
    }

    @After fun restoreClockAndFixture() {
        compose.mainClock.autoAdvance = true
        fixtureMeterId?.let { id ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java).database()
            db.openHelper.writableDatabase.execSQL("DELETE FROM meters WHERE id = ?", arrayOf(id))
        }
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test fun settingsPreviewsKeepCompletePortraitLayoutOnCancelAndCommit() {
        verifySettingsPreviews(landscape = false)
    }

    @Test fun settingsPreviewsKeepCompleteLandscapeLayoutOnCancelAndCommit() {
        verifySettingsPreviews(landscape = true)
    }

    @Test fun statisticsTariffReturnKeepsTabRangeAndRecreatedState() {
        orient(landscape = false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java).database()
        val id = "navigation-${UUID.randomUUID()}"
        fixtureMeterId = id
        runBlocking { db.meterDao().upsertAll(listOf(MeterEntity(id, "ELECTRICITY", 9999, "kWh", true, false, 1))) }
        compose.onNodeWithTag("app_tab_STATISTICS").performClick()
        compose.runOnIdle {
            model().apply {
                statisticsMode = "custom"
                statisticsStart = "2026-08-02T00:00:00Z"
                statisticsEnd = "2026-09-20T23:59:59Z"
                statisticsAnchor = "2026-09-01"
            }
        }
        compose.waitUntil(5_000) { model().meters.value.any { it.id == id } }
        compose.onAllNodesWithText(label(R.string.configure_tariffs)).onLast().performScrollTo().performClick()
        compose.waitForIdle()

        startGesture()
        progressGesture(0.5f)
        compose.onNodeWithTag("app_main_top_bar").assertExists()
        compose.onNodeWithTag("app_bottom_bar").assertExists()
        compose.onNodeWithTag("app_tab_STATISTICS").assertIsSelected()
        completeGesture()
        assertStatisticsState()

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("app_tab_STATISTICS").assertIsSelected()
        assertStatisticsState()
        compose.onAllNodesWithText(label(R.string.configure_tariffs)).onLast().performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        compose.onNodeWithTag("app_tab_STATISTICS").assertIsSelected()
        assertStatisticsState()
    }

    @Test fun toolbarOrdinaryBackAndNotificationRetainTheirDestinations() {
        orient(landscape = false)
        compose.onNodeWithTag("app_tab_SETTINGS").performClick()
        settingsItem(R.string.backend_urls).performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        compose.onNodeWithTag("app_tab_SETTINGS").assertIsSelected()

        settingsItem(R.string.tariff_history).performScrollTo().performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("app_tab_SETTINGS").assertIsSelected()

        settingsItem(R.string.reminder_settings).performScrollTo().performClick()
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ReminderController.OPEN_HOME, true),
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("app_tab_HOME") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("app_tab_HOME").assertIsSelected()
        compose.onNodeWithTag("app_main_top_bar").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.back)).assertDoesNotExist()
    }

    private fun verifySettingsPreviews(landscape: Boolean) {
        orient(landscape)
        compose.onNodeWithTag("app_tab_SETTINGS").performClick()
        val chromeTags = listOf("app_main_top_bar", "app_main_content", if (landscape) "app_navigation_rail" else "app_bottom_bar")
        for (entry in listOf(R.string.reminder_settings, R.string.backend_urls, R.string.tariff_history, R.string.conflicts, R.string.export_csv)) {
            settingsItem(entry).performScrollTo()
            val expectedChrome = chromeTags.associateWith { bounds(it) }
            val expectedRow = settingsItem(entry).fetchSemanticsNode().boundsInRoot
            settingsItem(entry).performClick()
            compose.onNodeWithContentDescription(label(R.string.back)).assertIsDisplayed()
            compose.onNodeWithTag("app_main_top_bar").assertDoesNotExist()

            startGesture()
            for (progress in listOf(0.1f, 0.5f, 0.9f)) {
                progressGesture(progress)
                expectedChrome.forEach { (tag, expected) -> assertBounds(tag, expected, bounds(tag)) }
                assertBounds("settings row", expectedRow, settingsItem(entry).fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithTag("app_tab_SETTINGS").assertIsSelected()
            }
            cancelGesture()
            compose.onNodeWithContentDescription(label(R.string.back)).assertIsDisplayed()
            compose.onNodeWithTag("app_main_top_bar").assertDoesNotExist()

            startGesture()
            progressGesture(0.5f)
            completeGesture()
            expectedChrome.forEach { (tag, expected) -> assertBounds(tag, expected, bounds(tag)) }
            assertBounds("settled settings row", expectedRow, settingsItem(entry).fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("app_tab_SETTINGS").assertIsSelected()
            compose.onNodeWithContentDescription(label(R.string.back)).assertDoesNotExist()
        }
    }

    private fun orient(landscape: Boolean) {
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == expected }
        compose.waitForIdle()
    }

    private fun startGesture() {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(event(0f)) }
        advanceProgressFrame()
    }

    private fun progressGesture(progress: Float) {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(event(progress)) }
        advanceProgressFrame()
    }

    private fun advanceProgressFrame() {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    private fun cancelGesture() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun completeGesture() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun event(progress: Float) = BackEventCompat(100f * progress, 300f, progress, BackEventCompat.EDGE_LEFT)
    private fun label(id: Int) = compose.activity.getString(id)
    private fun settingsItem(id: Int) = compose.onNode(
        hasText(label(id), substring = id == R.string.conflicts) and hasAnyAncestor(hasTestTag("app_main_content")),
    )
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun model() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun assertStatisticsState() {
        compose.runOnIdle {
            assertEquals("custom", model().statisticsMode)
            assertEquals("2026-08-02T00:00:00Z", model().statisticsStart)
            assertEquals("2026-09-20T23:59:59Z", model().statisticsEnd)
            assertEquals("2026-09-01", model().statisticsAnchor)
        }
    }

    private fun assertBounds(label: String, expected: Rect, actual: Rect) {
        assertEquals("$label left", expected.left, actual.left, 1f)
        assertEquals("$label top", expected.top, actual.top, 1f)
        assertEquals("$label right", expected.right, actual.right, 1f)
        assertEquals("$label bottom", expected.bottom, actual.bottom, 1f)
    }
}
