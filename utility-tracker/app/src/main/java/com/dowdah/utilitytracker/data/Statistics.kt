package com.dowdah.utilitytracker.data

import java.math.BigDecimal

data class ConsumptionSummary(val consumption: BigDecimal, val hasNegativeInterval: Boolean)

/** End-of-interval aggregation: only intervals whose later reading falls inside the selected range count. */
fun consumptionForRange(readings: List<ReadingEntity>, startUtc: String?, endUtc: String?): ConsumptionSummary {
    val ordered = readings.filter { !it.deleted }.sortedBy { it.recordedAt }
    var total = BigDecimal.ZERO
    var negative = false
    ordered.zipWithNext().forEach { (previous, current) ->
        if ((startUtc.isNullOrBlank() || current.recordedAt >= startUtc) && (endUtc.isNullOrBlank() || current.recordedAt <= endUtc)) {
            val difference = current.valueDecimal.toBigDecimal().subtract(previous.valueDecimal.toBigDecimal())
            if (difference < BigDecimal.ZERO) negative = true else total = total.add(difference)
        }
    }
    return ConsumptionSummary(total, negative)
}
