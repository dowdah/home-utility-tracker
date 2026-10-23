package com.dowdah.utilitytracker.ui

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.data.*
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Full Activity, navigation, rotation and app-local appearance; synthetic ledger only. */
@androidx.test.filters.MediumTest
class ReadingActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var db: UtilityDatabase
    private lateinit var locales: LocaleList
    private var nightMode = UiModeManager.MODE_NIGHT_AUTO
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun seed() {
        check(context.packageName.contains(".acceptance"))
        locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        nightMode = if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        db = EntryPointAccessors.fromApplication(context, LiveLedgerEntryPoint::class.java).database()
        runBlocking {
            check(db.endpointDao().active() == null)
            db.meterDao().upsertAll(listOf(MeterEntity("reveal-activity", "ELECTRICITY", 1, "kWh", true, false, 1)))
            repeat(60) { i -> db.readingDao().upsert(ReadingEntity("activity-old-$i", "reveal-activity", (1000+i).toString(),
                Instant.parse("2026-01-01T12:00:00Z").minusSeconds(i*86400L).toString(), null, false, 1)) }
        }
        compose.onNodeWithTag("app_tab_RECORDS").performClick()
        compose.waitUntil(10_000) { model().readings.value.size >= 60 }
    }

    @After fun restore() {
        context.getSystemService(LocaleManager::class.java).applicationLocales = locales
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(nightMode)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        runBlocking {
            db.readingDao().allForExport().filter { it.meterId == "reveal-activity" }.forEach {
                db.outboxDao().deleteForEntity("reading", it.id)
                db.readingDao().delete(it.id)
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM meters WHERE id = 'reveal-activity'")
        }
    }

    @Test fun actualFormDraftAndSavedViewportSurviveActivityRecreation() {
        compose.onNodeWithTag("reading_list").performScrollToIndex(30)
        compose.onNodeWithTag("reading_add").performClick()
        compose.onNodeWithTag("reading_value").performTextInput("350.25")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reading_value").assertTextContains("350.25")
        compose.onNodeWithTag("reading_save").performClick()
        val row = awaitValue("350.25")
        compose.onNodeWithTag("reading_${row.id}").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("app_tab_RECORDS").assertIsSelected()
        compose.onNodeWithTag("reading_${row.id}").assertIsDisplayed()
        assertNull(model().pendingReadingRevealId)
    }

    @Test fun realFormRevealsAcrossLanguagesOrientationsAndAppearance() {
        data class Appearance(val locale: String, val landscape: Boolean, val dark: Boolean)
        for ((index, appearance) in listOf(
            Appearance("en", false, false), Appearance("zh", false, true),
            Appearance("en", true, true), Appearance("zh", true, false),
        ).withIndex()) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(appearance.locale)
            context.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                if (appearance.dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
            compose.activityRule.scenario.onActivity { it.requestedOrientation = if (appearance.landscape)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(10_000) {
                val c = compose.activity.resources.configuration
                c.locales[0].language == appearance.locale &&
                    (c.orientation == Configuration.ORIENTATION_LANDSCAPE) == appearance.landscape &&
                    (c.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == appearance.dark
            }
            compose.onNodeWithTag("reading_list").performScrollToIndex(25)
            compose.onNodeWithTag("reading_add").performClick()
            compose.onNodeWithTag("reading_value").performTextInput((300-index).toString())
            compose.onNodeWithTag("reading_save").performClick()
            val row = awaitValue((300-index).toString())
            compose.onNodeWithTag("reading_${row.id}").assertIsDisplayed()
            compose.waitUntil(5_000) { compose.activity.window.decorView.hasWindowFocus() }
            captureIsolatedWindow("reading-${appearance.locale}-${appearance.landscape}-${appearance.dark}")
        }
    }

    private fun model() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun awaitValue(value: String): ReadingEntity {
        compose.waitUntil(10_000) { !model().readingEditorOpen && model().pendingReadingRevealId == null &&
            model().readings.value.any { it.meterId == "reveal-activity" && it.valueDecimal == value } }
        return model().readings.value.single { it.meterId == "reveal-activity" && it.valueDecimal == value }
    }
}
