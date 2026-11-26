package com.streamlinemyjob.inspect.core

/** Mileage rules (designs/stage-1-foundation.md 4.9, C8). */
object Mileage {
    sealed interface Result
    data class Ok(val miles: Int) : Result
    data class Refused(val problem: Problem) : Result

    /**
     * Parses typed mileage: spaces and commas removed, then 1 to 6 digits (0 to 999,999).
     * Empty → `MILEAGE_EMPTY`; anything but digits → `MILEAGE_BAD`; 7 or more digits → `MILEAGE_TOO_BIG`.
     * Leading zeros are fine (`007` → 7).
     */
    fun parse(typed: String): Result {
        val digits = typed.filter { it != ' ' && it != ',' }
        if (digits.isEmpty()) return Refused(Problem(Codes.MILEAGE_EMPTY))
        if (!digits.all { it in '0'..'9' }) return Refused(Problem(Codes.MILEAGE_BAD))
        if (digits.length > Rules.MILEAGE_DIGITS_MAX) return Refused(Problem(Codes.MILEAGE_TOO_BIG))
        return Ok(digits.toInt())
    }

    /** `45,210 miles`. */
    fun format(miles: Int): String = "${grouped(miles)} miles"

    /** The number with a comma between each group of three digits: `45,210`. */
    fun grouped(miles: Int): String {
        require(miles >= 0) { "mileage is never negative: $miles" }
        return miles.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
