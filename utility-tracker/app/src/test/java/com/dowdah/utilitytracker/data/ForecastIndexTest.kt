package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.time.Instant
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.Assert.*
import org.junit.Test

class ForecastIndexTest {
    private fun same(expected: BalanceForecast, actual: BalanceForecast) {
        assertEquals(expected.meter, actual.meter)
        assertEquals(expected.latest, actual.latest)
        assertEquals(expected.issue, actual.issue)
        assertEquals(expected.oldReading, actual.oldReading)
        listOf(expected.coverageDays to actual.coverageDays, expected.dailyUse to actual.dailyUse,
            expected.remaining to actual.remaining, expected.daysLeft to actual.daysLeft).forEach { (left, right) ->
            if (left == null) assertNull(right) else assertEquals(0, left.compareTo(requireNotNull(right)))
        }
    }

    @Test fun indexedForecastsMatchFrozenImplementationAcrossBoundariesAndInvalidIntervals() {
        val random = Random(1838)
        val base = Instant.parse("2026-01-01T00:00:00Z")
        for (type in listOf("ELECTRICITY", "COLD_WATER", "HOT_WATER")) repeat(35) { sample ->
            val meter = MeterEntity("m", type, 1, "unit", true, false, 1)
            val rows = (0..24).map { index ->
                ReadingEntity("r$index", "m", (100 + random.nextInt(20)).toString(),
                    base.plusSeconds(index * 86_400L * 9).toString(), null, index % 13 == 4, 1)
            }.toMutableList()
            if (sample % 3 == 0) rows += rows.last().copy(id = "duplicate", valueDecimal = if (sample % 2 == 0) rows.last().valueDecimal + ".0" else "99")
            rows += rows.last().copy(id = "other", meterId = "other")
            val credits = (0..240).map { index ->
                RechargeEntity("c$index", "m", "2", "1", listOf("0", "1.000000000001", "2", "0.125")[random.nextInt(4)], "CNY",
                    base.plusSeconds(index * 86_400L).plusNanos((index % 3).toLong()).toString(), null, index % 17 == 0, 1)
            }.toMutableList()
            credits += credits[24].copy(id = "equal-a", creditedAt = rows.last { it.meterId == "m" }.recordedAt)
            credits += credits.last().copy(id = "equal-b")
            credits += credits.last().copy(id = "other-credit", meterId = "other", quantityDecimal = "999999")
            credits[2] = credits[2].copy(creditedAt = Instant.parse(credits[2].creditedAt).atOffset(java.time.ZoneOffset.ofHours(8)).toString())
            rows.shuffle(random); credits.shuffle(random)
            val now = base.plusSeconds((216L + listOf(-1, 0, 1, 7, 30, 90, 91)[sample % 7]) * 86_400)
            val conflicted = sample % 11 == 0
            same(referenceBalanceForecast(meter, rows, credits, conflicted, now), balanceForecast(meter, rows, credits, conflicted, now))
        }
    }

    @Test fun tenYearThreeMeterLedgerPreservesResultsAndReportsJvmTiming() {
        val base = Instant.parse("2016-01-01T00:00:00Z")
        val meters = listOf("ELECTRICITY", "COLD_WATER", "HOT_WATER").map { MeterEntity(it, it, 1, "unit", true, false, 1) }
        val rows = meters.flatMap { meter -> (0..3652).map { day ->
            ReadingEntity("${meter.id}-$day", meter.id, "100", base.plusSeconds(day * 86_400L).toString(), null, false, 1)
        } }
        val credits = meters.flatMap { meter -> (1..3652).map { day ->
            RechargeEntity("${meter.id}-$day", meter.id, "10", "1", "10", "CNY", base.plusSeconds(day * 86_400L).toString(), null, false, 1)
        } }
        assertEquals(10_959, rows.size); assertEquals(10_956, credits.size)
        val now = base.plusSeconds(3652 * 86_400L + 43_200)
        fun original() = meters.map { referenceBalanceForecast(it, rows, credits, false, now) }
        fun indexed() = meters.map { balanceForecast(it, rows, credits, false, now) }
        original().zip(indexed()).forEach { (expected, actual) -> same(expected, actual) }
        val oldTimes = List(3) { measureNanoTime { original() } / 1_000_000.0 }
        val newTimes = List(3) { measureNanoTime { indexed() } / 1_000_000.0 }
        println("Forecast JVM 10959 readings/10956 credits: original_ms=$oldTimes indexed_ms=$newTimes")
        indexed().forEach { result ->
            assertEquals(0, BigDecimal("10").compareTo(result.dailyUse))
            assertEquals(0, BigDecimal("95").compareTo(result.remaining))
        }
    }
}
