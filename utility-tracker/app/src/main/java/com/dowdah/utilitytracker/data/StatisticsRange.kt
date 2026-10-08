package com.dowdah.utilitytracker.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class StatisticsMode(val key: String) {
    MONTH("month"), YEAR("year"), LAST_30_DAYS("last30"), LAST_183_DAYS("last183"), ALL_TIME("all"), CUSTOM("custom");

    val endsNow: Boolean get() = this == LAST_30_DAYS || this == LAST_183_DAYS || this == ALL_TIME

    companion object {
        fun fromKey(key: String): StatisticsMode = entries.firstOrNull { it.key == key } ?: MONTH
    }
}

data class StatisticsRange(val start: Instant?, val end: Instant?, val captionStart: Instant? = start) {
    val startUtc: String? get() = start?.toString()
    val endUtc: String? get() = end?.toString()
}

/** Rolling ranges count local calendar dates, including today, and stop at the actual current time. */
fun resolveStatisticsRange(
    mode: StatisticsMode,
    anchor: LocalDate,
    customStart: String?,
    customEnd: String?,
    now: Instant,
    zone: ZoneId,
    readings: List<ReadingEntity> = emptyList(),
    recharges: List<RechargeEntity> = emptyList(),
): StatisticsRange {
    val today = now.atZone(zone).toLocalDate()
    fun calendarRange(first: LocalDate, next: LocalDate) = StatisticsRange(
        first.atStartOfDay(zone).toInstant(), next.atStartOfDay(zone).toInstant().minusNanos(1),
    )
    return when (mode) {
        StatisticsMode.MONTH -> anchor.withDayOfMonth(1).let { calendarRange(it, it.plusMonths(1)) }
        StatisticsMode.YEAR -> anchor.withDayOfYear(1).let { calendarRange(it, it.plusYears(1)) }
        StatisticsMode.LAST_30_DAYS, StatisticsMode.LAST_183_DAYS -> {
            val days = if (mode == StatisticsMode.LAST_30_DAYS) 30L else 183L
            StatisticsRange(today.minusDays(days - 1).atStartOfDay(zone).toInstant(), now)
        }
        StatisticsMode.ALL_TIME -> {
            val earliest = (readings.asSequence().filterNot { it.deleted }.map { Instant.parse(it.recordedAt) } +
                recharges.asSequence().filterNot { it.deleted }.map { Instant.parse(it.creditedAt) })
                .filter { it <= now }.minOrNull()
            StatisticsRange(null, now, earliest)
        }
        StatisticsMode.CUSTOM -> StatisticsRange(
            customStart?.takeIf(String::isNotBlank)?.let(Instant::parse),
            customEnd?.takeIf(String::isNotBlank)?.let(Instant::parse),
        )
    }
}

/** Shared Home/Statistics display order; storage and other screens retain their existing order. */
fun statisticsMeterOrder(meters: List<MeterEntity>): List<MeterEntity> = meters.sortedBy {
    when (it.meterType) { "ELECTRICITY" -> 0; "COLD_WATER" -> 1; "HOT_WATER" -> 2; else -> 3 }
}

data class StatisticsPeriod(val date: LocalDate, val statistics: MeterStatistics)
data class MeterStatisticsReport(
    val meter: MeterEntity,
    val summary: MeterStatistics,
    val months: List<StatisticsPeriod>,
    val intervals: List<IntervalTrendPoint>,
    val remaining: List<DailyRemainingPoint>,
)

data class StatisticsInput(
    val mode: StatisticsMode,
    val anchor: LocalDate,
    val range: StatisticsRange,
    val zone: ZoneId,
    val meters: List<MeterEntity>,
    val readings: List<ReadingEntity>,
    val tariffs: List<TariffEntity>,
    val recharges: List<RechargeEntity>,
)

/** Run on a computation dispatcher. Keep the range's preceding baseline and all tariff history. */
fun calculateStatistics(input: StatisticsInput): List<MeterStatisticsReport> {
    val (mode, anchor, range, zone, meters, readings, tariffs, recharges) = input
    val rowsByMeter = readings.filter { !mode.endsNow || Instant.parse(it.recordedAt) <= requireNotNull(range.end) }.groupBy { it.meterId }
    val creditsByMeter = recharges.filter { !mode.endsNow || Instant.parse(it.creditedAt) <= requireNotNull(range.end) }.groupBy { it.meterId }
    val ratesByMeter = tariffs.groupBy { it.meterId }
    return statisticsMeterOrder(meters).map { meter ->
        val rows = rowsByMeter[meter.id].orEmpty()
        val credits = creditsByMeter[meter.id].orEmpty()
        val rates = ratesByMeter[meter.id].orEmpty()
        val summary = statisticsForRange(rows, rates, range.startUtc, range.endUtc, credits)
        val months = if (mode == StatisticsMode.YEAR) (1..12).map { month ->
            val date = LocalDate.of(anchor.year, month, 1)
            StatisticsPeriod(date, statisticsForRange(rows, rates, date.atStartOfDay(zone).toInstant().toString(),
                date.plusMonths(1).atStartOfDay(zone).toInstant().minusNanos(1).toString(), credits))
        } else emptyList()
        MeterStatisticsReport(meter, summary, months,
            if (mode == StatisticsMode.YEAR) emptyList() else intervalTrendForRange(rows, rates, range.startUtc, range.endUtc, credits),
            dailyRemainingForRange(meter.id, rows, credits, range.startUtc, range.endUtc, zone))
    }
}
