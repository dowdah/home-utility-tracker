package com.dowdah.utilitytracker.data

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.ui.AppViewModel
import com.dowdah.utilitytracker.ui.RechargeEditor
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import com.dowdah.utilitytracker.sync.SyncScheduler
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

@androidx.test.filters.MediumTest
class RechargeFormTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: UtilityDatabase
    private lateinit var model: AppViewModel
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        runBlocking { db.meterDao().upsertAll(listOf(MeterEntity("electric", "ELECTRICITY", 1, "kWh", true, false, 1))) }
        compose.runOnUiThread { model = AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), SavedStateHandle()) }
        compose.setContent {
            val meters by model.meters.collectAsState()
            UtilityTrackerTheme { if (model.rechargeDraft.open) RechargeEditor(model, meters) }
        }
        compose.waitUntil { model.meters.value.isNotEmpty() }
        compose.runOnIdle { model.openNewRecharge() }
    }
    @After fun teardown() { db.close() }
    @Test fun amountPriceAndOptionalBalanceSaveOneAtomicGroup() {
        compose.onNodeWithTag("recharge_amount").performTextInput("60")
        compose.onNodeWithTag("recharge_price").performTextInput("0.6")
        compose.onNodeWithTag("recharge_remaining").performScrollTo().performTextInput("150")
        compose.onNodeWithTag("recharge_save").performClick()
        compose.waitUntil(5000) { !model.rechargeDraft.open }
        runBlocking {
            assertEquals(2, db.outboxDao().all().size)
            assertEquals(1, db.outboxDao().all().map { it.groupId }.distinct().size)
        }
    }
    @Test fun invalidNotePreservesEnteredDraftAndExplainsFailure() {
        compose.onNodeWithTag("recharge_amount").performTextInput("60")
        compose.onNodeWithTag("recharge_price").performTextInput("0.6")
        compose.runOnIdle { model.updateRecharge(model.rechargeDraft.copy(note = "x".repeat(1001))) }
        compose.onNodeWithTag("recharge_save").performClick()
        compose.waitUntil(5000) { model.message != null }
        compose.runOnIdle { assertTrue(model.rechargeDraft.open); assertEquals("60", model.rechargeDraft.amount) }
        runBlocking { assertTrue(db.outboxDao().all().isEmpty()) }
    }
}
