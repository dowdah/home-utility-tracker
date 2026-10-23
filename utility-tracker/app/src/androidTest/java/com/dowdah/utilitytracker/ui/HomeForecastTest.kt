package com.dowdah.utilitytracker.ui

import android.os.Debug
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.data.*
import com.dowdah.utilitytracker.sync.SyncScheduler
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.MediumTest
class HomeForecastTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: UtilityDatabase
    private val store = ViewModelStore()
    @After fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        if (::db.isInitialized) db.close()
    }

    @Test fun tenYearHomeCanScrollAndReturnWithCachedBackgroundForecasts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.contains(".acceptance"))
        db = Room.inMemoryDatabaseBuilder(context, UtilityDatabase::class.java).build()
        val now = Instant.now()
        val types = listOf("ELECTRICITY", "COLD_WATER", "HOT_WATER")
        runBlocking { db.withTransaction {
            db.meterDao().upsertAll(types.map { MeterEntity(it, it, 1, "unit", true, false, 1) })
            for (type in types) for (day in 0..3652) {
                val time = now.minusSeconds((3652 - day) * 86_400L).toString()
                db.readingDao().upsert(ReadingEntity("$type-$day", type, "100", time, "synthetic", false, 1))
                if (day > 0) db.rechargeDao().upsert(RechargeEntity("$type-credit-$day", type, "10", "1", "10", "CNY", time, null, false, 1))
            }
        } }
        val repository = ForecastRepository(db)
        lateinit var model: AppViewModel
        lateinit var forecasts: ForecastViewModel
        instrumentation.runOnMainSync {
            val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    (if (modelClass == AppViewModel::class.java)
                        AppViewModel(BackendRepository(db, SecretStore(context)), SyncScheduler(context), SavedStateHandle())
                    else ForecastViewModel(repository)) as T
            })
            model = provider[AppViewModel::class.java]
            forecasts = provider[ForecastViewModel::class.java]
        }
        val shown = mutableStateOf(true)
        val trace = File(context.filesDir, "home-forecast.trace")
        Debug.startMethodTracingSampling(trace.absolutePath, 32 * 1024 * 1024, 100)
        try {
            compose.setContent { UtilityTrackerTheme { Surface { Column {
                Button(onClick = { shown.value = !shown.value }, modifier = Modifier.testTag("toggle_home")) { Text("Home / away") }
                if (shown.value) HomeScreen(model, forecasts) else Text("Away")
            } } } }
            compose.waitUntil(30_000) { forecasts.state.value?.meters?.size == 3 }
            compose.onNodeWithTag("forecast_HOT_WATER").performScrollTo().assertIsDisplayed()
            val cached = forecasts.state.value
            compose.onNodeWithTag("toggle_home").performClick()
            compose.onNodeWithText("Away").assertIsDisplayed()
            compose.onNodeWithTag("toggle_home").performClick()
            compose.onNodeWithTag("forecast_ELECTRICITY").performScrollTo().assertIsDisplayed()
            assertSame(cached, forecasts.state.value)
        } finally { Debug.stopMethodTracing() }
        assertTrue(trace.length() > 0)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("home_forecast_trace", trace.absolutePath)
            putString("fixture", "10959 readings, 10956 recharges; sampling interval 100us")
        })
    }
}
