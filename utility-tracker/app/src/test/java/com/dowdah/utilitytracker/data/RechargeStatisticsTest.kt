package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class RechargeStatisticsTest {
    private fun reading(date: String, value: String) = ReadingEntity(date, "electric", value, "${date}T12:00:00Z", null, false, 1)
    private fun recharge(date: String, amount: String = "60", price: String = "0.6") = RechargeEntity(date, "electric", amount, price, rechargeQuantity(amount, price).toPlainString(), "CNY", "${date}T12:00:00Z", null, false, 1)
    private val rate = TariffEntity("rate", "electric", "0.6", "CNY", "2026-01-01T00:00:00Z", false, 1)
    private fun number(expected: String, actual: BigDecimal?) { assertNotNull(actual); assertEquals(0, BigDecimal(expected).compareTo(actual)) }
    @Test fun `increase after recharge produces consumption instead of unknown`() {
        val stats = statisticsForRange(listOf(reading("2026-09-01", "100"), reading("2026-09-22", "150")), listOf(rate), null, null, listOf(recharge("2026-09-22")))
        number("50", stats.consumption); number("30", stats.cost); number("60", stats.rechargeSpend)
        assertFalse(stats.hasIncrease)
    }
    @Test fun `decreasing balance can contain multiple recharges`() {
        val stats = statisticsForRange(listOf(reading("2026-09-01", "100"), reading("2026-09-22", "80")), listOf(rate), null, null, listOf(recharge("2026-09-02"), recharge("2026-09-03", "30")))
        number("170", stats.consumption); number("90", stats.rechargeSpend)
    }
    @Test fun `interval is open on left closed on right while spending follows credit date`() {
        val rows = listOf(reading("2026-08-31", "100"), reading("2026-09-22", "150"))
        val credits = listOf(recharge("2026-08-31", "12"), recharge("2026-09-22"))
        val stats = statisticsForRange(rows, listOf(rate), "2026-09-01T00:00:00Z", "2026-09-30T23:59:59Z", credits)
        number("50", stats.consumption); number("60", stats.rechargeSpend)
        assertFalse(remainingReadingIncreases(rows.take(1), null, "electric", "150", rows.last().recordedAt, credits))
    }
    @Test fun `recharge alone does not fabricate consumption or remaining quantity`() {
        val stats = statisticsForRange(listOf(reading("2026-09-01", "100")), listOf(rate), null, null, listOf(recharge("2026-09-02")))
        assertNull(stats.consumption); assertNull(stats.cost); number("60", stats.rechargeSpend)
    }
    @Test fun `deleted credit recalculates and missing credit remains unknown`() {
        val rows = listOf(reading("2026-09-01", "100"), reading("2026-09-22", "150"))
        val stats = statisticsForRange(rows, listOf(rate), null, null, listOf(recharge("2026-09-22").copy(deleted = true)))
        assertNull(stats.consumption); assertTrue(stats.hasIncrease); number("0", stats.rechargeSpend)
    }
    @Test fun `purchase price snapshot and later consumption price remain separate`() {
        val credit = recharge("2026-12-31", "60", "0.5")
        val stats = statisticsForRange(listOf(reading("2026-12-30", "100"), reading("2027-01-02", "150")), listOf(rate), "2027-01-01T00:00:00Z", null, listOf(credit))
        number("70", stats.consumption); number("42", stats.cost); number("0", stats.rechargeSpend)
    }
    @Test fun `division uses scale twelve half even`() {
        number("0.333333333333", rechargeQuantity("1", "3"))
        number("0.000000000002", rechargeQuantity("0.000000000005", "2"))
        number("0.000000000004", rechargeQuantity("0.000000000007", "2"))
    }
    @Test fun `invalid or unrepresentable purchases are rejected`() {
        for ((amount, price) in listOf("0" to "1", "1" to "0", "-1" to "1", "1e1000000" to "1", "0.000000000001" to "100")) {
            try { rechargeQuantity(amount, price); fail("Expected invalid purchase") } catch (_: IllegalArgumentException) { }
        }
    }
}
