package com.dowdah.utilitytracker.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import org.junit.*
import org.junit.Assert.*

/** Optional real-app UI check after live fixtures are synchronized. */
class LiveUiAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun rechargeDraftRotationAndStatistics() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("live_ui") == "true")
        fun label(id: Int) = compose.activity.getString(id)
        compose.onAllNodesWithText(label(R.string.records)).onLast().performClick()
        compose.onNodeWithText(label(R.string.recharges_tab)).performClick()
        compose.onNodeWithTag("add_recharge").performClick()
        compose.onNodeWithTag("recharge_amount").performTextInput("99")
        compose.onNodeWithTag("recharge_price").performTextReplacement("0.6")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithTag("recharge_amount").assertTextContains("99")
        compose.onNodeWithTag("recharge_price").assertTextContains("0.6")
        screenshot("recharge-landscape")
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.onAllNodesWithText(label(R.string.statistics)).onLast().performClick()
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[AppViewModel::class.java].apply {
                statisticsMode = "year"; statisticsAnchor = "2098-01-01"
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.year_mode)).assertIsDisplayed()
        screenshot("statistics-landscape")
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.onNodeWithText(label(R.string.year_mode)).assertIsDisplayed()
        screenshot("statistics-portrait")
        compose.onAllNodesWithText(label(R.string.settings)).onLast().performClick()
        compose.onNodeWithText(label(R.string.export_csv)).performClick()
        compose.onNodeWithText(label(R.string.recharges_tab)).performClick()
        compose.onNodeWithTag("export_save").performClick()
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val filename = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.clazz("android.widget.EditText")), 10000)
        if (filename == null) {
            screenshot("saf-failure")
            device.dumpWindowHierarchy(java.io.File(compose.activity.getExternalFilesDir(null), "saf-window.xml"))
        }
        assertNotNull("System document filename field", filename)
        filename.text = "acceptance-" + java.util.UUID.randomUUID().toString() + ".csv"
        val save = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)save|保存"))), 10000)
        assertNotNull("System document save button", save)
        save.click()
        compose.waitUntil(15000) { compose.onAllNodesWithText(label(R.string.export_complete)).fetchSemanticsNodes().isNotEmpty() }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "\nLIVE_UI_OK\n") })
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(1000) // Wait for the system rotation animation, outside the Compose clock.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val locale = compose.activity.resources.configuration.locales[0].language
        java.io.File(compose.activity.getExternalFilesDir(null), "$name-$locale.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
