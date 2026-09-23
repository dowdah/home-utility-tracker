package com.dowdah.utilitytracker.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.data.ReadingEntity
import com.dowdah.utilitytracker.data.intervalTrendForRange
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class IntervalAverageTrendUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun intervalDetailsAndLocalizedLayouts() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val points = intervalTrendForRange(listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-03T12:00:00Z", "80"),
            reading("c", "2026-09-07T12:00:00Z", "60"),
        ), emptyList(), null, null)
        val state = mutableStateOf(DisplayCase(Locale.ENGLISH, false, false))
        val originalLocale = Locale.getDefault()
        compose.setContent {
            val shown = state.value
            val configuration = Configuration(target.resources.configuration).apply {
                setLocale(shown.locale)
                orientation = if (shown.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (shown.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val localized = target.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                UtilityTrackerTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().width(if (shown.landscape) 650.dp else 360.dp).verticalScroll(rememberScrollState())) {
                            IntervalAverageTrend(points, "kWh")
                        }
                    }
                }
            }
        }
        try {
            for (case in listOf(
                DisplayCase(Locale.ENGLISH, false, false),
                DisplayCase(Locale.SIMPLIFIED_CHINESE, true, false),
                DisplayCase(Locale.ENGLISH, true, true),
                DisplayCase(Locale.SIMPLIFIED_CHINESE, false, true),
            )) {
                compose.runOnIdle { Locale.setDefault(case.locale); state.value = case }
                compose.waitForIdle()
                compose.onNodeWithTag("interval_average_chart").assertExists()
                compose.onNodeWithTag("interval_item_0").performClick()
                compose.onNodeWithTag("interval_detail").assertExists()
                compose.onNodeWithText(if (case.locale.language == "zh") "区间日均消耗（kWh/天）" else "Interval average consumption (kWh/day)").assertExists()
                screenshot("interval-${case.locale.language}-${if (case.landscape) "wide" else "narrow"}-${if (case.dark) "dark" else "light"}")
                compose.onNodeWithTag("interval_average_chart").performTouchInput {
                    click(percentOffset(.75f, .75f))
                }
                compose.onNodeWithText(if (case.locale.language == "zh") "区间日均：5 kWh/天" else "Interval average: 5 kWh/day").assertExists()
            }
        } finally {
            compose.runOnIdle { Locale.setDefault(originalLocale) }
        }
    }

    @Test fun emptyRangeExplainsMissingIntervals() {
        compose.setContent { UtilityTrackerTheme { IntervalAverageTrend(emptyList(), "kWh") } }
        compose.onNodeWithText("No completed reading interval in this range.").assertExists()
    }

    @Test fun denseIntervalsKeepTheLastEntryReachable() {
        val rows = (0..15).map { day -> reading("r$day", "2026-09-${(day + 1).toString().padStart(2, '0')}T12:00:00Z", (100 - day).toString()) }
        val points = intervalTrendForRange(rows, emptyList(), null, null)
        compose.setContent {
            UtilityTrackerTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
                        IntervalAverageTrend(points, "kWh")
                    }
                }
            }
        }
        compose.onNodeWithTag("interval_item_14").performScrollTo().performClick()
        compose.onNodeWithTag("interval_detail").assertExists()
    }

    @Test fun unknownZeroAndZeroDurationHaveDistinctText() {
        val points = intervalTrendForRange(listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-02T12:00:00Z", "120"),
            reading("c", "2026-09-03T12:00:00Z", "120"),
            reading("d", "2026-09-03T12:00:00Z", "110"),
        ), emptyList(), null, null)
        compose.setContent { UtilityTrackerTheme { IntervalAverageTrend(points, "kWh") } }
        compose.onNodeWithText("Cannot calculate: recorded recharges do not explain the increased balance.").assertExists()
        compose.onNodeWithText("0 kWh/day").assertExists()
        compose.onNodeWithText("Cannot calculate a daily average: the readings have the same time.").assertExists()
    }

    private fun reading(id: String, at: String, remaining: String) =
        ReadingEntity(id, "electric", remaining, at, null, false, 1)

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class DisplayCase(val locale: Locale, val landscape: Boolean, val dark: Boolean)
}
