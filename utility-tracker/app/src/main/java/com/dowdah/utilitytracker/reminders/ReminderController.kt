package com.dowdah.utilitytracker.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dowdah.utilitytracker.MainActivity
import com.dowdah.utilitytracker.R
import com.dowdah.utilitytracker.data.ForecastRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ReminderController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: ForecastRepository,
    private val device: ReminderDeviceStore,
) {
    private val mutex = Mutex()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun createChannel() = manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
    fun permitted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    @SuppressLint("MissingPermission") // Checked immediately before posting; revocation is also caught.
    suspend fun evaluate(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ReminderAction = mutex.withLock {
        createChannel()
        val snapshot = repository.snapshot()
        val low = snapshot.forecasts(now).filter { it.isLow(snapshot.setting(it.meter.id)) }
        val local = now.atZone(zone)
        val current = device.state.value
        val active = manager.activeNotifications.any { it.id == NOTIFICATION_ID }
        val action = reminderAction(current.enabled, permitted(), low.isNotEmpty(), snapshot.schedule, local, current.notifiedDates, active)
        if (action == ReminderAction.CANCEL) manager.cancel(NOTIFICATION_ID)
        if (action == ReminderAction.ALERT || action == ReminderAction.UPDATE) {
            fun number(value: BigDecimal) = value.setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            val lines = low.map { forecast ->
                val meter = context.getString(when(forecast.meter.meterType) { "ELECTRICITY" -> R.string.electricity; "COLD_WATER" -> R.string.cold_water; else -> R.string.hot_water })
                val days = when {
                    forecast.remaining!!.signum() == 0 -> context.getString(R.string.forecast_depleted)
                    forecast.daysLeft == null -> context.getString(R.string.forecast_remaining,number(forecast.remaining),forecast.meter.unit)
                    forecast.daysLeft < BigDecimal.ONE -> context.getString(R.string.forecast_less_day)
                    else -> context.getString(R.string.forecast_days,number(forecast.daysLeft))
                }
                val old = if (forecast.oldReading) " · " + context.getString(R.string.reminder_old_date,
                    Instant.parse(forecast.latest!!.recordedAt).atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(context.resources.configuration.locales[0]))) else ""
                "$meter: $days$old"
            }
            val intent = Intent(context,MainActivity::class.java).putExtra(OPEN_HOME,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context,NOTIFICATION_ID,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = context.getString(R.string.reminder_title)
            val notification = NotificationCompat.Builder(context,CHANNEL)
                .setSmallIcon(R.drawable.ic_balance_notification).setContentTitle(title)
                .setContentText(lines.joinToString(" · ")).setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(action == ReminderAction.UPDATE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_balance_notification).setContentTitle(title).setContentText(context.getString(R.string.reminder_open)).build())
                .build()
            // Reserve before posting to avoid a second audible alert after a process crash.
            val day = local.toLocalDate().toString()
            if (action == ReminderAction.ALERT) device.update { it.copy(notifiedDates = it.notifiedDates + day) }
            try { manager.notify(NOTIFICATION_ID,notification) }
            catch (_: SecurityException) {
                if (action == ReminderAction.ALERT) device.update { it.copy(notifiedDates = it.notifiedDates - day) }
            }
        }
        action
    }
    companion object {
        const val CHANNEL = "low-balance"
        const val NOTIFICATION_ID = 1301
        const val OPEN_HOME = "balance-reminder-open-home"
    }
}
