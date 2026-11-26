package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mileage rules (designs/stage-1-foundation.md 4.9). */
class MileageTest {
    private fun random(test: String) = TestRandom.forTest("${MileageTest::class.qualifiedName}.$test")

    private fun code(typed: String) = (Mileage.parse(typed) as Mileage.Refused).problem.code

    @Test
    fun spacesAndCommasAreRemoved() {
        assertEquals(Mileage.Ok(45210), Mileage.parse("45,210"))
        assertEquals(Mileage.Ok(45210), Mileage.parse(" 45 210 "))
    }

    @Test
    fun leadingZerosAreFine() = assertEquals(Mileage.Ok(7), Mileage.parse("007"))

    @Test
    fun extremes() {
        assertEquals(Mileage.Ok(0), Mileage.parse("0"))
        assertEquals(Mileage.Ok(999_999), Mileage.parse("999,999"))
        assertEquals(Codes.MILEAGE_TOO_BIG, code("1,000,000"))
        assertEquals(Codes.MILEAGE_TOO_BIG, code("0000000"))
    }

    @Test
    fun emptyAndNotDigits() {
        assertEquals(Codes.MILEAGE_EMPTY, code(""))
        assertEquals(Codes.MILEAGE_EMPTY, code(" , "))
        assertEquals(Codes.MILEAGE_BAD, code("45.2"))
        assertEquals(Codes.MILEAGE_BAD, code("-5"))
        assertEquals(Codes.MILEAGE_BAD, code("12k"))
        assertEquals(Codes.MILEAGE_BAD, code("٤٥")) // Arabic-Indic digits are not 0-9
    }

    @Test
    fun shownWithCommasAndMiles() {
        assertEquals("45,210 miles", Mileage.format(45210))
        assertEquals("41,000 miles", Mileage.format(41000))
        assertEquals("0 miles", Mileage.format(0))
        assertEquals("999,999 miles", Mileage.format(999_999))
        assertEquals("100 miles", Mileage.format(100))
    }

    @Test
    fun whatIsShownParsesBackToTheSameNumber() {
        val r = random("whatIsShownParsesBackToTheSameNumber")
        repeat(2000) {
            val n = r.nextInt(0, Rules.MILEAGE_MAX + 1)
            assertEquals(Mileage.Ok(n), Mileage.parse(Mileage.grouped(n)))
        }
    }
}
