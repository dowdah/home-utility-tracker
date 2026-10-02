package com.dowdah.utilitytracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.BalanceForecast
import com.dowdah.utilitytracker.data.ForecastIssue
import java.math.BigDecimal
import java.math.RoundingMode

internal fun BigDecimal.forecastDisplay() = setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@Composable
internal fun ForecastCard(forecast: BalanceForecast) {
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.forecast_title), style = MaterialTheme.typography.titleSmall)
            forecast.issue?.let { issue ->
                Text(stringResource(when (issue) {
                    ForecastIssue.NO_READING -> R.string.forecast_no_reading
                    ForecastIssue.INSUFFICIENT_HISTORY -> R.string.forecast_insufficient
                    ForecastIssue.CONFLICT -> R.string.forecast_conflict
                    ForecastIssue.FUTURE_READING -> R.string.forecast_future
                    ForecastIssue.AMBIGUOUS_READING -> R.string.forecast_ambiguous
                    ForecastIssue.INVALID_INTERVAL -> R.string.forecast_invalid
                    ForecastIssue.STALE_READING -> R.string.forecast_stale
                }))
            }
            forecast.remaining?.let { remaining ->
                Text(stringResource(R.string.forecast_remaining, remaining.forecastDisplay(), forecast.meter.unit))
                Text(when {
                    remaining.signum() == 0 -> stringResource(R.string.forecast_depleted)
                    forecast.daysLeft == null -> stringResource(R.string.forecast_zero_use)
                    forecast.daysLeft < BigDecimal.ONE -> stringResource(R.string.forecast_less_day)
                    else -> stringResource(R.string.forecast_days, forecast.daysLeft.forecastDisplay())
                }, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.forecast_basis, requireNotNull(forecast.coverageDays).forecastDisplay(), requireNotNull(forecast.dailyUse).forecastDisplay(), forecast.meter.unit), style = MaterialTheme.typography.bodySmall)
            }
            forecast.latest?.let { Text(stringResource(R.string.forecast_reading_date, it.recordedAt.localDisplay()), style = MaterialTheme.typography.bodySmall) }
            if (forecast.oldReading) Text(stringResource(R.string.forecast_old), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.forecast_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
