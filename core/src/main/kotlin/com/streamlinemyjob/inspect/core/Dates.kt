package com.streamlinemyjob.inspect.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Inspection dates (designs/stage-1-foundation.md 4.9, C10): a `LocalDate` with no time zone,
 * stored as ISO `yyyy-mm-dd` text, typed and shown in UK order.
 */
object Dates {
    sealed interface Result
    data class Ok(val date: LocalDate) : Result
    data class Refused(val problem: Problem) : Result

    private val TYPED = Regex("([0-9]{1,2})/([0-9]{1,2})/([0-9]{4})")
    private val SHOWN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu")

    /**
     * Parses a typed date: `D/M/YYYY` or `DD/MM/YYYY` → else `DATE_BAD`; the date must exist
     * (31/02 → `DATE_BAD`); it must be from 1990-01-01 to [today] (today on the phone's clock)
     * → else `DATE_OUT_OF_RANGE`. Whitespace around the text is ignored.
     */
    fun parse(typed: String, today: LocalDate): Result {
        val m = TYPED.matchEntire(typed.trim()) ?: return Refused(Problem(Codes.DATE_BAD))
        val (d, mo, y) = m.destructured
        val date = try {
            LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        } catch (e: DateTimeException) {
            return Refused(Problem(Codes.DATE_BAD))
        }
        if (date.isBefore(Rules.DATE_MIN) || date.isAfter(today)) return Refused(Problem(Codes.DATE_OUT_OF_RANGE, toStored(date)))
        return Ok(date)
    }

    /** The date picker's selection (UTC midnight milliseconds) as a date, never through the device's time zone. */
    fun fromPickerMillis(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    /** `18/11/2026`. */
    fun format(date: LocalDate): String = date.format(SHOWN)

    /** ISO `yyyy-mm-dd`, as stored. */
    fun toStored(date: LocalDate): String = date.toString()

    fun fromStored(stored: String): LocalDate = LocalDate.parse(stored)
}
