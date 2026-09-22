package com.dowdah.utilitytracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatisticsTest {
    @Test fun `interval belongs to its later reading and negative values are excluded`() {
        val readings = listOf(
            ReadingEntity("1", "electric", "10", "2026-09-01T00:00:00Z", null, false, 1),
            ReadingEntity("2", "electric", "14", "2026-09-10T00:00:00Z", null, false, 2),
            ReadingEntity("3", "electric", "12", "2026-09-20T00:00:00Z", null, false, 3),
        )
        val summary = consumptionForRange(readings, "2026-09-05T00:00:00Z", "2026-09-30T00:00:00Z")
        assertEquals("4", summary.consumption.toPlainString())
        assertTrue(summary.hasNegativeInterval)
    }
}
