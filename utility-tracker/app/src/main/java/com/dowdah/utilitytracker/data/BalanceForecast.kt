package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

enum class ForecastIssue { NO_READING, INSUFFICIENT_HISTORY, CONFLICT, FUTURE_READING, AMBIGUOUS_READING, INVALID_INTERVAL, STALE_READING }

data class BalanceForecast(
    val meter: MeterEntity,
    val latest: ReadingEntity? = null,
    val issue: ForecastIssue? = null,
    val oldReading: Boolean = false,
    val coverageDays: BigDecimal? = null,
    val dailyUse: BigDecimal? = null,
    val remaining: BigDecimal? = null,
    val daysLeft: BigDecimal? = null,
) {
    fun isLow(setting: MeterReminderEntity): Boolean = setting.enabled && issue == null && (
        daysLeft?.let { it <= BigDecimal(setting.daysThreshold) } == true ||
            setting.quantityThreshold?.toBigDecimalOrNull()?.let { threshold -> remaining?.let { it <= threshold } } == true
        )
}

/** Independent projection: never persist estimates as readings or alter official statistics. */
fun balanceForecast(
    meter: MeterEntity,
    readings: List<ReadingEntity>,
    recharges: List<RechargeEntity>,
    conflicted: Boolean,
    now: Instant,
): BalanceForecast {
    val rows = indexedReadings(readings.filter { it.meterId == meter.id })
    val latest = rows.lastOrNull()?.entity
    val base = BalanceForecast(meter, latest)
    if (conflicted) return base.copy(issue = ForecastIssue.CONFLICT)
    if (latest == null) return base.copy(issue = ForecastIssue.NO_READING)
    val at = Instant.parse(latest.recordedAt)
    if (at > now) return base.copy(issue = ForecastIssue.FUTURE_READING)
    val electric = meter.meterType == "ELECTRICITY"
    val age = Duration.between(at, now)
    val result = base.copy(oldReading = age > Duration.ofDays(if (electric) 7 else 30))
    if (age > Duration.ofDays(if (electric) 30 else 90)) return result.copy(issue = ForecastIssue.STALE_READING)
    val cutoff = at.minus(Duration.ofDays(if (electric) 30 else 180))
    val groups = rows.groupBy { it.at }
    // Keep the full boundary interval, including the last reading before the window.
    val unique = groups.values.map { it.last() }
    val relevantStart = unique.lastOrNull { it.at < cutoff }?.at ?: Instant.MIN
    if (groups.any { (time, sameTime) -> time >= relevantStart && sameTime.map { it.value.stripTrailingZeros() }.distinct().size > 1 }) {
        return result.copy(issue = ForecastIssue.AMBIGUOUS_READING)
    }
    // Parse/sort once; prefix sums and binary boundaries preserve (start, end].
    // Every queried end is <= now, so future credits stay outside the projection.
    val credits = indexedRecharges(recharges.filter { it.meterId == meter.id })
    var seconds = BigDecimal.ZERO
    var consumed = BigDecimal.ZERO
    for ((previous, current) in unique.zipWithNext().asReversed()) {
        val start = previous.at
        val end = current.at
        if (end <= cutoff) break
        val delta = previous.value + credits.quantityAfter(start, end) - current.value
        if (delta.signum() < 0) {
            if (seconds.signum() == 0) return result.copy(issue = ForecastIssue.INVALID_INTERVAL)
            break
        }
        seconds += forecastSeconds(Duration.between(start, end))
        consumed += delta
    }
    if (seconds < DAY_SECONDS * BigDecimal(if (electric) 1 else 7)) return result.copy(issue = ForecastIssue.INSUFFICIENT_HISTORY)
    val daily = consumed.multiply(DAY_SECONDS).divide(seconds, 12, RoundingMode.HALF_EVEN)
    val sinceReading = forecastSeconds(age)
    val remaining = (latest.valueDecimal.toBigDecimal() + credits.quantityAfter(at, now) -
        consumed.multiply(sinceReading).divide(seconds, 12, RoundingMode.HALF_EVEN)).max(BigDecimal.ZERO)
    return result.copy(
        coverageDays = seconds.divide(DAY_SECONDS, 12, RoundingMode.HALF_EVEN),
        dailyUse = daily,
        remaining = remaining,
        daysLeft = if (consumed.signum() > 0) remaining.multiply(seconds).divide(consumed.multiply(DAY_SECONDS), 12, RoundingMode.HALF_EVEN) else null,
    )
}

private val DAY_SECONDS = BigDecimal("86400")
private fun forecastSeconds(duration: Duration): BigDecimal =
    BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong()).movePointLeft(9))
