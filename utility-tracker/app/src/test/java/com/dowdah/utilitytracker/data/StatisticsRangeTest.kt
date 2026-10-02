package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

class StatisticsRangeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-02T17:51:00Z") // October 3 locally
    private val anchor = LocalDate.of(2020, 1, 1)
    private val meter = MeterEntity("electric", "ELECTRICITY", 1, "kWh", true, false, 1)
    private fun reading(id: String, at: String, value: String = "100") = ReadingEntity(id, meter.id, value, at, null, false, 1)
    private fun recharge(id: String, at: String, quantity: String = "10") = RechargeEntity(id, meter.id, "6", "0.6", quantity, "CNY", at, null, false, 1)
    private fun range(mode: StatisticsMode, at: Instant = now, inZone: ZoneId = zone,
        readings: List<ReadingEntity> = emptyList(), credits: List<RechargeEntity> = emptyList()) =
        resolveStatisticsRange(mode, anchor, null, null, at, inZone, readings, credits)
    private fun decimal(expected: String, actual: BigDecimal?) {
        assertNotNull(actual)
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test fun `rolling ranges include exactly 30 or 183 local dates and ignore historical anchor`() {
        val month = range(StatisticsMode.LAST_30_DAYS)
        assertEquals(Instant.parse("2026-09-03T16:00:00Z"), month.start)
        assertEquals(now, month.end)
        val half = range(StatisticsMode.LAST_183_DAYS)
        val halfStart = requireNotNull(half.start).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2026, 4, 4), halfStart)
        assertEquals(183, ChronoUnit.DAYS.between(halfStart, now.atZone(zone).toLocalDate()).toInt() + 1)
    }

    @Test fun `fixed day counts handle leap day month end and cross year`() {
        for (date in listOf("2024-03-01", "2024-03-31", "2026-01-01", "2026-08-31")) {
            val today = LocalDate.parse(date)
            val at = today.atTime(12, 0).atZone(zone).toInstant()
            for ((mode, count) in listOf(StatisticsMode.LAST_30_DAYS to 30L, StatisticsMode.LAST_183_DAYS to 183L)) {
                val result = range(mode, at)
                assertEquals(today.minusDays(count - 1).atStartOfDay(zone).toInstant(), result.start)
                assertEquals(at, result.end)
            }
        }
    }

    @Test fun `boundaries use local dates and remain correct around daylight saving`() {
        val ny = ZoneId.of("America/New_York")
        val at = Instant.parse("2026-03-09T02:00:00Z")
        val result = range(StatisticsMode.LAST_30_DAYS, at, ny)
        assertEquals(Instant.parse("2026-02-07T05:00:00Z"), result.start)
        assertEquals(at, result.end)
        val before = range(StatisticsMode.LAST_30_DAYS, Instant.parse("2026-10-02T15:59:59Z"))
        val after = range(StatisticsMode.LAST_30_DAYS, Instant.parse("2026-10-02T16:00:00Z"))
        assertEquals(before.start!!.plus(1, ChronoUnit.DAYS), after.start)
    }

    @Test fun `natural month year and custom keep their existing inclusive bounds`() {
        val month = resolveStatisticsRange(StatisticsMode.MONTH, LocalDate.of(2024, 2, 29), null, null, now, zone)
        assertEquals(Instant.parse("2024-01-31T16:00:00Z"), month.start)
        assertEquals(Instant.parse("2024-02-29T16:00:00Z").minusNanos(1), month.end)
        val year = resolveStatisticsRange(StatisticsMode.YEAR, LocalDate.of(2024, 6, 30), null, null, now, zone)
        assertEquals(Instant.parse("2023-12-31T16:00:00Z"), year.start)
        assertEquals(Instant.parse("2024-12-31T16:00:00Z").minusNanos(1), year.end)
        assertEquals(StatisticsRange(month.start, month.end), resolveStatisticsRange(StatisticsMode.CUSTOM, anchor, month.startUtc, month.endUtc, now, zone))
    }

    @Test fun `all time has no lower bound and caption ignores deleted future records`() {
        val result = range(StatisticsMode.ALL_TIME, readings = listOf(
            reading("deleted", "1990-01-01T00:00:00Z").copy(deleted = true),
            reading("actual", "2026-09-01T12:00:00Z"), reading("future", "2030-01-01T00:00:00Z")),
            credits = listOf(recharge("first-credit", "2026-08-01T00:00:00Z")))
        assertNull(result.start)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), result.captionStart)
        assertEquals(now, result.end)
        assertNull(range(StatisticsMode.ALL_TIME).captionStart)
        assertNull(range(StatisticsMode.ALL_TIME, readings = listOf(reading("future", now.plusSeconds(1).toString()))).captionStart)
    }

    @Test fun `new ranges preserve preceding baseline and exclude future data even on the same local date`() {
        val selected = range(StatisticsMode.LAST_30_DAYS)
        val rows = listOf(
            reading("baseline", selected.start!!.minusSeconds(1).toString(), "100"),
            reading("at-start", selected.start.toString(), "90"),
            reading("at-now", now.toString(), "80"),
            reading("future", now.plusSeconds(1).toString(), "1"),
        )
        val credits = listOf(recharge("start", selected.start.toString()), recharge("now", now.toString()), recharge("future", now.plusSeconds(1).toString()))
        val report = calculateStatistics(StatisticsInput(StatisticsMode.LAST_30_DAYS, anchor, selected, zone, listOf(meter), rows, emptyList(), credits)).single()
        decimal("40", report.summary.consumption)
        decimal("12", report.summary.rechargeSpend)
        assertEquals(2, report.intervals.size)
        decimal("80", report.remaining.last().value)
        assertEquals(now, report.remaining.last().recordedAt)
        val custom = calculateStatistics(StatisticsInput(StatisticsMode.CUSTOM, anchor, selected, zone, listOf(meter), rows.dropLast(1), emptyList(), credits.dropLast(1))).single()
        assertEquals(custom.summary, report.summary)
    }

    @Test fun `future reading cannot bound a daily estimate in all time`() {
        val rows = listOf(reading("past", "2026-10-01T12:00:00Z"), reading("future", "2026-10-04T12:00:00Z", "50"))
        val report = calculateStatistics(StatisticsInput(StatisticsMode.ALL_TIME, anchor, range(StatisticsMode.ALL_TIME), zone, listOf(meter), rows, emptyList(), emptyList())).single()
        assertNull(report.summary.consumption)
        assertNull(report.remaining.last().value)
        assertEquals(RemainingSource.UNAVAILABLE, report.remaining.last().source)
    }

    @Test fun `only recharges remain visible without manufacturing readings or consumption`() {
        val credit = recharge("only", "2026-09-01T12:00:00Z")
        val selected = range(StatisticsMode.ALL_TIME, credits = listOf(credit))
        val report = calculateStatistics(StatisticsInput(StatisticsMode.ALL_TIME, anchor, selected, zone, listOf(meter), emptyList(), emptyList(), listOf(credit))).single()
        assertEquals(Instant.parse(credit.creditedAt), selected.captionStart)
        decimal("6", report.summary.rechargeSpend)
        assertNull(report.summary.consumption)
        assertTrue(report.remaining.isEmpty())
    }

    @Test fun `statistics order is electric cold hot independent of stored or localized order`() {
        val cold = meter.copy(id = "cold", meterType = "COLD_WATER")
        val hot = meter.copy(id = "hot", meterType = "HOT_WATER")
        val unknown = meter.copy(id = "other", meterType = "UNKNOWN")
        assertEquals(listOf(meter, cold, hot, unknown), statisticsMeterOrder(listOf(hot, unknown, cold, meter)))
        assertEquals(StatisticsMode.MONTH, StatisticsMode.fromKey("unknown"))
        assertEquals(StatisticsMode.LAST_183_DAYS, StatisticsMode.fromKey("last183"))
    }
}
