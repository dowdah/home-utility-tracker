package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.time.Instant

/** Known consumption is a subtotal when a remaining reading increases. Null means no usable interval. */
data class MeterStatistics(
    val consumption: BigDecimal?,
    val cost: BigDecimal?,
    val hasIncrease: Boolean,
    val hasTariffs: Boolean,
    val hasMissingTariff: Boolean,
    val costEstimated: Boolean,
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
): MeterStatistics {
    val start = startUtc?.takeIf { it.isNotBlank() }?.let(Instant::parse)
    val end = endUtc?.takeIf { it.isNotBlank() }?.let(Instant::parse)
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
                val delta = previous.valueDecimal.toBigDecimal() - current.valueDecimal.toBigDecimal()
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
    )
}

/** Compare the draft with its chronological neighbours, including backfills and edits. */
fun remainingReadingIncreases(
    readings: List<ReadingEntity>, id: String?, meterId: String, value: String, recordedAt: String,
): Boolean {
    val amount = value.toBigDecimalOrNull() ?: return false
    val time = Instant.parse(recordedAt)
    val others = readings.filter { !it.deleted && it.meterId == meterId && it.id != id }
        .sortedBy { Instant.parse(it.recordedAt) }
    val previous = others.lastOrNull { Instant.parse(it.recordedAt) <= time }
    val next = others.firstOrNull { Instant.parse(it.recordedAt) > time }
    return (previous != null && amount > previous.valueDecimal.toBigDecimal()) ||
        (next != null && next.valueDecimal.toBigDecimal() > amount)
}

fun defaultReadingMeterId(meters: List<MeterEntity>): String =
    meters.firstOrNull { it.active && !it.deleted && it.meterType == "ELECTRICITY" }?.id.orEmpty()
