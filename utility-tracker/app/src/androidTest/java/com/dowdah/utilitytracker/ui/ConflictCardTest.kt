package com.dowdah.utilitytracker.ui

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.ConflictEntity
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.MediumTest
class ConflictCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletedServerValuesAreNeverPresentedAsLiveInEitherLanguage() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = mutableStateOf(Locale.ENGLISH)
        val conflict = mutableStateOf(ConflictEntity("op", "reading", "id", "{}", null, createdAt = "2026-10-03T00:00:00Z"))
        compose.setContent {
            val config = Configuration(target.resources.configuration).apply { setLocale(locale.value) }
            CompositionLocalProvider(LocalContext provides target.createConfigurationContext(config), LocalConfiguration provides config) {
                UtilityTrackerTheme { ConflictCard(conflict.value, {}, {}) }
            }
        }
        for (language in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            val resources = target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(language) }).resources
            fun server(value: String) = resources.getString(R.string.server_value, value)
            for ((type, field) in listOf("reading" to "value_decimal", "tariff" to "price_decimal", "recharge" to "amount_decimal")) {
                compose.runOnIdle {
                    locale.value = language
                    conflict.value = conflict.value.copy(entityType = type, serverEntityJson = "{\"$field\":\"42\",\"deleted\":true}")
                }
                compose.onNodeWithText(server(resources.getString(R.string.deleted))).assertExists()
                compose.onNodeWithText(server("42")).assertDoesNotExist()
                compose.onNodeWithText(resources.getString(R.string.local_draft, resources.getString(R.string.delete))).assertExists()
                compose.runOnIdle { conflict.value = conflict.value.copy(serverEntityJson = "{\"$field\":\"42\",\"deleted\":false}") }
                compose.onNodeWithText(server("42")).assertExists()
                compose.runOnIdle { conflict.value = conflict.value.copy(serverEntityJson = null) }
                compose.onNodeWithText(server(resources.getString(R.string.unavailable_value))).assertExists()
            }
        }
    }
}
