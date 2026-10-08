package com.dowdah.utilitytracker.data

import java.math.BigDecimal
import java.time.Instant
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

class BalanceForecastTest {
    private val at = Instant.parse("2026-10-03T00:00:00Z")
    private val meter = MeterEntity("m", "ELECTRICITY", 1, "kWh", true, false, 1)
    private fun reading(day: Long, value: String, id: String = "r$day") = ReadingEntity(id, "m", value, at.plus(Duration.ofDays(day)).toString(), null, false, 1)
    private fun credit(day: Long, quantity: String) = RechargeEntity("c$day", "m", quantity, "1", quantity, "CNY", at.plus(Duration.ofDays(day)).toString(), null, false, 1)
    private fun forecast(rows: List<ReadingEntity>, credits: List<RechargeEntity> = emptyList(), day: Long = 0, water: Boolean = false, conflict: Boolean = false) = balanceForecast(if (water) meter.copy(meterType = "COLD_WATER", unit = "t") else meter, rows, credits, conflict, at.plus(Duration.ofDays(day)))
    private fun decimal(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))

    @Test fun weightsCompleteIntervalsAndProjectsOnlyOutsideLedger() {
        val rows = listOf(reading(-4,"100"), reading(-3,"90"), reading(0,"84"))
        val result = forecast(rows, day = 1)
        decimal("4", result.dailyUse); decimal("80", result.remaining); decimal("20", result.daysLeft)
        assertEquals("84", rows.last().valueDecimal)
    }
    @Test fun rechargesAtReadingBoundaryAreNotCountedTwice() {
        val result = forecast(listOf(reading(-2,"100"), reading(0,"110")), listOf(credit(-2,"99"),credit(0,"30"),credit(1,"20"),credit(3,"500")), day=1)
        decimal("10",result.dailyUse); decimal("120",result.remaining); decimal("12",result.daysLeft)
    }
    @Test fun sparseWaterUsesLongerWindowAndFullBoundaryInterval() {
        val result = forecast(listOf(reading(-200,"100"),reading(-100,"90"),reading(0,"80")),water=true)
        decimal("200",result.coverageDays); decimal("0.1",result.dailyUse)
    }
    @Test fun oldWaterReadingsContinueUntilNinetyDaysWithoutRechargeReset() {
        val rows = listOf(reading(-30,"60"),reading(0,"30"))
        assertFalse(forecast(rows,day=30,water=true).oldReading)
        assertTrue(forecast(rows,day=31,water=true).oldReading)
        assertNull(forecast(rows,day=90,water=true).issue)
        assertEquals(ForecastIssue.STALE_READING,forecast(rows,listOf(credit(91,"300")),day=91,water=true).issue)
    }
    @Test fun electricityFreshnessAndMinimumCoverage() {
        val rows = listOf(reading(-1,"10"),reading(0,"9"))
        assertFalse(forecast(rows,day=7).oldReading); assertTrue(forecast(rows,day=8).oldReading)
        assertNull(forecast(rows,day=30).issue)
        assertEquals(ForecastIssue.STALE_READING,forecast(rows,day=31).issue)
        assertEquals(ForecastIssue.INSUFFICIENT_HISTORY,forecast(rows,water=true).issue)
    }
    @Test fun noFiniteDaysForZeroUseButQuantityThresholdStillWorks() {
        val result=forecast(listOf(reading(-1,"2"),reading(0,"2")))
        assertNull(result.daysLeft); decimal("0",result.dailyUse)
        assertFalse(result.isLow(MeterReminderEntity("m")))
        assertTrue(result.isLow(MeterReminderEntity("m",quantityThreshold="2")))
    }
    @Test fun depletedBalanceIsClampedAndRemainsAnEstimate() {
        val result=forecast(listOf(reading(-1,"10"),reading(0,"1")),day=1)
        decimal("0",result.remaining); decimal("0",result.daysLeft)
    }
    @Test fun errorsBlockPredictionsAndThresholds() {
        val valid=listOf(reading(-1,"10"),reading(0,"1"))
        assertEquals(ForecastIssue.NO_READING,forecast(emptyList()).issue)
        assertEquals(ForecastIssue.INSUFFICIENT_HISTORY,forecast(valid.takeLast(1)).issue)
        assertEquals(ForecastIssue.CONFLICT,forecast(valid,conflict=true).issue)
        assertEquals(ForecastIssue.FUTURE_READING,forecast(listOf(reading(1,"1"))).issue)
        assertEquals(ForecastIssue.INVALID_INTERVAL,forecast(listOf(reading(-1,"1"),reading(0,"10"))).issue)
        val ambiguous=forecast(valid+reading(0,"2","duplicate"))
        assertEquals(ForecastIssue.AMBIGUOUS_READING,ambiguous.issue); assertFalse(ambiguous.isLow(MeterReminderEntity("m")))
    }
    @Test fun identicalReadingsAtOneTimeAreDeduplicatedOnlyForCalculation() {
        val result=forecast(listOf(reading(-1,"10"),reading(0,"9"),reading(0,"9.0","copy")))
        decimal("1",result.dailyUse)
    }
    @Test fun invalidOlderIntervalStopsTheContiguousWindow() {
        val result=forecast(listOf(reading(-10,"1"),reading(-2,"20"),reading(0,"10")))
        decimal("2",result.coverageDays); decimal("5",result.dailyUse)
    }
    @Test fun thresholdUsesUnroundedDaysAndIndependentMeterData() {
        val result=forecast(listOf(reading(-1,"8.000000000001"),reading(0,"7.000000000001"),reading(1,"1","other").copy(meterId="other")))
        assertFalse(result.isLow(MeterReminderEntity("m")))
        assertTrue(result.isLow(MeterReminderEntity("m",quantityThreshold="8")))
        assertFalse(result.isLow(MeterReminderEntity("m",enabled=false,quantityThreshold="8")))
    }
    @Test fun subDayDurationAndDeletedRowsAreHandled() {
        val rows=listOf(reading(-1,"20"),reading(0,"10"),reading(0,"30","deleted").copy(deleted=true))
        val result=balanceForecast(meter,rows,emptyList(),false,at.plusSeconds(43200))
        decimal("5",result.remaining); decimal("0.5",result.daysLeft)
    }
}
