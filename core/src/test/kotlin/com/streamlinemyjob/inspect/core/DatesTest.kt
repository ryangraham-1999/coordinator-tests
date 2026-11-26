package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/** Dates (designs/stage-1-foundation.md 4.9), with S1.10's picker tests across UK clock-change days. */
class DatesTest {
    private fun random(test: String) = TestRandom.forTest("${DatesTest::class.qualifiedName}.$test")

    private fun code(typed: String, today: LocalDate) = (Dates.parse(typed, today) as Dates.Refused).problem.code

    @Test
    fun thePickerExampleIsTheSameDayWhateverTheDefaultTimeZone() {
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("Europe/London", "America/New_York", "Asia/Tokyo", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(zone, LocalDate.of(2026, 3, 30), Dates.fromPickerMillis(1774828800000L))
            }
        } finally {
            TimeZone.setDefault(saved)
        }
        assertEquals("2026-03-30", Dates.toStored(Dates.fromPickerMillis(1774828800000L)))
    }

    @Test
    fun aLeapDayIsAcceptedOnceItIsNotInTheFuture() {
        assertEquals(Dates.Ok(LocalDate.of(2028, 2, 29)), Dates.parse("29/02/2028", LocalDate.of(2028, 2, 29)))
        assertEquals(Dates.Ok(LocalDate.of(2028, 2, 29)), Dates.parse("29/02/2028", LocalDate.of(2031, 1, 1)))
        assertEquals(Codes.DATE_OUT_OF_RANGE, code("29/02/2028", LocalDate.of(2028, 2, 28)))
        assertEquals("2028-02-29", Dates.toStored(LocalDate.of(2028, 2, 29)))
    }

    @Test
    fun aDayThatDoesNotExistIsDateBad() {
        val today = LocalDate.of(2030, 1, 1)
        assertEquals(Codes.DATE_BAD, code("29/02/2027", today))
        assertEquals(Codes.DATE_BAD, code("31/02/2026", today))
        assertEquals(Codes.DATE_BAD, code("0/1/2020", today))
        assertEquals(Codes.DATE_BAD, code("1/13/2020", today))
    }

    @Test
    fun todayTypedExactlyIsOkAndTomorrowIsOutOfRange() {
        val r = random("todayTypedExactlyIsOkAndTomorrowIsOutOfRange")
        val days = LocalDate.of(2040, 1, 1).toEpochDay() - Rules.DATE_MIN.toEpochDay()
        val todays = listOf(LocalDate.now()) + List(200) { Rules.DATE_MIN.plusDays(r.nextLong(0, days)) }
        for (today in todays) {
            assertEquals(Dates.Ok(today), Dates.parse(Dates.format(today), today))
            assertEquals(Dates.Ok(today), Dates.parse("${today.dayOfMonth}/${today.monthValue}/${today.year}", today))
            val tomorrow = today.plusDays(1)
            assertEquals(Codes.DATE_OUT_OF_RANGE, code(Dates.format(tomorrow), today))
        }
    }

    @Test
    fun theRangeStartsOn1January1990() {
        val today = LocalDate.of(2026, 11, 18)
        assertEquals(Dates.Ok(LocalDate.of(1990, 1, 1)), Dates.parse("1/1/1990", today))
        assertEquals(Codes.DATE_OUT_OF_RANGE, code("31/12/1989", today))
    }

    @Test
    fun onlyUkOrderWithSlashesIsAccepted() {
        val today = LocalDate.of(2026, 11, 18)
        assertEquals(Dates.Ok(LocalDate.of(2026, 10, 12)), Dates.parse("12/10/2026", today))
        assertEquals(Dates.Ok(LocalDate.of(2026, 9, 1)), Dates.parse("1/9/2026", today))
        assertEquals(Dates.Ok(LocalDate.of(2026, 9, 1)), Dates.parse(" 01/09/2026 ", today))
        for (bad in listOf("2026-09-01", "1.9.2026", "1/9/26", "001/9/2026", "1/9/20266", "", "today", "1 / 9 / 2026")) {
            assertEquals(bad, Codes.DATE_BAD, code(bad, today))
        }
    }

    @Test
    fun shownAndStored() {
        val d = LocalDate.of(2026, 11, 18)
        assertEquals("18/11/2026", Dates.format(d))
        assertEquals("2026-11-18", Dates.toStored(d))
        assertEquals(d, Dates.fromStored("2026-11-18"))
        assertEquals("01/09/2026", Dates.format(LocalDate.of(2026, 9, 1)))
    }

    /**
     * Every UK clock-change day from 1990 to 2040, read from the time zone rules of Europe/London
     * (not written out), with the day before and the day after.
     */
    private fun ukClockChangeDays(): List<LocalDate> {
        val rules = ZoneId.of("Europe/London").rules
        val days = sortedSetOf<LocalDate>()
        var t = rules.nextTransition(LocalDate.of(1990, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant())
        while (t != null && t.instant.isBefore(LocalDate.of(2041, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
            val day = t.instant.atZone(ZoneOffset.UTC).toLocalDate()
            days += listOf(day.minusDays(1), day, day.plusDays(1))
            t = rules.nextTransition(t.instant)
        }
        return days.toList()
    }

    private fun utcMidnight(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun pickerMillisOnUkClockChangeDaysAreTheSameDayInLondonNewYorkAndTokyo() {
        val days = ukClockChangeDays()
        // Two changes a year, 51 years, three days each (some overlap is impossible: changes are months apart).
        assertEquals(51 * 2 * 3, days.size)
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("Europe/London", "America/New_York", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (day in days) {
                    assertEquals("$zone $day", day, Dates.fromPickerMillis(utcMidnight(day)))
                    assertEquals("$zone $day", Dates.toStored(day), Dates.toStored(Dates.fromPickerMillis(utcMidnight(day))))
                }
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun theseZonesWouldCatchAConversionThroughThePhonesTimeZone() {
        // Proof the test above can fail: converting through the default zone gives the day before in
        // New York on every one of those days, and the right day in London and Tokyo only by chance.
        val throughZone = { ms: Long, zone: String -> Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone)).toLocalDate() }
        val days = ukClockChangeDays()
        for (day in days) assertEquals(day.minusDays(1), throughZone(utcMidnight(day), "America/New_York"))
        assertEquals(LocalDate.of(2026, 3, 29), throughZone(1774828800000L, "America/New_York"))
    }
}
