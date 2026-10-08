package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class RemainingSource { ACTUAL, ESTIMATED, UNAVAILABLE }
enum class RemainingGap { NO_BOUNDING_READINGS, UNKNOWN_CONSUMPTION, ZERO_DURATION, NEGATIVE_ESTIMATE }

data class DailyRemainingPoint(
    val date: LocalDate,
    val value: BigDecimal?,
    val source: RemainingSource,
    val recordedAt: Instant? = null,
    val gap: RemainingGap? = null,
    val rechargeCount: Int = 0,
    val rechargeQuantity: BigDecimal = BigDecimal.ZERO,
    val breakBefore: Boolean = false,
)

/** Daily display values for one meter. Only days inside a pair of readings may be estimated. */
fun dailyRemainingForRange(
    meterId: String,
    readings: List<ReadingEntity>,
    recharges: List<RechargeEntity>,
    startUtc: String?,
    endUtc: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DailyRemainingPoint> {
    val rows = indexedReadings(readings.filter { it.meterId == meterId })
    if (rows.isEmpty()) return emptyList()
    val credits = indexedRecharges(recharges.filter { it.meterId == meterId })
    val firstDay = startUtc?.takeIf(String::isNotBlank)?.let { Instant.parse(it).atZone(zone).toLocalDate() }
        ?: rows.first().at.atZone(zone).toLocalDate()
    val lastDay = endUtc?.takeIf(String::isNotBlank)?.let { Instant.parse(it).atZone(zone).toLocalDate() }
        ?: rows.last().at.atZone(zone).toLocalDate()
    if (lastDay < firstDay) return emptyList()

    val actualByDay = rows.associateBy { it.at.atZone(zone).toLocalDate() }
    val creditByDay = credits.rows.groupBy { it.at.atZone(zone).toLocalDate() }
    val readingsById = rows.withIndex().associate { it.value.entity.id to it.index }
    val consumptionByInterval = rows.zipWithNext().map { (previous, current) -> intervalConsumption(previous, current, credits) }
    val result = ArrayList<DailyRemainingPoint>()
    var date = firstDay
    var earlierIndex = -1
    while (date <= lastDay) {
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
        while (earlierIndex + 1 < rows.size && rows[earlierIndex + 1].at <= dayEnd) earlierIndex++
        val dayCredits = creditByDay[date].orEmpty()
        val quantity = dayCredits.fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantity }
        val actual = actualByDay[date]
        val point = if (actual != null) {
            val index = readingsById.getValue(actual.entity.id)
            val unknownBefore = index > 0 && consumptionByInterval[index - 1] == null
            DailyRemainingPoint(date, actual.value, RemainingSource.ACTUAL,
                recordedAt = actual.at, rechargeCount = dayCredits.size,
                rechargeQuantity = quantity, breakBefore = unknownBefore)
        } else {
            val previous = rows.getOrNull(earlierIndex)
            val next = rows.getOrNull(earlierIndex + 1)
            val estimate = if (previous != null && next != null) {
                estimateRemaining(previous, next, dayEnd, credits, consumptionByInterval[earlierIndex])
            } else null
            DailyRemainingPoint(date, estimate?.first, if (estimate?.first == null) RemainingSource.UNAVAILABLE else RemainingSource.ESTIMATED,
                gap = if (estimate?.first == null) estimate?.second ?: RemainingGap.NO_BOUNDING_READINGS else null,
                rechargeCount = dayCredits.size, rechargeQuantity = quantity)
        }
        result += point
        date = date.plusDays(1)
    }
    return result
}

private fun intervalConsumption(previous: StatisticsReading, current: StatisticsReading, credits: RechargeTimeline): BigDecimal? {
    val start = previous.at
    val end = current.at
    if (end <= start) return null
    val added = credits.quantityAfter(start, end)
    return (previous.value + added - current.value).takeIf { it.signum() >= 0 }
}

private fun estimateRemaining(
    previous: StatisticsReading,
    current: StatisticsReading,
    at: Instant,
    credits: RechargeTimeline,
    intervalConsumption: BigDecimal?,
): Pair<BigDecimal?, RemainingGap?> {
    val start = previous.at
    val end = current.at
    if (end <= start) return null to RemainingGap.ZERO_DURATION
    val consumption = intervalConsumption ?: return null to RemainingGap.UNKNOWN_CONSUMPTION
    val elapsed = decimalSeconds(Duration.between(start, at))
    val total = decimalSeconds(Duration.between(start, end))
    val consumed = consumption.multiply(elapsed).divide(total, 12, RoundingMode.HALF_EVEN)
    val added = credits.quantityAfter(start, at)
    val estimate = previous.value + added - consumed
    return if (estimate.signum() < 0) null to RemainingGap.NEGATIVE_ESTIMATE else estimate to null
}

private fun decimalSeconds(duration: Duration): BigDecimal =
    BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong()).movePointLeft(9))
