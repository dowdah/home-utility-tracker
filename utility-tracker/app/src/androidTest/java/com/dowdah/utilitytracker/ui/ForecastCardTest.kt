package com.dowdah.utilitytracker.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.*
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import java.time.Instant
import java.util.Locale
import org.junit.Rule
import org.junit.Test

@androidx.test.filters.MediumTest
class ForecastCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun estimatesStayDistinctFromActualReadingsAcrossLocalizedLayouts() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = mutableStateOf(Locale.ENGLISH)
        val dark = mutableStateOf(false)
        val meter = MeterEntity("m","COLD_WATER",1,"t",true,false,1)
        val result=balanceForecast(meter,listOf(
            ReadingEntity("a","m","100","2026-08-01T00:00:00Z",null,false,1),
            ReadingEntity("b","m","69","2026-09-01T00:00:00Z",null,false,2),
        ),emptyList(),false,Instant.parse("2026-10-03T00:00:00Z"))
        compose.setContent {
            val configuration = Configuration(target.resources.configuration).apply {
                setLocale(locale.value)
                orientation = if (dark.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalContext provides target.createConfigurationContext(configuration),LocalConfiguration provides configuration) {
                UtilityTrackerTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState())) { ForecastCard(result) } } }
            }
        }
        for (language in listOf(Locale.ENGLISH,Locale.SIMPLIFIED_CHINESE)) for (night in listOf(false,true)) {
            compose.runOnIdle { locale.value=language; dark.value=night }
            val res=target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(language) }).resources
            compose.onNodeWithText(res.getString(R.string.forecast_remaining,"37","t")).assertExists()
            compose.onNodeWithText(res.getString(R.string.forecast_days,"37")).assertExists()
            compose.onNodeWithText(res.getString(R.string.forecast_old)).performScrollTo().assertExists()
            compose.onNodeWithText(res.getString(R.string.forecast_hint)).performScrollTo().assertExists()
        }
    }
}
