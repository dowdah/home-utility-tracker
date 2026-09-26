package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class IntervalTrendTest {
    private fun reading(id: String, at: String, remaining: String, deleted: Boolean = false) =
        ReadingEntity(id, "electric", remaining, at, null, deleted, 1)

    private fun recharge(at: String, quantity: String) =
        RechargeEntity("credit-$at", "electric", "60", "0.6", quantity, "CNY", at, null, false, 1)

    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test fun `unequal intervals compare rate rather than raw totals and match summary`() {
        val rows = listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("b", "2026-09-03T12:00:00Z", "80"),
            reading("c", "2026-09-07T12:00:00Z", "40"),
        )
        val points = intervalTrendForRange(rows, emptyList(), null, null)
        assertEquals(2, points.size)
        decimal("20", points[0].statistics.consumption)
        decimal("40", points[1].statistics.consumption)
        decimal("10", points[0].averagePerDay)
        decimal("10", points[1].averagePerDay)
        decimal("60", statisticsForRange(rows, emptyList(), null, null).consumption)
    }

    @Test fun `same day readings use actual elapsed hours and zero duration stays unknown`() {
        val first = reading("a", "2026-09-01T12:00:00Z", "100")
        val second = reading("b", "2026-09-01T18:00:00Z", "94")
        val duplicateTime = reading("c", "2026-09-01T18:00:00Z", "92")
        val points = intervalTrendForRange(listOf(first, second, duplicateTime), emptyList(), null, null)
        decimal("0.25", points[0].elapsedDays)
        decimal("24", points[0].averagePerDay)
        assertTrue(points[1].hasZeroDuration)
        decimal("2", points[1].statistics.consumption)
        assertNull(points[1].averagePerDay)
    }

    @Test fun `cross month interval uses complete duration but belongs to later reading month`() {
        val rows = listOf(
            reading("a", "2026-08-30T12:00:00Z", "100"),
            reading("b", "2026-09-02T12:00:00Z", "70"),
        )
        assertTrue(intervalTrendForRange(rows, emptyList(), "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z").isEmpty())
        val point = intervalTrendForRange(rows, emptyList(), "2026-09-01T00:00:00Z", "2026-09-30T23:59:59Z").single()
        assertEquals("2026-08-30T12:00:00Z", point.start.toString())
        decimal("3", point.elapsedDays)
        decimal("10", point.averagePerDay)
        decimal("30", statisticsForRange(rows, emptyList(), "2026-09-01T00:00:00Z", "2026-09-30T23:59:59Z").consumption)
    }

    @Test fun `recharges unknown intervals deleted readings and genuine zero preserve accounting`() {
        val rows = listOf(
            reading("a", "2026-09-01T12:00:00Z", "100"),
            reading("deleted", "2026-09-02T12:00:00Z", "999", deleted = true),
            reading("b", "2026-09-03T12:00:00Z", "130"),
            reading("c", "2026-09-04T12:00:00Z", "150"),
            reading("d", "2026-09-05T12:00:00Z", "150"),
        )
        val credits = listOf(recharge("2026-09-02T12:00:00Z", "50"))
        val points = intervalTrendForRange(rows, emptyList(), null, null, credits)
        assertEquals(3, points.size)
        decimal("20", points[0].statistics.consumption)
        decimal("10", points[0].averagePerDay)
        assertTrue(points[1].statistics.hasIncrease)
        assertNull(points[1].statistics.consumption)
        assertNull(points[1].averagePerDay)
        decimal("0", points[2].statistics.consumption)
        decimal("0", points[2].averagePerDay)
        val summary = statisticsForRange(rows, emptyList(), null, null, credits)
        decimal("20", summary.consumption)
        assertTrue(summary.hasIncrease)
    }
}
