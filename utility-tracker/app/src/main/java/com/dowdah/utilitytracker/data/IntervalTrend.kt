package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/** One complete reading interval. The chart's average is not a measured daily value. */
data class IntervalTrendPoint(
    val start: Instant,
    val end: Instant,
    val statistics: MeterStatistics,
    val elapsedDays: BigDecimal?,
    val averagePerDay: BigDecimal?,
) {
    val hasZeroDuration: Boolean get() = elapsedDays == null
}

/** Match statisticsForRange's later-reading attribution without allocating use to calendar days. */
fun intervalTrendForRange(
    readings: List<ReadingEntity>,
    tariffs: List<TariffEntity>,
    startUtc: String?,
    endUtc: String?,
    recharges: List<RechargeEntity> = emptyList(),
): List<IntervalTrendPoint> {
    val rangeStart = startUtc?.takeIf(String::isNotBlank)?.let(Instant::parse)
    val rangeEnd = endUtc?.takeIf(String::isNotBlank)?.let(Instant::parse)
    return readings.filterNot { it.deleted }.groupBy { it.meterId }.flatMap { (_, rows) ->
        rows.sortedBy { Instant.parse(it.recordedAt) }.zipWithNext().mapNotNull { (previous, current) ->
            val earlier = Instant.parse(previous.recordedAt)
            val later = Instant.parse(current.recordedAt)
            if ((rangeStart != null && later < rangeStart) || (rangeEnd != null && later > rangeEnd)) return@mapNotNull null

            val statistics = statisticsForRange(listOf(previous, current), tariffs, current.recordedAt, current.recordedAt, recharges)
            val elapsed = Duration.between(earlier, later)
            val seconds = BigDecimal.valueOf(elapsed.seconds).add(BigDecimal.valueOf(elapsed.nano.toLong()).movePointLeft(9))
            val days = seconds.takeIf { it.signum() > 0 }?.divide(SECONDS_PER_DAY, 12, RoundingMode.HALF_EVEN)
            val average = if (seconds.signum() > 0) statistics.consumption?.multiply(SECONDS_PER_DAY)?.divide(seconds, 12, RoundingMode.HALF_EVEN) else null
            IntervalTrendPoint(earlier, later, statistics, days, average)
        }
    }.sortedBy { it.end }
}

private val SECONDS_PER_DAY = BigDecimal.valueOf(86_400)
