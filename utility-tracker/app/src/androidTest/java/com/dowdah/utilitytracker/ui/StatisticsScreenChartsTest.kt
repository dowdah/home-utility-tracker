package com.dowdah.utilitytracker.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.data.BackendRepository
import com.dowdah.utilitytracker.data.MeterEntity
import com.dowdah.utilitytracker.data.ReadingEntity
import com.dowdah.utilitytracker.data.SecretStore
import com.dowdah.utilitytracker.data.TariffEntity
import com.dowdah.utilitytracker.data.UtilityDatabase
import com.dowdah.utilitytracker.sync.SyncScheduler
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class StatisticsScreenChartsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: UtilityDatabase

    @After fun teardown() { if (::db.isInitialized) db.close() }

    @Test fun monthYearAndCustomModesShowTheCorrectCharts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        runBlocking {
            db.meterDao().upsertAll(listOf(MeterEntity("electric", "ELECTRICITY", 1, "kWh", true, false, 1)))
            db.readingDao().upsert(ReadingEntity("a", "electric", "100", "2026-09-01T12:00:00Z", null, false, 1))
            db.readingDao().upsert(ReadingEntity("b", "electric", "80", "2026-09-03T12:00:00Z", null, false, 1))
            db.readingDao().upsert(ReadingEntity("c", "electric", "60", "2026-09-07T12:00:00Z", null, false, 1))
            db.tariffDao().upsert(TariffEntity("rate", "electric", "0.6", "CNY", "2026-08-01T00:00:00Z", false, 1))
        }
        val model = AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), SavedStateHandle())
        model.statisticsAnchor = "2026-09-01"
        compose.setContent { UtilityTrackerTheme { Surface { StatisticsScreen(model, onTariffs = {}) } } }
        compose.waitUntil(5_000) { model.meters.value.isNotEmpty() && model.readings.value.size == 3 }

        awaitTag("interval_average_chart")
        compose.onNodeWithText("Interval average consumption (kWh/day)").assertExists()
        compose.onNodeWithText("Daily remaining (kWh)").assertExists()
        compose.onNodeWithTag("interval_average_chart").assertExists()
        compose.onNodeWithTag("daily_remaining_chart").assertExists()

        compose.runOnIdle { model.statisticsMode = "year" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Monthly consumption").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Monthly consumption").assertExists()
        compose.onNodeWithText("Daily remaining (kWh)").assertExists()

        compose.runOnIdle {
            model.statisticsStart = "2026-09-01T00:00:00Z"
            model.statisticsEnd = "2026-09-30T23:59:59Z"
            model.statisticsMode = "custom"
        }
        awaitTag("interval_average_chart")
        compose.onNodeWithText("Interval average consumption (kWh/day)").assertExists()
        compose.onNodeWithText("Daily remaining (kWh)").assertExists()
    }

    @Test fun sixPresetsCustomCancellationAndElectricityFirst() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        runBlocking { db.meterDao().upsertAll(listOf(
            MeterEntity("cold", "COLD_WATER", 1, "m³", true, false, 1),
            MeterEntity("hot", "HOT_WATER", 1, "m³", true, false, 1),
            MeterEntity("electric", "ELECTRICITY", 1, "kWh", true, false, 1))) }
        val model = AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), SavedStateHandle())
        val clock = Clock.fixed(Instant.parse("2026-10-02T17:51:00Z"), ZoneId.of("Asia/Shanghai"))
        compose.setContent { UtilityTrackerTheme { Surface { StatisticsScreen(model, onTariffs = {}, clock = clock) } } }
        awaitTag("statistics_meter_ELECTRICITY")
        for (mode in listOf("month", "year", "last30", "last183", "all", "custom")) {
            compose.onNodeWithTag("statistics_mode_$mode").assertExists()
        }
        val electric = compose.onNodeWithTag("statistics_meter_ELECTRICITY").fetchSemanticsNode().positionInRoot.y
        val cold = compose.onNodeWithTag("statistics_meter_COLD_WATER").fetchSemanticsNode().positionInRoot.y
        val hot = compose.onNodeWithTag("statistics_meter_HOT_WATER").fetchSemanticsNode().positionInRoot.y
        assertTrue(electric < cold && cold < hot)
        for (mode in listOf("last30", "last183", "all")) {
            compose.onNodeWithTag("statistics_mode_$mode").performClick().assertIsSelected()
            awaitTag("statistics_meter_ELECTRICITY")
            compose.onNodeWithTag("statistics_range_caption").assertExists()
            assertTrue(compose.onAllNodesWithText("Previous period").fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("statistics_mode_custom").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("statistics_mode_all").assertIsSelected()
        compose.runOnIdle { assertEquals("all", model.statisticsMode); assertNull(model.statisticsStart) }
    }

    @Test fun savedRangeAndCustomDatesRestoreWithoutChangingModeOnCancel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        val state = SavedStateHandle(mapOf("statisticsMode" to "last183", "statisticsAnchor" to "2024-02-29",
            "statisticsStart" to "2026-09-01T00:00:00Z", "statisticsEnd" to "2026-09-30T23:59:59Z"))
        val model = AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), state)
        assertEquals("last183", model.statisticsMode)
        assertEquals("2024-02-29", model.statisticsAnchor)
        model.selectStatisticsMode("custom")
        assertTrue(model.rangePickerOpen)
        assertEquals("last183", model.statisticsMode)
        model.rangePickerOpen = false
        assertEquals("2026-09-01T00:00:00Z", model.statisticsStart)
        model.applyStatisticsDates(java.time.LocalDate.of(2024, 2, 28), java.time.LocalDate.of(2024, 2, 29), ZoneId.of("Asia/Shanghai"))
        assertEquals("custom", state.get<String>("statisticsMode"))
        assertEquals("2024-02-27T16:00:00Z", state.get<String>("statisticsStart"))
        assertEquals("2024-02-29T15:59:59.999999999Z", state.get<String>("statisticsEnd"))
        assertFalse(model.rangePickerOpen)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
