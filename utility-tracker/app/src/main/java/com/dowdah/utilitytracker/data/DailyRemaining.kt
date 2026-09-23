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
    val rows = readings.filter { !it.deleted && it.meterId == meterId }
        .sortedBy { Instant.parse(it.recordedAt) }
    if (rows.isEmpty()) return emptyList()
    val credits = recharges.filter { !it.deleted && it.meterId == meterId }
    val firstDay = startUtc?.takeIf(String::isNotBlank)?.let { Instant.parse(it).atZone(zone).toLocalDate() }
        ?: Instant.parse(rows.first().recordedAt).atZone(zone).toLocalDate()
    val lastDay = endUtc?.takeIf(String::isNotBlank)?.let { Instant.parse(it).atZone(zone).toLocalDate() }
        ?: Instant.parse(rows.last().recordedAt).atZone(zone).toLocalDate()
    if (lastDay < firstDay) return emptyList()

    val actualByDay = rows.groupBy { Instant.parse(it.recordedAt).atZone(zone).toLocalDate() }
        .mapValues { (_, sameDay) -> sameDay.last() }
    val creditByDay = credits.groupBy { Instant.parse(it.creditedAt).atZone(zone).toLocalDate() }
    val readingsById = rows.withIndex().associate { it.value.id to it.index }
    val result = ArrayList<DailyRemainingPoint>()
    var date = firstDay
    var earlierIndex = -1
    while (date <= lastDay) {
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
        while (earlierIndex + 1 < rows.size && Instant.parse(rows[earlierIndex + 1].recordedAt) <= dayEnd) earlierIndex++
        val dayCredits = creditByDay[date].orEmpty()
        val quantity = dayCredits.fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantityDecimal.toBigDecimal() }
        val actual = actualByDay[date]
        val point = if (actual != null) {
            val index = readingsById.getValue(actual.id)
            val previous = rows.getOrNull(index - 1)
            val unknownBefore = previous != null && intervalConsumption(previous, actual, credits) == null
            DailyRemainingPoint(date, actual.valueDecimal.toBigDecimal(), RemainingSource.ACTUAL,
                recordedAt = Instant.parse(actual.recordedAt), rechargeCount = dayCredits.size,
                rechargeQuantity = quantity, breakBefore = unknownBefore)
        } else {
            val previous = rows.getOrNull(earlierIndex)
            val next = rows.getOrNull(earlierIndex + 1)
            val estimate = if (previous != null && next != null) estimateRemaining(previous, next, dayEnd, credits) else null
            DailyRemainingPoint(date, estimate?.first, if (estimate?.first == null) RemainingSource.UNAVAILABLE else RemainingSource.ESTIMATED,
                gap = if (estimate?.first == null) estimate?.second ?: RemainingGap.NO_BOUNDING_READINGS else null,
                rechargeCount = dayCredits.size, rechargeQuantity = quantity)
        }
        result += point
        date = date.plusDays(1)
    }
    return result
}

private fun intervalConsumption(previous: ReadingEntity, current: ReadingEntity, credits: List<RechargeEntity>): BigDecimal? {
    val start = Instant.parse(previous.recordedAt)
    val end = Instant.parse(current.recordedAt)
    if (end <= start) return null
    val added = credits.filter { Instant.parse(it.creditedAt) > start && Instant.parse(it.creditedAt) <= end }
        .fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantityDecimal.toBigDecimal() }
    return (previous.valueDecimal.toBigDecimal() + added - current.valueDecimal.toBigDecimal()).takeIf { it.signum() >= 0 }
}

private fun estimateRemaining(
    previous: ReadingEntity,
    current: ReadingEntity,
    at: Instant,
    credits: List<RechargeEntity>,
): Pair<BigDecimal?, RemainingGap?> {
    val start = Instant.parse(previous.recordedAt)
    val end = Instant.parse(current.recordedAt)
    if (end <= start) return null to RemainingGap.ZERO_DURATION
    val consumption = intervalConsumption(previous, current, credits) ?: return null to RemainingGap.UNKNOWN_CONSUMPTION
    val elapsed = decimalSeconds(Duration.between(start, at))
    val total = decimalSeconds(Duration.between(start, end))
    val consumed = consumption.multiply(elapsed).divide(total, 12, RoundingMode.HALF_EVEN)
    val added = credits.filter { Instant.parse(it.creditedAt) > start && Instant.parse(it.creditedAt) <= at }
        .fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantityDecimal.toBigDecimal() }
    val estimate = previous.valueDecimal.toBigDecimal() + added - consumed
    return if (estimate.signum() < 0) null to RemainingGap.NEGATIVE_ESTIMATE else estimate to null
}

private fun decimalSeconds(duration: Duration): BigDecimal =
    BigDecimal.valueOf(duration.seconds).add(BigDecimal.valueOf(duration.nano.toLong()).movePointLeft(9))
