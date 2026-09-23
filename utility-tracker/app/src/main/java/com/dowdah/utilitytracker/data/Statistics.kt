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
    val credits = recharges.filter { !it.deleted }
    val activeTariffs = tariffs.filter { !it.deleted }
    var consumption = BigDecimal.ZERO
    var cost = BigDecimal.ZERO
    var usableIntervals = 0
    var increased = false
    var missingRate = false
    var estimated = false
    readings.filter { !it.deleted }.groupBy { it.meterId }.forEach { (meterId, rows) ->
        val rates = activeTariffs.filter { it.meterId == meterId }.sortedBy { Instant.parse(it.effectiveFrom) }
        rows.sortedBy { Instant.parse(it.recordedAt) }.zipWithNext().forEach { (previous, current) ->
            val earlier = Instant.parse(previous.recordedAt)
            val later = Instant.parse(current.recordedAt)
            if ((start == null || later >= start) && (end == null || later <= end)) {
                val added = credits.filter { it.meterId == meterId && Instant.parse(it.creditedAt) > earlier && Instant.parse(it.creditedAt) <= later }.fold(BigDecimal.ZERO) { total, credit -> total + credit.quantityDecimal.toBigDecimal() }
                val delta = previous.valueDecimal.toBigDecimal() + added - current.valueDecimal.toBigDecimal()
                if (delta.signum() < 0) {
                    increased = true
                } else {
                    usableIntervals++
                    consumption += delta
                    val initialRate = rates.lastOrNull { Instant.parse(it.effectiveFrom) <= earlier }
                    val rate = rates.lastOrNull { Instant.parse(it.effectiveFrom) <= later }
                    if (initialRate == null || rate == null) {
                        missingRate = true
                    } else {
                        cost += delta * rate.priceDecimal.toBigDecimal()
                        if (delta.signum() > 0 && rates.any {
                                val effective = Instant.parse(it.effectiveFrom)
                                effective > earlier && effective <= later &&
                                    it.priceDecimal.toBigDecimal().compareTo(initialRate.priceDecimal.toBigDecimal()) != 0
                            }) estimated = true
                    }
                }
            }
        }
    }
    return MeterStatistics(
        consumption = consumption.takeIf { usableIntervals > 0 },
        cost = cost.takeIf { usableIntervals > 0 && !increased && !missingRate },
        hasIncrease = increased,
        hasTariffs = activeTariffs.isNotEmpty(),
        hasMissingTariff = missingRate,
        costEstimated = estimated,
        rechargeSpend = credits.filter { (start == null || Instant.parse(it.creditedAt) >= start) && (end == null || Instant.parse(it.creditedAt) <= end) }.fold(BigDecimal.ZERO) { total, credit -> total + credit.amountDecimal.toBigDecimal() },
        creditedQuantity = credits.filter { (start == null || Instant.parse(it.creditedAt) >= start) && (end == null || Instant.parse(it.creditedAt) <= end) }.fold(BigDecimal.ZERO) { total, credit -> total + credit.quantityDecimal.toBigDecimal() },
    )
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
