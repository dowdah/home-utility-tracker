package com.dowdah.utilitytracker.ui

import android.util.Log
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.*
import com.dowdah.utilitytracker.sync.SyncScheduler
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Saves through the real form and checks the viewport, not merely the database. */
@androidx.test.filters.MediumTest
class ReadingRevealTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: UtilityDatabase
    private lateinit var model: AppViewModel
    private lateinit var savedState: SavedStateHandle
    private var showRecords by mutableStateOf(true)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setup() {
        check(context.packageName.contains(".acceptance"))
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        runBlocking {
            db.meterDao().upsertAll(listOf(
                MeterEntity("electric", "ELECTRICITY", 1, "kWh", true, false, 1),
                MeterEntity("cold", "COLD_WATER", 1, "t", true, false, 1),
                MeterEntity("hot", "HOT_WATER", 1, "t", true, false, 1),
            ))
            db.withTransaction { repeat(80) { index ->
                db.readingDao().upsert(ReadingEntity("old-$index", "electric", (1000 + index).toString(),
                    Instant.parse("2026-01-01T12:00:00Z").minusSeconds(index * 86400L).toString(), null, false, 1))
            } }
            assertNull(db.endpointDao().active()) // Entire form path works without a server/token.
        }
        savedState = SavedStateHandle()
        compose.runOnUiThread { model = newModel(savedState) }
        compose.setContent { UtilityTrackerTheme { Surface { if (showRecords) RecordsScreen(model) } } }
        compose.waitUntil(10_000) { model.readings.value.size == 80 && model.meters.value.size == 3 }
    }

    @After fun teardown() { compose.runOnIdle { showRecords = false }; if (::db.isInitialized) db.close() }

    @Test fun savedReadingIsVisibleFromMiddleOfStableKeyList() {
        compose.onNodeWithTag("reading_list").performScrollToIndex(35)
        compose.onNodeWithTag("reading_old-35").assertIsDisplayed()
        val row = save("500")
        val flowContains = model.readings.value.any { it.id == row.id }
        val filterContains = model.recordFilter == null || model.recordFilter == row.meterId
        val displayed = compose.onNodeWithTag("reading_${row.id}").isDisplayed()
        Log.i("ReadingRevealEvidence", "transaction=true roomFlow=$flowContains filter=$filterContains visible=$displayed")
        assertTrue(flowContains)
        assertTrue(filterContains)
        assertVisible(row)
        assertNull(model.recordFilter)
    }

    @Test fun topAndAllFilterRevealEachMeterWithoutChangingAll() {
        for (meter in listOf("electric", "cold", "hot")) {
            compose.onNodeWithTag("reading_list").performScrollToIndex(0)
            val row = save("400", meter)
            assertVisible(row)
            assertNull(model.recordFilter)
        }
    }

    @Test fun emptyAndIncompatibleFiltersSwitchOnlyWhenNeeded() {
        compose.onNodeWithTag("reading_filter_cold").performClick()
        compose.onNodeWithText(context.getString(R.string.no_records)).assertIsDisplayed()
        val cold = save("20", "cold")
        assertEquals("cold", model.recordFilter)
        assertVisible(cold)
        val electric = save("300", "electric")
        assertEquals("electric", model.recordFilter)
        assertVisible(electric)
        compose.onNodeWithTag("reading_filter_all").performClick()
        val hot = save("10", "hot")
        assertNull(model.recordFilter)
        assertVisible(hot)
    }

    @Test fun emptyLedgerAndSoftDeletedRowsDoNotHideFirstSave() {
        runBlocking { db.withTransaction {
            db.readingDao().allForExport().forEach { db.readingDao().upsert(it.copy(deleted = true)) }
        } }
        compose.waitUntil(10_000) { model.readings.value.isEmpty() }
        compose.onNodeWithText(context.getString(R.string.no_records)).assertIsDisplayed()
        val row = save("80")
        assertVisible(row)
        assertEquals(1, model.readings.value.size)
    }

    @Test fun backfilledAndSameTimestampConsecutiveSavesLocateExactIds() {
        val at = Instant.parse("2026-01-01T12:00:00Z").minusSeconds(35 * 86400L).toString()
        // Date fixtures are injected into the same picker-backed draft; Save uses the actual form.
        val first = save("1035", at = at)
        assertVisible(first)
        val second = save("1035", at = at)
        assertNotEquals(first.id, second.id)
        assertVisible(second)
        compose.waitUntil(5_000) { model.pendingReadingRevealId == null }
        compose.onNodeWithTag("reading_${second.id}").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.reading_just_added)))
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("reading_${second.id}").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == null
        }
    }

    @Test fun failedSaveKeepsDraftAndDoesNotRequestOrPerformReveal() {
        compose.onNodeWithTag("reading_list").performScrollToIndex(35)
        compose.onNodeWithTag("reading_add").performClick()
        compose.onNodeWithTag("reading_value").performTextInput("500")
        compose.runOnIdle { model.readingNote = "x".repeat(1001) }
        compose.onNodeWithTag("reading_save").performClick()
        compose.waitUntil(5_000) { model.message != null && !model.formBusy }
        assertTrue(model.readingEditorOpen)
        assertEquals("500", model.readingValue)
        assertEquals(1001, model.readingNote.length)
        assertNull(model.pendingReadingRevealId)
        assertTrue(runBlocking { db.outboxDao().all().isEmpty() })
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("reading_old-35").assertIsDisplayed()
    }

    @Test fun remoteUpdatesEditsAndDeletesDoNotStealScroll() {
        save("500")
        compose.onNodeWithTag("reading_list").performScrollToIndex(36)
        compose.onNodeWithTag("reading_old-35").assertIsDisplayed()
        runBlocking {
            db.readingDao().upsert(ReadingEntity("remote", "electric", "499", Instant.now().plusSeconds(1).toString(), "remote fixture", false, 2))
            val saved = db.readingDao().allForExport().single { it.valueDecimal == "500" }
            db.readingDao().upsert(saved.copy(serverRevision = 2))
        }
        compose.waitUntil(5_000) { model.readings.value.any { it.id == "remote" } }
        compose.onNodeWithTag("reading_old-35").assertIsDisplayed()
        compose.onNodeWithTag("reading_actions_old-35").performClick()
        compose.onNodeWithText(context.getString(R.string.edit)).performClick()
        compose.onNodeWithTag("reading_value").performTextReplacement("1035.5")
        compose.onNodeWithTag("reading_save").performClick()
        compose.waitUntil(5_000) { !model.readingEditorOpen }
        assertNull(model.pendingReadingRevealId)
        compose.onNodeWithTag("reading_old-35").assertIsDisplayed()
        compose.onNodeWithTag("reading_actions_old-35").performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        compose.waitUntil(5_000) { model.readings.value.none { it.id == "old-35" } }
        compose.onNodeWithTag("reading_old-36").assertIsDisplayed()
        assertNull(model.pendingReadingRevealId)
    }

    @Test fun capturedSubmissionAndUnconsumedRevealSurviveNewViewModel() = runBlocking {
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transaction = launch(Dispatchers.IO) { db.withTransaction { acquired.complete(Unit); release.await() } }
        acquired.await()
        try {
            compose.onNodeWithTag("reading_add").performClick()
            compose.onNodeWithTag("reading_value").performTextInput("500")
            compose.onNodeWithTag("reading_save").performClick()
            compose.waitUntil { model.formBusy }
            compose.runOnIdle {
                // The in-flight transaction must retain the submitted values.
                model.readingMeterId = "hot"
                model.readingValue = "999"
                showRecords = false
            }
            release.complete(Unit)
            transaction.join()
            compose.waitUntil(5_000) { !model.formBusy }
            val id = checkNotNull(model.pendingReadingRevealId)
            val row = checkNotNull(db.readingDao().byId(id))
            assertEquals("electric", row.meterId)
            assertEquals("500", row.valueDecimal)
            compose.runOnIdle {
                val restored = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) })
                model = newModel(restored)
                showRecords = true
            }
            compose.waitUntil(10_000) { compose.onNodeWithTag("reading_$id").isDisplayed() }
            assertVisible(row)
            compose.runOnIdle { model.consumeReadingReveal("old-request") }
            assertNull(model.pendingReadingRevealId)
        } finally { release.complete(Unit); transaction.join() }
    }

    private fun newModel(state: SavedStateHandle) = AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), state)

    private fun save(value: String, meter: String = "electric", at: String? = null): ReadingEntity {
        val before = runBlocking { db.readingDao().allForExport().map { it.id }.toSet() }
        compose.onNodeWithTag("reading_add").performClick()
        if (meter != "electric") {
            compose.onNodeWithTag("meter_chooser").performClick()
            compose.onNodeWithTag("meter_option_$meter").performClick()
        }
        if (at != null) compose.runOnIdle { model.readingRecordedAt = at }
        compose.onNodeWithTag("reading_value").performTextInput(value)
        compose.onNodeWithTag("reading_save").performClick()
        compose.waitUntil(10_000) { !model.readingEditorOpen }
        val row = runBlocking { db.readingDao().allForExport().single { it.id !in before } }
        compose.waitUntil(10_000) { model.readings.value.any { it.id == row.id } && model.pendingReadingRevealId == null }
        return row
    }

    private fun assertVisible(row: ReadingEntity) {
        // No performScrollTo here: this must be visible due to the application's save response.
        compose.onNodeWithTag("reading_${row.id}").assertIsDisplayed()
        val item = compose.onNodeWithTag("reading_${row.id}").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("reading_list").fetchSemanticsNode().boundsInRoot
        assertTrue("Saved row has nonzero visible bounds", item.height > 0 && item.top >= list.top && item.bottom <= list.bottom)
    }
}
