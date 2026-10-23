package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Known consumption is a subtotal when a remaining reading increases. Null means no usable interval. */
data class MeterStatistics(
    val consumption: BigDecimal?,
    val cost: BigDecimal?,
    val hasIncrease: Boolean,
    val hasTariffs: Boolean,
    val hasMissingTariff: Boolean,
    val costEstimated: Boolean,
    val rechargeSpend: BigDecimal = BigDecimal.ZERO,
    val creditedQuantity: BigDecimal = BigDecimal.ZERO,
)

/**
 * Readings are remaining balances. Attribute each interval to its later reading, using that
 * reading's effective tariff. A tariff change inside the interval makes the cost an estimate.
 * Without a rate covering the start, the interval cannot be fully priced. No top-ups are inferred.
 */
fun statisticsForRange(
    readings: List<ReadingEntity>,
    tariffs: List<TariffEntity>,
    startUtc: String?,
    endUtc: String?,
    recharges: List<RechargeEntity> = emptyList(),
): MeterStatistics {
    val start = startUtc?.takeIf { it.isNotBlank() }?.let(Instant::parse)
    val end = endUtc?.takeIf { it.isNotBlank() }?.let(Instant::parse)
    val ledger = StatisticsLedgerIndex(readings, tariffs, recharges)
    var consumption = BigDecimal.ZERO
    var cost = BigDecimal.ZERO
    var usableIntervals = 0
    var increased = false
    var missingRate = false
    var estimated = false
    ledger.readingsByMeter.values.forEach { rows ->
        rows.zipWithNext().forEach { (previous, current) ->
            val later = current.at
            if ((start == null || later >= start) && (end == null || later <= end)) {
                val interval = ledger.intervalStatistics(previous, current)
                increased = increased || interval.hasIncrease
                missingRate = missingRate || interval.hasMissingTariff
                estimated = estimated || interval.costEstimated
                if (interval.consumption != null) {
                    usableIntervals++
                    consumption += interval.consumption
                    cost += interval.cost ?: BigDecimal.ZERO
                }
            }
        }
    }
    return MeterStatistics(
        consumption = consumption.takeIf { usableIntervals > 0 },
        cost = cost.takeIf { usableIntervals > 0 && !increased && !missingRate },
        hasIncrease = increased,
        hasTariffs = ledger.hasTariffs,
        hasMissingTariff = missingRate,
        costEstimated = estimated,
        rechargeSpend = ledger.credits.amountInRange(start, end),
        creditedQuantity = ledger.credits.quantityInRange(start, end),
    )
}

internal data class StatisticsReading(val entity: ReadingEntity, val at: Instant, val value: BigDecimal)

internal fun indexedReadings(readings: List<ReadingEntity>): List<StatisticsReading> =
    readings.filterNot { it.deleted }.map { StatisticsReading(it, Instant.parse(it.recordedAt), it.valueDecimal.toBigDecimal()) }
        .sortedBy { it.at }

internal data class StatisticsRecharge(val entity: RechargeEntity, val at: Instant, val quantity: BigDecimal, val amount: BigDecimal)

/** Sorted once per calculation; binary boundaries preserve (previous, current] credit attribution. */
internal class RechargeTimeline(val rows: List<StatisticsRecharge>) {
    private val quantities = rows.runningFold(BigDecimal.ZERO) { sum, row -> sum + row.quantity }
    private val amounts = rows.runningFold(BigDecimal.ZERO) { sum, row -> sum + row.amount }

    fun quantityAfter(start: Instant, end: Instant): BigDecimal =
        sum(quantities, rows.timeBound(start, true) { it.at }, rows.timeBound(end, true) { it.at })

    fun quantityInRange(start: Instant?, end: Instant?): BigDecimal =
        sum(quantities, start?.let { at -> rows.timeBound(at, false) { it.at } } ?: 0,
            end?.let { at -> rows.timeBound(at, true) { it.at } } ?: rows.size)

    fun amountInRange(start: Instant?, end: Instant?): BigDecimal =
        sum(amounts, start?.let { at -> rows.timeBound(at, false) { it.at } } ?: 0,
            end?.let { at -> rows.timeBound(at, true) { it.at } } ?: rows.size)

    private fun sum(prefix: List<BigDecimal>, from: Int, to: Int): BigDecimal =
        if (to <= from) BigDecimal.ZERO else prefix[to] - prefix[from]
}

internal fun indexedRecharges(recharges: List<RechargeEntity>): RechargeTimeline = RechargeTimeline(
    recharges.filterNot { it.deleted }.map {
        StatisticsRecharge(it, Instant.parse(it.creditedAt), it.quantityDecimal.toBigDecimal(), it.amountDecimal.toBigDecimal())
    }.sortedBy { it.at },
)

/** First index >= time, or > time when [after] is true. Stable sort resolves equal timestamps. */
private inline fun <T> List<T>.timeBound(time: Instant, after: Boolean, timestamp: (T) -> Instant): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high).ushr(1)
        val value = timestamp(this[mid])
        if (value < time || (after && value == time)) low = mid + 1 else high = mid
    }
    return low
}

private data class StatisticsTariff(val at: Instant, val price: BigDecimal)

private class TariffTimeline(val rows: List<StatisticsTariff>) {
    // Count price changes, including transient changes that return to the starting price.
    private val changes = IntArray(rows.size).also { counts ->
        for (index in 1 until rows.size) {
            counts[index] = counts[index - 1] + if (rows[index].price.compareTo(rows[index - 1].price) == 0) 0 else 1
        }
    }

    fun at(time: Instant): Int = rows.timeBound(time, true) { it.at } - 1
    fun changed(from: Int, to: Int): Boolean = changes[to] != changes[from]
}

/** Shared by summary and trend so a long trend never reparses/rescans the ledger per interval. */
internal class StatisticsLedgerIndex(readings: List<ReadingEntity>, tariffs: List<TariffEntity>, recharges: List<RechargeEntity>) {
    val readingsByMeter = readings.filterNot { it.deleted }.groupBy { it.meterId }.mapValues { indexedReadings(it.value) }
    val credits = indexedRecharges(recharges)
    private val creditsByMeter = credits.rows.groupBy { it.entity.meterId }.mapValues { RechargeTimeline(it.value) }
    private val ratesByMeter = tariffs.filterNot { it.deleted }.groupBy { it.meterId }.mapValues { (_, rows) ->
        TariffTimeline(rows.map { StatisticsTariff(Instant.parse(it.effectiveFrom), it.priceDecimal.toBigDecimal()) }.sortedBy { it.at })
    }
    val hasTariffs = ratesByMeter.isNotEmpty()

    fun intervalStatistics(previous: StatisticsReading, current: StatisticsReading): MeterStatistics {
        val meterId = current.entity.meterId
        val added = creditsByMeter[meterId]?.quantityAfter(previous.at, current.at) ?: BigDecimal.ZERO
        val delta = previous.value + added - current.value
        val usable = delta.signum() >= 0
        val rates = ratesByMeter[meterId]
        val initialIndex = rates?.at(previous.at) ?: -1
        val finalIndex = rates?.at(current.at) ?: -1
        val priced = rates != null && initialIndex >= 0 && finalIndex >= 0
        return MeterStatistics(
            consumption = delta.takeIf { usable },
            cost = if (usable && priced) delta * rates!!.rows[finalIndex].price else null,
            hasIncrease = !usable,
            hasTariffs = hasTariffs,
            hasMissingTariff = usable && !priced,
            costEstimated = usable && priced && delta.signum() > 0 && rates!!.changed(initialIndex, finalIndex),
            // Existing per-interval details count purchases at the later reading timestamp.
            rechargeSpend = credits.amountInRange(current.at, current.at),
            creditedQuantity = credits.quantityInRange(current.at, current.at),
        )
    }
}

/** Compare the draft with its chronological neighbours, including backfills and edits. */
fun remainingReadingIncreases(
    readings: List<ReadingEntity>, id: String?, meterId: String, value: String, recordedAt: String,
    recharges: List<RechargeEntity> = emptyList(),
): Boolean {
    val amount = value.toBigDecimalOrNull() ?: return false
    val time = Instant.parse(recordedAt)
    val others = readings.filter { !it.deleted && it.meterId == meterId && it.id != id }
        .sortedBy { Instant.parse(it.recordedAt) }
    val previous = others.lastOrNull { Instant.parse(it.recordedAt) <= time }
    val next = others.firstOrNull { Instant.parse(it.recordedAt) > time }
    fun added(from: Instant, to: Instant) = recharges.filter { !it.deleted && it.meterId == meterId && Instant.parse(it.creditedAt) > from && Instant.parse(it.creditedAt) <= to }.fold(BigDecimal.ZERO) { total, credit -> total + credit.quantityDecimal.toBigDecimal() }
    return (previous != null && amount > previous.valueDecimal.toBigDecimal() + added(Instant.parse(previous.recordedAt), time)) ||
        (next != null && next.valueDecimal.toBigDecimal() > amount + added(time, Instant.parse(next.recordedAt)))
}

fun defaultReadingMeterId(meters: List<MeterEntity>): String =
    meters.firstOrNull { it.active && !it.deleted && it.meterType == "ELECTRICITY" }?.id.orEmpty()

/** Purchase quantity is a historical snapshot, never recalculated from a later tariff. */
fun rechargeQuantity(amount: String, price: String): BigDecimal {
    require(amount.length <= 128 && price.length <= 128) { "Number exceeds supported precision" }
    val money = amount.toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a positive amount and purchase price")
    val rate = price.toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a positive amount and purchase price")
    require(money.signum() > 0 && rate.signum() > 0) { "Enter a positive amount and purchase price" }
    require(listOf(money, rate).all { it.scale() <= 24 && it.precision() - it.scale() <= 25 }) { "Number exceeds supported precision" }
    val quantity = money.divide(rate, 12, RoundingMode.HALF_EVEN)
    require(quantity.signum() > 0 && quantity.precision() - quantity.scale() <= 25) { "Number exceeds supported precision" }
    return quantity
}
