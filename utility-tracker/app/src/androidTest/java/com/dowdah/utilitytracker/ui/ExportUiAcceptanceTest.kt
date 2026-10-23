package com.dowdah.utilitytracker.ui

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.LiveLedgerEntryPoint
import com.dowdah.utilitytracker.data.MeterEntity
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.MediumTest
class ExportUiAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localAndServerSourcesSurviveLocalizedRotationAndSafCancellationAndSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.contains(".acceptance"))
        val entry = EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java)
        val meter = UUID.randomUUID().toString()
        runBlocking {
            entry.database().meterDao().upsertAll(listOf(MeterEntity(meter, "ELECTRICITY", 1, "kWh", true, false, 1)))
            entry.repository().saveReading(meterId = meter, value = "42.123456789012", recordedAt = "2026-10-02T00:00:00Z", note = "synthetic-export-ui")
        }
        fun label(id: Int) = compose.activity.getString(id)
        val locales = context.getSystemService(LocaleManager::class.java)
        val originalLocales = locales.applicationLocales
        val device = UiDevice.getInstance(instrumentation)
        try {
            compose.onAllNodesWithText(label(R.string.settings)).onLast().performClick()
            compose.onNodeWithText(label(R.string.export_csv)).performClick()
            compose.onNodeWithText(label(R.string.export_local)).assertIsSelected()
            compose.onNodeWithText(label(R.string.export_server)).performClick()
            compose.onNodeWithText(label(R.string.export_server_pending_warning)).assertExists()
            compose.onNodeWithText(label(R.string.tariff_history)).performScrollTo().performClick()
            for ((language, orientation, dark) in listOf(
                Triple("zh-CN", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, true),
                Triple("en", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, false),
            )) {
                compose.activityRule.scenario.onActivity {
                    locales.applicationLocales = LocaleList.forLanguageTags(language)
                    it.getSystemService(UiModeManager::class.java).setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
                    it.requestedOrientation = orientation
                }
                compose.waitUntil(15000) {
                    compose.activity.resources.configuration.locales[0].language == language.substringBefore('-') &&
                        compose.activity.resources.configuration.orientation == if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                compose.onNodeWithText(label(R.string.export_server)).assertIsSelected()
                compose.onNodeWithText(label(R.string.tariff_history)).assertIsSelected()
                compose.onNodeWithTag("export_save").performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText(label(R.string.export_local)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.readings_tab)).performScrollTo().performClick()
            compose.onNodeWithTag("export_save").performScrollTo().performClick()
            assertNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000))
            device.pressBack()
            if (device.currentPackageName?.contains("documentsui") == true) device.pressBack()
            compose.waitUntil(10000) { runCatching { compose.onAllNodesWithText(label(R.string.export_cancelled)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            compose.onNodeWithTag("export_save").performScrollTo().assertIsEnabled().performClick()
            val filename = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)!!
            val name = "acceptance-export-${UUID.randomUUID()}.csv"
            filename.text = name
            // Recreate while SAF owns the screen: the frozen source/type must survive.
            compose.activityRule.scenario.onActivity { it.recreate() }
            val save = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)save|保存"))), 10000)
            assertNotNull(save); save!!.click()
            compose.waitUntil(15000) { runCatching { compose.onAllNodesWithText(label(R.string.export_complete)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            val csv = instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
            assertTrue(csv.lineSequence().first().endsWith(",sync_status"))
            assertTrue(csv.contains("42.123456789012")); assertTrue(csv.contains("pending"))
            instrumentation.uiAutomation.executeShellCommand("rm /sdcard/Download/$name").close()
        } finally {
            compose.activityRule.scenario.onActivity {
                locales.applicationLocales = originalLocales
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                it.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
            }
        }
    }
}
