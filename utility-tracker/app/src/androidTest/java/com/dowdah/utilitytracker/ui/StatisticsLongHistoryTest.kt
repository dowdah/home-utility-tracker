package com.dowdah.utilitytracker.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.data.*
import com.dowdah.utilitytracker.sync.SyncScheduler
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Full statistics screen stress, separate from calculator timing and individual chart tests. */
@androidx.test.filters.MediumTest
class StatisticsLongHistoryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: UtilityDatabase
    @After fun closeDatabase() { if (::db.isInitialized) db.close() }

    @Test fun tenYearLedgerCanSwitchSelectAndReachBothEndsWithoutUnboundedCanvases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.contains(".acceptance"))
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        val first = Instant.parse("2016-01-01T12:00:00Z")
        val meters = listOf(
            MeterEntity("cold", "COLD_WATER", 1, "t", true, false, 1),
            MeterEntity("hot", "HOT_WATER", 1, "t", true, false, 1),
            MeterEntity("electric", "ELECTRICITY", 1, "kWh", true, false, 1),
        )
        runBlocking { db.withTransaction {
            db.meterDao().upsertAll(meters)
            for (meter in meters) for (day in 0..3653) {
                db.readingDao().upsert(ReadingEntity("${meter.id}-$day", meter.id,
                    (10_000 - day).toString(), first.plusSeconds(day * 86_400L).toString(),
                    "synthetic-ten-year", false, 1))
            }
        } }
        val model = AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), SavedStateHandle())
        model.statisticsMode = "all"
        val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneId.of("Asia/Shanghai"))
        compose.setContent { UtilityTrackerTheme { Surface { StatisticsScreen(model, onTariffs = {}, clock = clock) } } }
        awaitCharts()
        compose.onAllNodesWithTag("interval_average_chart").onFirst().assertWidthIsEqualTo(2400.dp)
        compose.onAllNodesWithTag("daily_remaining_chart").onFirst().assertWidthIsEqualTo(2400.dp)
        compose.onAllNodesWithTag("interval_values").assertCountEquals(0)
        compose.onNodeWithTag("statistics_mode_last30").performClick().assertIsSelected()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("statistics_meter_HOT_WATER").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("statistics_mode_all").performClick().assertIsSelected()
        awaitCharts()
        compose.onAllNodesWithTag("interval_average_chart").onFirst().performScrollTo().performTouchInput {
            // The full canvas is wider than its horizontal viewport; select a visible point.
            click(androidx.compose.ui.geometry.Offset(64f, 32f))
        }
        compose.onAllNodesWithTag("interval_detail").assertCountEquals(1)
        compose.onAllNodesWithTag("interval_values_toggle").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("interval_item_0").performScrollTo().performClick()
        compose.onNodeWithTag("interval_item_0").assertIsSelected()
        compose.onAllNodesWithTag("interval_values").onFirst().performScrollTo().performScrollToIndex(3652)
        compose.onNodeWithTag("interval_item_3652").performClick().assertIsSelected()
        compose.onAllNodesWithTag("interval_detail").assertCountEquals(1)
    }

    private fun awaitCharts() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("interval_average_chart").fetchSemanticsNodes().size == 3 }
    }
}
