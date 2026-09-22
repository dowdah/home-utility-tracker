package com.dowdah.utilitytracker.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dowdah.utilitytracker.R

private enum class Destination(val title: Int) {
    HOME(R.string.home), RECORDS(R.string.records), STATISTICS(R.string.statistics), SETTINGS(R.string.settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtilityTrackerApp(appViewModel: AppViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val navEntry by navController.currentBackStackEntryAsState()
    val secondary = navEntry?.destination?.route?.let { it != "main" } ?: false
    var destinationName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
    val destination = Destination.valueOf(destinationName)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val select: (Destination) -> Unit = { destinationName = it.name }
    val content: @Composable () -> Unit = {
        NavHost(navController, startDestination = "main") {
            composable("main") { when (destination) {
                Destination.HOME -> HomeScreen(appViewModel)
                Destination.RECORDS -> RecordsScreen(appViewModel)
                Destination.STATISTICS -> StatisticsScreen(appViewModel)
                Destination.SETTINGS -> SettingsScreen(appViewModel, onEndpoints = { navController.navigate("endpoints") }, onTariffs = { navController.navigate("tariffs") }, onConflicts = { navController.navigate("conflicts") }, onExport = { navController.navigate("export") })
            } }
            composable("endpoints") { EndpointScreen(onBack = { navController.popBackStack() }) }
            composable("tariffs") { TariffScreen(appViewModel, onBack = { navController.popBackStack() }) }
            composable("conflicts") { ConflictScreen(appViewModel, onBack = { navController.popBackStack() }) }
            composable("export") { ExportScreen(appViewModel, onBack = { navController.popBackStack() }) }
        }
    }
    if (secondary) {
        content()
    } else if (landscape) {
        Row(Modifier.fillMaxSize()) {
            AppRail(destination, select)
            Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(destination.title)) }, actions = { if (destination == Destination.HOME || destination == Destination.RECORDS) IconButton(appViewModel::sync) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } }) }) { padding ->
                Column(Modifier.padding(padding).padding(horizontal = 24.dp).widthIn(max = 1100.dp)) { content() }
            }
        }
    } else {
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(destination.title)) }, actions = { if (destination == Destination.HOME || destination == Destination.RECORDS) IconButton(appViewModel::sync) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors()) },
            bottomBar = { AppBar(destination, select) },
        ) { padding -> Column(Modifier.padding(padding).padding(horizontal = 16.dp)) { content() } }
    }
}

@Composable private fun AppRail(selected: Destination, select: (Destination) -> Unit) = NavigationRail {
    Destination.entries.forEach { destination -> NavigationRailItem(selected = selected == destination, onClick = { select(destination) }, icon = { DestinationIcon(destination) }, label = { Text(stringResource(destination.title)) }) }
}
@Composable private fun AppBar(selected: Destination, select: (Destination) -> Unit) = NavigationBar {
    Destination.entries.forEach { destination -> NavigationBarItem(selected = selected == destination, onClick = { select(destination) }, icon = { DestinationIcon(destination) }, label = { Text(stringResource(destination.title)) }) }
}
@Composable private fun DestinationIcon(destination: Destination) = Icon(when (destination) {
    Destination.HOME -> Icons.Default.Home; Destination.RECORDS -> Icons.Default.List; Destination.STATISTICS -> Icons.Default.BarChart; Destination.SETTINGS -> Icons.Default.Settings
}, contentDescription = null)
