package com.dowdah.utilitytracker.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

        compose.onNodeWithText("Interval average consumption (kWh/day)").assertExists()
        compose.onNodeWithText("Daily remaining (kWh)").assertExists()
        compose.onNodeWithTag("interval_average_chart").assertExists()
        compose.onNodeWithTag("daily_remaining_chart").assertExists()

        compose.runOnIdle { model.statisticsMode = "year" }
        compose.onNodeWithText("Monthly consumption").assertExists()
        compose.onNodeWithText("Daily remaining (kWh)").assertExists()

        compose.runOnIdle {
            model.statisticsStart = "2026-09-01T00:00:00Z"
            model.statisticsEnd = "2026-09-30T23:59:59Z"
            model.statisticsMode = "custom"
        }
        compose.onNodeWithText("Interval average consumption (kWh/day)").assertExists()
        compose.onNodeWithText("Daily remaining (kWh)").assertExists()
    }
}
