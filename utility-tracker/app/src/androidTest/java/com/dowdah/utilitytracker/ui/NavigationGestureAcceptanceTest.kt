package com.dowdah.utilitytracker.ui

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real touchscreen injection through the OS back gesture, not dispatcher simulation. */
@androidx.test.filters.MediumTest
class NavigationGestureAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realEdgeGesturesCancelAndCompleteWithFullMainPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Run tools/navigation_acceptance.py", InstrumentationRegistry.getArguments().getString("real_gestures") == "true")
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".acceptancev131"))
        val device = UiDevice.getInstance(instrumentation)
        // A prior failed injection may have left the test pointer down in InputDispatcher.
        val reset = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { instrumentation.uiAutomation.injectInputEvent(reset, true) } finally { reset.recycle() }
        val entry = EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java)
        runBlocking {
            check(entry.database().endpointDao().active() == null)
            check(!entry.repository().tokenPresent())
            val now = Instant.now().minusSeconds(60)
            for ((id, type, unit) in listOf(Triple("gesture-electric", "ELECTRICITY", "kWh"), Triple("gesture-cold", "COLD_WATER", "t"), Triple("gesture-hot", "HOT_WATER", "t"))) {
                entry.database().meterDao().upsertAll(listOf(MeterEntity(id,type,1,unit,true,false,1)))
                for ((index, days) in listOf(40L,10L,0L).withIndex()) {
                    entry.database().readingDao().upsert(ReadingEntity("$id-$index",id,(100-index*10).toString(),now.minus(Duration.ofDays(days)).toString(),"synthetic-navigation",false,0))
                }
            }
        }
        val locales = context.getSystemService(LocaleManager::class.java)
        val mode = context.getSystemService(UiModeManager::class.java)
        val oldLocales = locales.applicationLocales
        val oldNight = UiModeManager.MODE_NIGHT_AUTO
        try {
            compose.activityRule.scenario.onActivity {
                locales.applicationLocales = LocaleList.forLanguageTags("zh-CN")
                mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(15000) { compose.activity.resources.configuration.locales[0].language == "zh" && compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            compose.onNodeWithTag("app_tab_SETTINGS").performClick()
            compose.onNodeWithTag("app_main_top_bar").assertIsDisplayed()
            screenshot("settings-baseline-portrait-zh-dark",device)
            val routes = listOf(R.string.reminder_settings,R.string.backend_urls,R.string.tariff_history,R.string.conflicts,R.string.export_csv)
            for (labelId in routes) {
                val title = compose.activity.getString(labelId)
                compose.onAllNodesWithText(title,substring=labelId==R.string.conflicts).onFirst().performClick()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).assertExists()
                edgeGesture(device,cancel=true,name="cancel-$labelId")
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).assertExists()
                edgeGesture(device,cancel=false,name="complete-$labelId")
                compose.onNodeWithTag("app_main_top_bar").assertIsDisplayed()
                compose.onNodeWithTag("app_bottom_bar").assertIsDisplayed()
                compose.onNodeWithTag("app_tab_SETTINGS").assertIsSelected()
            }
            compose.onNodeWithTag("app_tab_STATISTICS").performClick()
            compose.onNodeWithTag("statistics_mode_last30").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("statistics_meter_ELECTRICITY").fetchSemanticsNodes().isNotEmpty() }
            screenshot("statistics-last30-portrait-zh-dark",device)
            compose.onAllNodesWithText(compose.activity.getString(R.string.configure_tariffs)).onFirst().performScrollTo().performClick()
            edgeGesture(device,cancel=true,name="statistics-cancel")
            edgeGesture(device,cancel=false,name="statistics-complete")
            compose.onNodeWithTag("statistics_mode_last30").assertIsSelected()
            compose.onNodeWithTag("app_tab_STATISTICS").assertIsSelected()
            compose.onNodeWithTag("app_tab_SETTINGS").performClick()
            compose.activityRule.scenario.onActivity {
                locales.applicationLocales = LocaleList.forLanguageTags("en")
                mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(15000) { compose.activity.resources.configuration.locales[0].language == "en" && compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithText(compose.activity.getString(R.string.backend_urls)).performClick()
            edgeGesture(device,cancel=true,name="landscape-cancel-en-light")
            edgeGesture(device,cancel=false,name="landscape-complete-en-light")
            compose.onNodeWithTag("app_navigation_rail").assertIsDisplayed()
            compose.onNodeWithTag("app_main_top_bar").assertIsDisplayed()
            screenshot("settings-return-landscape-en-light",device)
            // A regular Back key follows the same destination stack.
            compose.onNodeWithText(compose.activity.getString(R.string.export_csv)).performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).assertIsDisplayed()
            compose.waitForIdle()
            device.pressBack()
            compose.waitForIdle()
            compose.onNodeWithTag("app_tab_SETTINGS").assertIsSelected()
            compose.onNodeWithTag("app_navigation_rail").assertIsDisplayed()
        } finally {
            // Restoring locale/night mode can recreate the Activity; release its orientation first.
            runCatching { compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
            locales.applicationLocales = oldLocales
            mode.setApplicationNightMode(oldNight)
        }
    }

    private fun edgeGesture(device: UiDevice, cancel: Boolean, name: String) {
        // Finish destination entry before starting OS input (performClick only delivers
        // the click; unlike the settings loop, callers need not query the new page).
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).assertExists()
        compose.waitForIdle()
        // Compose idleness does not include the system's cancelled-back surface transition.
        SystemClock.sleep(1000)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        check(device.currentPackageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        val down = SystemClock.uptimeMillis()
        val edge = device.displayWidth - 1f
        val end = device.displayWidth * .57f
        val y = device.displayHeight * .6f
        fun event(action: Int, x: Float) {
            val e = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("OS touch injection",automation.injectInputEvent(e,true)) } finally { e.recycle() }
        }
        var released = false
        try {
            event(MotionEvent.ACTION_DOWN,edge)
            for (index in 1..12) { event(MotionEvent.ACTION_MOVE,edge+(end-edge)*index/12);SystemClock.sleep(20) }
            SystemClock.sleep(250)
            // Native input has delivered the progress; drive Compose's test clock before inspection.
            compose.waitForIdle()
            compose.onNodeWithTag("app_main_top_bar").assertExists()
            val landscape = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            compose.onNodeWithTag(if (landscape) "app_navigation_rail" else "app_bottom_bar").assertExists()
            screenshot("$name-progress",device)
            if (cancel) {
                for(index in 1..12) {event(MotionEvent.ACTION_MOVE,end+(edge-end)*index/12);SystemClock.sleep(20)}
                event(MotionEvent.ACTION_UP,edge)
            } else event(MotionEvent.ACTION_UP,end)
            released = true
            SystemClock.sleep(1000)
            compose.waitForIdle()
            screenshot("$name-finished",device)
        } finally {
            if (!released) {
                val release = MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,end,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
                try { automation.injectInputEvent(release,true) } finally { release.recycle() }
            }
        }
    }

    private fun screenshot(name: String, device: UiDevice) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(device.currentPackageName == instrumentation.targetContext.packageName) { "Capture only the isolated test app" }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null),"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
