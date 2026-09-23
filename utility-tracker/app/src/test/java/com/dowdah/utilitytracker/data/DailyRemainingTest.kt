package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailyRemainingTest {
    private fun reading(id: String, at: String, value: String, meter: String = "electric") =
        ReadingEntity(id, meter, value, at, null, false, 1)
    private fun recharge(at: String, quantity: String, meter: String = "electric") =
        RechargeEntity("credit-$at", meter, "60", "0.6", quantity, "CNY", at, null, false, 1)
    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertTrue("Expected $expected but was $actual", BigDecimal(expected).subtract(actual).abs() < BigDecimal("0.000001"))
    }

    @Test fun `actual days use last reading and intervening days use calibrated end of day`() {
        val rows = listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("same-day", "2026-09-01T18:00:00Z", "94"),
            reading("b", "2026-09-05T18:00:00Z", "54"),
        )
        val points = dailyRemainingForRange("electric", rows, emptyList(), "2026-09-01T00:00:00Z", "2026-09-07T23:59:59Z", ZoneId.of("UTC"))
        assertEquals(7, points.size)
        assertEquals(RemainingSource.ACTUAL, points[0].source)
        assertEquals("2026-09-01T18:00:00Z", points[0].recordedAt.toString())
        decimal("94", points[0].value)
        assertEquals(RemainingSource.ESTIMATED, points[1].source)
        decimal("81.5", points[1].value)
        assertNull(points[1].gap)
        decimal("54", points[4].value)
        assertEquals(RemainingSource.UNAVAILABLE, points[5].source)
        assertEquals(RemainingGap.NO_BOUNDING_READINGS, points[5].gap)
        assertNull(points[5].value)
    }

    @Test fun `recharge applies at its timestamp and meter balances stay separate`() {
        val rows = listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-05T12:00:00Z", "120"),
            reading("water-a", "2026-09-01T12:00:00Z", "900", "water"),
        )
        val credits = listOf(recharge("2026-09-03T06:00:00Z", "50"), recharge("2026-09-03T06:00:00Z", "900", "water"))
        val points = dailyRemainingForRange("electric", rows, credits, null, null, ZoneId.of("UTC"))
        decimal("88.75", points[1].value)
        decimal("131.25", points[2].value)
        assertEquals(1, points[2].rechargeCount)
        decimal("50", points[2].rechargeQuantity)
        decimal("120", points.last().value)
    }

    @Test fun `credit at a reading belongs to the preceding interval only`() {
        val rows = listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("post-credit", "2026-09-03T06:00:00Z", "130"),
            reading("b", "2026-09-05T12:00:00Z", "100"),
        )
        val credits = listOf(recharge("2026-09-03T06:00:00Z", "50"))
        decimal("20", statisticsForRange(rows.take(2), emptyList(), null, null, credits).consumption)
        decimal("30", statisticsForRange(rows.drop(1), emptyList(), null, null, credits).consumption)
        val points = dailyRemainingForRange("electric", rows, credits, null, null, ZoneId.of("UTC"))
        decimal("130", points[2].value)
        assertEquals(RemainingSource.ACTUAL, points[2].source)
        assertEquals(1, points[2].rechargeCount)
        assertEquals(RemainingSource.ESTIMATED, points[3].source)
        assertTrue(points[3].value!! < BigDecimal("130"))
    }

    @Test fun `unknown consumption and impossible negative estimates leave gaps`() {
        val unexplained = listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-04T12:00:00Z", "120"),
        )
        val unknown = dailyRemainingForRange("electric", unexplained, emptyList(), null, null, ZoneId.of("UTC"))
        assertEquals(RemainingGap.UNKNOWN_CONSUMPTION, unknown[1].gap)
        assertNull(unknown[1].value)
        assertEquals(RemainingSource.ACTUAL, unknown.last().source)
        assertTrue(unknown.last().breakBefore)

        val rows = listOf(
            reading("c", "2026-09-01T12:00:00Z", "1"),
            reading("d", "2026-09-05T12:00:00Z", "90"),
        )
        val credits = listOf(recharge("2026-09-04T06:00:00Z", "100"))
        val values = dailyRemainingForRange("electric", rows, credits, null, null, ZoneId.of("UTC"))
        assertEquals(RemainingGap.NEGATIVE_ESTIMATE, values[1].gap)
        assertNull(values[1].value)
        assertNotNull(values[3].value)
    }

    @Test fun `local day boundaries respect daylight saving and duplicate timestamps do not create estimates`() {
        val zone = ZoneId.of("America/New_York")
        val rows = listOf(
            reading("a", "2026-03-07T17:00:00Z", "100"),
            reading("b", "2026-03-09T16:00:00Z", "53"),
        )
        val points = dailyRemainingForRange("electric", rows, emptyList(), null, null, zone)
        assertEquals(3, points.size)
        decimal("65", points[1].value)
        assertEquals(RemainingSource.ESTIMATED, points[1].source)

        val duplicate = listOf(
            reading("c", "2026-09-01T12:00:00Z", "100"),
            reading("d", "2026-09-01T12:00:00Z", "90"),
        )
        val sameDay = dailyRemainingForRange("electric", duplicate, emptyList(), null, null, ZoneId.of("UTC"))
        assertEquals(1, sameDay.size)
        decimal("90", sameDay.single().value)
        assertEquals(RemainingSource.ACTUAL, sameDay.single().source)
    }
}
