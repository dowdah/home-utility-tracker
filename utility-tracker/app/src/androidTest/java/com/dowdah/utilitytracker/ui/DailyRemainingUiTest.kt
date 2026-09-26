package com.dowdah.utilitytracker.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.data.ReadingEntity
import com.dowdah.utilitytracker.data.RechargeEntity
import com.dowdah.utilitytracker.data.dailyRemainingForRange
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.time.ZoneId
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class DailyRemainingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun measuredEstimatedRechargeAndLocalizedLayouts() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val points = dailyRemainingForRange("electric", listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-05T12:00:00Z", "120"),
        ), listOf(recharge("2026-09-03T06:00:00Z", "50")),
            "2026-09-01T00:00:00Z", "2026-09-07T23:59:59Z", ZoneId.of("UTC"))
        val state = mutableStateOf(DisplayCase(Locale.ENGLISH, false, false))
        val originalLocale = Locale.getDefault()
        compose.setContent {
            val shown = state.value
            val configuration = Configuration(target.resources.configuration).apply {
                setLocale(shown.locale)
                orientation = if (shown.wide) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (shown.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val localized = target.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                UtilityTrackerTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().width(if (shown.wide) 650.dp else 360.dp).verticalScroll(rememberScrollState())) {
                            DailyRemainingTrend(points, "kWh")
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
                compose.onNodeWithText(if (case.locale.language == "zh") "每日剩余量（kWh）" else "Daily remaining (kWh)").assertExists()
                compose.onNodeWithTag("daily_remaining_chart").assertExists()
                compose.onNodeWithTag("daily_remaining_chart").performTouchInput { click(percentOffset(.33f, .5f)) }
                compose.onNodeWithTag("daily_remaining_detail").assertExists()
                screenshot("remaining-${case.locale.language}-${if (case.wide) "wide" else "narrow"}-${if (case.dark) "dark" else "light"}")
            }
        } finally {
            compose.runOnIdle { Locale.setDefault(originalLocale) }
        }
    }

    @Test fun dailyTextValuesAndUnboundedDaysRemainAccessible() {
        val points = dailyRemainingForRange("electric", listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-05T12:00:00Z", "60"),
        ), emptyList(), "2026-09-01T00:00:00Z", "2026-09-10T23:59:59Z", ZoneId.of("UTC"))
        compose.setContent { UtilityTrackerTheme { DailyRemainingTrend(points, "kWh") } }
        compose.onNodeWithText("Show daily values").performClick()
        compose.onNodeWithTag("daily_remaining_values").performScrollToIndex(7)
        compose.onNodeWithTag("daily_remaining_item_7").performClick()
        compose.onNodeWithText("Two readings are required to estimate this day.").assertExists()
    }

    @Test fun annualRangeKeepsTheLastDailyValueReachable() {
        val points = dailyRemainingForRange("electric", listOf(
            reading("a", "2026-01-01T12:00:00Z", "500"),
            reading("b", "2026-12-31T12:00:00Z", "135"),
        ), emptyList(), "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z", ZoneId.of("UTC"))
        compose.setContent { UtilityTrackerTheme { DailyRemainingTrend(points, "kWh") } }
        compose.onNodeWithText("Show daily values").performClick()
        compose.onNodeWithTag("daily_remaining_values").performScrollToIndex(points.lastIndex)
        compose.onNodeWithTag("daily_remaining_item_${points.lastIndex}").performClick()
        compose.onNodeWithTag("daily_remaining_detail").assertExists()
    }

    private fun reading(id: String, at: String, value: String) =
        ReadingEntity(id, "electric", value, at, null, false, 1)
    private fun recharge(at: String, quantity: String) =
        RechargeEntity("credit", "electric", "60", "0.6", quantity, "CNY", at, null, false, 1)

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class DisplayCase(val locale: Locale, val wide: Boolean, val dark: Boolean)
}
