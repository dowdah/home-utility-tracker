package com.dowdah.utilitytracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.dowdah.utilitytracker.reminders.ReminderController
import com.dowdah.utilitytracker.ui.UtilityTrackerApp
import com.dowdah.utilitytracker.ui.theme.UtilityTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notificationOpen = mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeNotificationIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore navigation without replaying the original notification launch.
        if (savedInstanceState == null) consumeNotificationIntent(intent)
        enableEdgeToEdge()
        setContent {
            UtilityTrackerTheme { UtilityTrackerApp(openHome = notificationOpen.intValue) }
        }
    }

    private fun consumeNotificationIntent(intent: Intent) {
        if (intent.getBooleanExtra(ReminderController.OPEN_HOME, false)) {
            intent.removeExtra(ReminderController.OPEN_HOME)
            notificationOpen.intValue++
        }
    }
}
