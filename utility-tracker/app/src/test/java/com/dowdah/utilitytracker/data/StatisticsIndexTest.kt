package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class StatisticsIndexTest {
    private fun reading(id: String, at: Instant, value: String, meter: String = "electric") =
        ReadingEntity(id, meter, value, at.toString(), null, false, 1)

    private fun credit(id: String, at: Instant, quantity: String, meter: String = "electric") =
        RechargeEntity(id, meter, "2.50", "2", quantity, "CNY", at.toString(), null, false, 1)

    private fun tariff(id: String, at: Instant, price: String, meter: String = "electric") =
        TariffEntity(id, meter, price, "CNY", at.toString(), false, 1)

    private fun decimal(expected: BigDecimal?, actual: BigDecimal?) {
        if (expected == null) assertNull(actual) else {
            assertNotNull(actual)
            assertEquals("Expected $expected but got $actual", 0, expected.compareTo(actual))
        }
    }

    private fun sameStatistics(expected: MeterStatistics, actual: MeterStatistics) {
        decimal(expected.consumption, actual.consumption)
        decimal(expected.cost, actual.cost)
        decimal(expected.rechargeSpend, actual.rechargeSpend)
        decimal(expected.creditedQuantity, actual.creditedQuantity)
        assertEquals(expected.hasIncrease, actual.hasIncrease)
        assertEquals(expected.hasTariffs, actual.hasTariffs)
        assertEquals(expected.hasMissingTariff, actual.hasMissingTariff)
        assertEquals(expected.costEstimated, actual.costEstimated)
    }

    @Test fun `equal time rows keep stable order and transient price changes remain estimates`() {
        val start = Instant.parse("2026-10-01T00:00:00Z")
        val end = start.plusSeconds(86_400)
        val rows = listOf(reading("a", start, "100"), reading("b", end, "90"), reading("c", end, "85"))
        val rates = listOf(
            tariff("initial-a", start, "0.1"), tariff("initial-b", start, "0.5"),
            tariff("transient", start.plusSeconds(1), "0.9"),
            tariff("return", start.plusSeconds(1), "0.50"),
            tariff("final-a", end, "0.7"), tariff("final-b", end, "0.500"),
        )
        val credits = listOf(credit("left", start, "99"), credit("right-a", end, "2"), credit("right-b", end, "3"))
        val points = intervalTrendForRange(rows, rates, null, null, credits)
        assertEquals(2, points.size)
        decimal(BigDecimal("15"), points[0].statistics.consumption)
        decimal(BigDecimal("7.5"), points[0].statistics.cost)
        assertTrue(points[0].statistics.costEstimated)
        decimal(BigDecimal("5"), points[1].statistics.consumption)
        decimal(BigDecimal("2.5"), points[1].statistics.cost)
        assertFalse(points[1].statistics.costEstimated)
        assertTrue(points[1].hasZeroDuration)
        assertNull(points[1].averagePerDay)
        sameStatistics(referenceStatistics(rows, rates, null, null, credits), statisticsForRange(rows, rates, null, null, credits))
        val daily = dailyRemainingForRange("electric", rows, credits, null, null, ZoneId.of("UTC"))
        decimal(BigDecimal("85"), daily.last().value)
        assertTrue(daily.last().breakBefore)
    }

    @Test fun `indexed summaries and interval details match direct calculations across boundaries`() {
        val random = Random(713)
        val base = Instant.parse("2026-03-05T00:00:00Z")
        val rows = mutableListOf<ReadingEntity>()
        val credits = mutableListOf<RechargeEntity>()
        val rates = mutableListOf<TariffEntity>()
        for (meter in listOf("water", "electric", "hot")) {
            repeat(18) { index ->
                val time = base.plusSeconds(index / 2 * 43_201L)
                rows += reading("$meter-$index", time, (random.nextInt(80, 130) / 10.0).toString(), meter)
                    .copy(deleted = index % 11 == 5)
                credits += credit("$meter-credit-$index", time, listOf("0.100000000001", "3.25", "0", "5")[index % 4], meter)
                    .copy(deleted = index % 7 == 3)
                rates += tariff("$meter-rate-$index", time, listOf("0.50", "0.500", "0.7", "0.5")[index % 4], meter)
                    .copy(deleted = index % 9 == 2)
            }
        }
        rows.shuffle(random)
        credits.shuffle(random)
        rates.shuffle(random)
        // Equivalent offsets and subsecond boundaries must sort as instants, never as strings.
        rows[0] = rows[0].copy(recordedAt = Instant.parse(rows[0].recordedAt).atOffset(java.time.ZoneOffset.ofHours(8)).toString())
        credits += credit("fractional", base.plusNanos(1), "0.000000000001")
        val boundaries = listOf(null, base.minusNanos(1), base, base.plusNanos(1), base.plusSeconds(86_402), base.plusSeconds(500_000))
        for (start in boundaries) for (end in boundaries) {
            sameStatistics(referenceStatistics(rows, rates, start, end, credits),
                statisticsForRange(rows, rates, start?.toString(), end?.toString(), credits))
        }
        val points = intervalTrendForRange(rows, rates, null, null, credits)
        val expectedPairs = rows.filterNot { it.deleted }.groupBy { it.meterId }.values.flatMap { meterRows ->
            meterRows.sortedBy { Instant.parse(it.recordedAt) }.zipWithNext()
        }.sortedBy { Instant.parse(it.second.recordedAt) }
        expectedPairs.zip(points).forEach { (pair, point) ->
            val at = Instant.parse(pair.second.recordedAt)
            assertEquals(Instant.parse(pair.first.recordedAt), point.start)
            assertEquals(at, point.end)
            sameStatistics(referenceStatistics(listOf(pair.first, pair.second), rates, at, at, credits), point.statistics)
        }
        assertEquals(expectedPairs.size, points.size)
    }

    @Test fun `daily indexed estimates match direct day end arithmetic and preserve gaps through DST`() {
        val base = Instant.parse("2026-03-06T17:00:00Z")
        val rows = listOf(
            reading("a", base, "100"), reading("b", base.plusSeconds(5 * 86_400L), "90"),
            reading("c", base.plusSeconds(9 * 86_400L), "200"),
            reading("d", base.plusSeconds(13 * 86_400L), "180"),
        )
        val credits = listOf(
            credit("left", base, "90"), credit("inside", base.plusSeconds(86_400), "1.000000000001"),
            credit("right", Instant.parse(rows[1].recordedAt), "5"),
            credit("late", base.plusSeconds(12 * 86_400L), "40"),
        )
        val zone = ZoneId.of("America/New_York")
        val points = dailyRemainingForRange("electric", rows, credits, base.minusSeconds(86_400).toString(), base.plusSeconds(15 * 86_400L).toString(), zone)
        for (point in points) {
            val at = point.date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1)
            val actual = rows.lastOrNull { Instant.parse(it.recordedAt).atZone(zone).toLocalDate() == point.date }
            if (actual != null) {
                assertEquals(RemainingSource.ACTUAL, point.source)
                decimal(actual.valueDecimal.toBigDecimal(), point.value)
                continue
            }
            val previous = rows.lastOrNull { Instant.parse(it.recordedAt) <= at }
            val next = rows.firstOrNull { Instant.parse(it.recordedAt) > at }
            if (previous == null || next == null) {
                assertEquals(RemainingGap.NO_BOUNDING_READINGS, point.gap)
                assertNull(point.value)
                continue
            }
            val start = Instant.parse(previous.recordedAt)
            val end = Instant.parse(next.recordedAt)
            fun added(until: Instant) = credits.filter { Instant.parse(it.creditedAt) > start && Instant.parse(it.creditedAt) <= until }
                .fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantityDecimal.toBigDecimal() }
            val consumption = previous.valueDecimal.toBigDecimal() + added(end) - next.valueDecimal.toBigDecimal()
            if (consumption.signum() < 0) {
                assertEquals(RemainingGap.UNKNOWN_CONSUMPTION, point.gap)
                assertNull(point.value)
            } else {
                fun seconds(duration: Duration) = BigDecimal.valueOf(duration.seconds) + BigDecimal.valueOf(duration.nano.toLong()).movePointLeft(9)
                val consumed = consumption.multiply(seconds(Duration.between(start, at)))
                    .divide(seconds(Duration.between(start, end)), 12, RoundingMode.HALF_EVEN)
                val expected = previous.valueDecimal.toBigDecimal() + added(at) - consumed
                decimal(expected.takeIf { it.signum() >= 0 }, point.value)
                assertEquals(if (expected.signum() < 0) RemainingGap.NEGATIVE_ESTIMATE else null, point.gap)
            }
        }
    }

    @Test fun `ten years of daily records for three meters retains exact totals and bounded daily estimates`() {
        val started = System.nanoTime()
        val first = LocalDate.of(2016, 1, 1)
        val last = LocalDate.of(2026, 1, 1)
        val days = ChronoUnit.DAYS.between(first, last).toInt()
        val zone = ZoneId.of("Asia/Shanghai")
        val base = first.atTime(12, 0).atZone(zone).toInstant()
        val meters = listOf("electric", "cold", "hot")
        val rows = meters.flatMap { meter -> (0..days).map { day -> reading("$meter-$day", base.plusSeconds(day * 86_400L), "100", meter) } }
        val credits = meters.flatMap { meter -> (1..days).map { day -> credit("$meter-$day", base.plusSeconds(day * 86_400L), "1.25", meter) } }
        val rates = meters.flatMap { meter -> (0..days step 30).map { day -> tariff("$meter-$day", base.plusSeconds(day * 86_400L), "0.5", meter) } }
        val expectedConsumption = BigDecimal("1.25").multiply(BigDecimal(days * meters.size))
        val summary = statisticsForRange(rows, rates, null, null, credits)
        decimal(expectedConsumption, summary.consumption)
        decimal(expectedConsumption * BigDecimal("0.5"), summary.cost)
        val trend = intervalTrendForRange(rows, rates, null, null, credits)
        assertEquals(days * meters.size, trend.size)
        assertTrue(trend.all { it.averagePerDay?.compareTo(BigDecimal("1.25")) == 0 })
        for (meter in meters) {
            val dense = dailyRemainingForRange(meter, rows, credits, null, null, zone)
            assertEquals(days + 1, dense.size)
            assertTrue(dense.all { it.source == RemainingSource.ACTUAL && it.value?.compareTo(BigDecimal("100")) == 0 })
            // One long bounded interval exercises every day against thousands of purchases.
            val sparse = dailyRemainingForRange(meter, rows.filter { it.recordedAt == base.toString() || it.recordedAt == base.plusSeconds(days * 86_400L).toString() }, credits, null, null, zone)
            assertEquals(days + 1, sparse.size)
            assertTrue(sparse.drop(1).dropLast(1).all { it.source == RemainingSource.ESTIMATED })
            decimal(BigDecimal("99.375000000000"), sparse[1].value)
            decimal(BigDecimal("100"), sparse.last().value)
        }
        // Diagnostic only: functional tests must not fail because a CI runner is slower.
        println("Ten-year statistics fixture: ${rows.size} readings, ${credits.size} recharges, ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Straight scans intentionally mirror the original algorithm as a small-fixture oracle. */
    private fun referenceStatistics(rows: List<ReadingEntity>, tariffs: List<TariffEntity>, start: Instant?, end: Instant?, recharges: List<RechargeEntity>): MeterStatistics {
        val credits = recharges.filterNot { it.deleted }
        val activeRates = tariffs.filterNot { it.deleted }
        var consumption = BigDecimal.ZERO
        var cost = BigDecimal.ZERO
        var usable = 0
        var increased = false
        var missing = false
        var estimated = false
        rows.filterNot { it.deleted }.groupBy { it.meterId }.forEach { (meterId, readings) ->
            val rates = activeRates.filter { it.meterId == meterId }.sortedBy { Instant.parse(it.effectiveFrom) }
            readings.sortedBy { Instant.parse(it.recordedAt) }.zipWithNext().forEach { (previous, current) ->
                val earlier = Instant.parse(previous.recordedAt)
                val later = Instant.parse(current.recordedAt)
                if ((start == null || later >= start) && (end == null || later <= end)) {
                    val added = credits.filter { it.meterId == meterId && Instant.parse(it.creditedAt) > earlier && Instant.parse(it.creditedAt) <= later }
                        .fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantityDecimal.toBigDecimal() }
                    val delta = previous.valueDecimal.toBigDecimal() + added - current.valueDecimal.toBigDecimal()
                    if (delta.signum() < 0) increased = true else {
                        usable++
                        consumption += delta
                        val initial = rates.lastOrNull { Instant.parse(it.effectiveFrom) <= earlier }
                        val final = rates.lastOrNull { Instant.parse(it.effectiveFrom) <= later }
                        if (initial == null || final == null) missing = true else {
                            cost += delta * final.priceDecimal.toBigDecimal()
                            if (delta.signum() > 0 && rates.any { Instant.parse(it.effectiveFrom) > earlier && Instant.parse(it.effectiveFrom) <= later && it.priceDecimal.toBigDecimal().compareTo(initial.priceDecimal.toBigDecimal()) != 0 }) estimated = true
                        }
                    }
                }
            }
        }
        val inRange = credits.filter { (start == null || Instant.parse(it.creditedAt) >= start) && (end == null || Instant.parse(it.creditedAt) <= end) }
        return MeterStatistics(consumption.takeIf { usable > 0 }, cost.takeIf { usable > 0 && !increased && !missing }, increased,
            activeRates.isNotEmpty(), missing, estimated,
            inRange.fold(BigDecimal.ZERO) { sum, credit -> sum + credit.amountDecimal.toBigDecimal() },
            inRange.fold(BigDecimal.ZERO) { sum, credit -> sum + credit.quantityDecimal.toBigDecimal() })
    }
}
