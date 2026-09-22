package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    private fun reading(day: Int, value: String, meter: String = "electric") = ReadingEntity(
        "$meter-$day", meter, value, "2026-09-${day.toString().padStart(2, '0')}T12:00:00Z", null, false, 1,
    )
    private fun tariff(day: Int = 1, price: String = "0.6", meter: String = "electric") = TariffEntity(
        "$meter-rate-$day", meter, price, "CNY", "2026-09-${day.toString().padStart(2, '0')}T00:00:00Z", false, 1,
    )
    private fun stats(rows: List<ReadingEntity>, rates: List<TariffEntity> = listOf(tariff())) =
        statisticsForRange(rows, rates, null, null)
    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test fun `descending remaining readings produce consumption and cost`() {
        val summary = stats(listOf(reading(22, "148"), reading(20, "165"), reading(21, "155")))
        decimal("17", summary.consumption)
        decimal("10.2", summary.cost)
        assertFalse(summary.hasIncrease)
        assertFalse(summary.costEstimated)
    }

    @Test fun `empty single and out of range readings are unknown not zero`() {
        for (rows in listOf(emptyList(), listOf(reading(20, "165")))) {
            assertNull(stats(rows).consumption)
            assertNull(stats(rows).cost)
        }
        val result = statisticsForRange(listOf(reading(20, "165"), reading(21, "155")), listOf(tariff()), "2026-09-22T00:00:00Z", null)
        assertNull(result.consumption)
    }

    @Test fun `equal remaining readings are a genuine zero`() {
        val result = stats(listOf(reading(20, "165"), reading(21, "165")))
        decimal("0", result.consumption)
        decimal("0", result.cost)
        assertFalse(result.hasIncrease)
    }

    @Test fun `top up yields a known subtotal and no total cost`() {
        val result = stats(listOf(reading(19, "165"), reading(20, "155"), reading(21, "200"), reading(22, "197")))
        decimal("13", result.consumption)
        assertTrue(result.hasIncrease)
        assertNull(result.cost)
        val onlyIncrease = stats(listOf(reading(20, "100"), reading(21, "200")))
        assertNull(onlyIncrease.consumption)
        assertNull(onlyIncrease.cost)
    }

    @Test fun `intervals use later date with inclusive boundaries and a preceding baseline`() {
        val rows = listOf(reading(20, "165"), reading(21, "155"), reading(22, "148"))
        val result = statisticsForRange(rows, listOf(tariff()), "2026-09-21T12:00:00Z", "2026-09-21T12:00:00Z")
        decimal("10", result.consumption)
        decimal("6", result.cost)
    }

    @Test fun `instants are sorted chronologically across offsets and fractional seconds`() {
        val first = reading(20, "165").copy(recordedAt = "2026-09-20T12:00:00Z")
        val second = reading(21, "155").copy(recordedAt = "2026-09-20T12:00:00.500Z")
        val third = reading(22, "148").copy(recordedAt = "2026-09-20T21:00:00+08:00")
        decimal("17", stats(listOf(third, second, first)).consumption)
    }

    @Test fun `deleted and edited readings recompute rather than reuse cached differences`() {
        val original = listOf(reading(20, "165"), reading(21, "155"), reading(22, "148"))
        decimal("17", stats(listOf(original[0], original[1].copy(deleted = true), original[2])).consumption)
        decimal("15", stats(original.dropLast(1) + original.last().copy(valueDecimal = "150")).consumption)
        assertNull(stats(listOf(original[0], original[1].copy(deleted = true))).consumption)
    }

    @Test fun `missing deleted and future tariffs never masquerade as free consumption`() {
        val rows = listOf(reading(20, "165"), reading(21, "155"))
        for (rates in listOf(emptyList(), listOf(tariff().copy(deleted = true)), listOf(tariff(22)), listOf(tariff(21)))) {
            val result = stats(rows, rates)
            decimal("10", result.consumption)
            assertNull(result.cost)
            assertTrue(result.hasMissingTariff)
        }
        assertFalse(stats(rows, emptyList()).hasTariffs)
        assertTrue(stats(rows, listOf(tariff(22))).hasTariffs)
    }

    @Test fun `rate change uses later reading rate but explicitly marks estimate`() {
        val rows = listOf(reading(20, "165"), reading(22, "155"))
        val result = stats(rows, listOf(tariff(price = "0.5"), tariff(21, "0.8")))
        decimal("8", result.cost)
        assertTrue(result.costEstimated)
        assertFalse(stats(rows, listOf(tariff(), tariff(21, "0.60"))).costEstimated)
    }

    @Test fun `rates at the first reading cover the interval and later rate applies at its exact timestamp`() {
        val rows = listOf(reading(20, "165"), reading(21, "155"))
        val initial = tariff().copy(effectiveFrom = rows[0].recordedAt)
        decimal("6", stats(rows, listOf(initial)).cost)
        val changed = tariff(21, "0.8").copy(effectiveFrom = rows[1].recordedAt)
        val result = stats(rows, listOf(initial, changed))
        decimal("8", result.cost)
        assertTrue(result.costEstimated)
    }

    @Test fun `one unpriced interval prevents an apparently complete cost`() {
        val result = stats(listOf(reading(19, "170"), reading(20, "165"), reading(21, "155")), listOf(tariff(20)))
        decimal("15", result.consumption)
        assertNull(result.cost)
    }

    @Test fun `meters are never joined or priced with another meters tariff`() {
        val rows = listOf(reading(20, "165"), reading(21, "155"), reading(20, "60", "water"), reading(21, "57", "water"))
        val result = stats(rows, listOf(tariff(), tariff(price = "3", meter = "water")))
        decimal("13", result.consumption)
        decimal("15", result.cost)
        assertNull(stats(rows, listOf(tariff())).cost)
    }

    @Test fun `backfills compare temporal neighbours not the most recent entry`() {
        val rows = listOf(reading(20, "165"), reading(22, "148"))
        assertFalse(remainingReadingIncreases(rows, null, "electric", "155", reading(21, "155").recordedAt))
        assertTrue(remainingReadingIncreases(rows, null, "electric", "170", reading(21, "170").recordedAt))
        assertTrue(remainingReadingIncreases(rows, null, "electric", "140", reading(21, "140").recordedAt))
        assertFalse(remainingReadingIncreases(rows, null, "electric", "140", reading(23, "140").recordedAt))
    }

    @Test fun `editing excludes itself deleted rows and other meters`() {
        val row = reading(20, "165")
        val rows = listOf(row, reading(19, "100").copy(deleted = true), reading(19, "50", "water"))
        assertFalse(remainingReadingIncreases(rows, row.id, "electric", "170", row.recordedAt))
    }

    @Test fun `new reading defaults to active electricity independent of list order`() {
        fun meter(id: String, type: String) = MeterEntity(id, type, 1, "kWh", true, false, 1)
        val cold = meter("cold", "COLD_WATER")
        val electricity = meter("electric", "ELECTRICITY")
        assertEquals("electric", defaultReadingMeterId(listOf(cold, electricity)))
        assertEquals("electric", defaultReadingMeterId(listOf(electricity, cold)))
        assertEquals("", defaultReadingMeterId(emptyList()))
        assertEquals("", defaultReadingMeterId(listOf(cold)))
        assertEquals("", defaultReadingMeterId(listOf(electricity.copy(deleted = true))))
        assertEquals("", defaultReadingMeterId(listOf(electricity.copy(active = false))))
    }
}
