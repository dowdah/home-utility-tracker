package com.dowdah.utilitytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dowdah.utilitytracker.ui.UtilityTrackerApp
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notificationOpen = androidx.compose.runtime.mutableIntStateOf(0)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(com.dowdah.utilitytracker.reminders.ReminderController.OPEN_HOME,false)) notificationOpen.intValue++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(com.dowdah.utilitytracker.reminders.ReminderController.OPEN_HOME,false)) notificationOpen.intValue++
        enableEdgeToEdge()
        setContent {
            UtilityTrackerTheme { UtilityTrackerApp(openHome = notificationOpen.intValue) }
        }
    }
}
