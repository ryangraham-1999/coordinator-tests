package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reg rules (designs/stage-1-foundation.md 4.8): the worked-example table row by row, then properties. */
class RegTest {
    private fun random(test: String) = TestRandom.forTest("${RegTest::class.qualifiedName}.$test")

    private fun ok(typed: String) = Reg.check(typed) as Reg.Ok
    private fun refused(typed: String) = (Reg.check(typed) as Reg.Refused).problem

    @Test
    fun row1GbCurrentFormat() = assertEquals(Reg.Ok("AB12 CDE", "AB12CDE"), Reg.check("ab12 cde"))

    @Test
    fun row2NiFormat() = assertEquals(Reg.Ok("XYZ 1234", "XYZ1234"), Reg.check("  xyz   1234 "))

    @Test
    fun row3SameKeyAsAnExistingVehicleIsRegTakenWithItsId() {
        val existing = Reg.check("AB12 CDE") as Reg.Ok
        val vehicleId = java.util.UUID.randomUUID().toString()
        val result = Reg.checkNew("AB12CDE", mapOf(existing.key to vehicleId)) as Reg.Refused
        assertEquals(Problem(Codes.REG_TAKEN, "AB12CDE"), result.problem)
        assertEquals(vehicleId, result.existingVehicleId)
    }

    @Test
    fun row4DatelessPlateTwoKeyCharactersIsTheMinimum() = assertEquals(Reg.Ok("A 1", "A1"), Reg.check("a 1"))

    @Test
    fun row5OneCharacterIsTooShort() = assertEquals(Codes.REG_TOO_SHORT, refused("X").code)

    @Test
    fun row6AnAccentedLetterIsBadChar() = assertEquals(Problem(Codes.REG_BAD_CHAR, "É"), refused("AB12 CDÉ"))

    @Test
    fun row7SearchMatchesByAPartOfTheKey() {
        assertEquals("12CD", Reg.searchKey("12 cd"))
        assertTrue(Reg.matches(ok("AB12 CDE").key, "12 cd"))
    }

    @Test
    fun hyphensAreKeptInTheDisplayAndRemovedFromTheKey() {
        assertEquals(Reg.Ok("AB-12 CDE", "AB12CDE"), Reg.check(" ab-12  cde"))
    }

    @Test
    fun noGbPatternIsChecked() {
        for (typed in listOf("ABZ 1234", "A 1", "1234 AB", "ABC 123D", "A123 BCD", "QQQQQQQQQQ", "1111")) {
            assertTrue(typed, Reg.check(typed) is Reg.Ok)
        }
    }

    @Test
    fun lengthLimits() {
        assertEquals(Codes.REG_TOO_LONG, refused("A".repeat(Rules.REG_KEY_MAX + 1)).code)
        assertTrue(Reg.check("A".repeat(Rules.REG_KEY_MAX)) is Reg.Ok)
        // 20 typed characters with a 10-character key is fine; 21 typed is too long whatever the key.
        assertTrue(Reg.check("A B C D E F G H I J ") is Reg.Ok)
        assertEquals(Codes.REG_TOO_LONG, refused("A B C D E F G H I J  ").code)
        assertEquals(Codes.REG_TOO_SHORT, refused("").code)
        assertEquals(Codes.REG_TOO_SHORT, refused(" - ").code)
    }

    @Test
    fun badCharactersAreNamed() {
        assertEquals(Problem(Codes.REG_BAD_CHAR, "."), refused("AB.12"))
        assertEquals(Problem(Codes.REG_BAD_CHAR, " "), refused("AB 12"))
        assertEquals(Problem(Codes.REG_BAD_CHAR, "🚗"), refused("AB🚗"))
    }

    @Test
    fun searchEmptyMatchesAllAndABadCharacterMatchesNothing() {
        val key = ok("XYZ 1234").key
        assertTrue(Reg.matches(key, ""))
        assertTrue(Reg.matches(key, "  "))
        assertNull(Reg.searchKey("12é"))
        assertFalse(Reg.matches(key, "12é"))
        assertFalse(Reg.matches(key, "ZZ"))
    }

    @Test
    fun theKeyOfTheDisplayIsTheKeyOfWhatWasTypedAndCleaningTwiceChangesNothing() {
        val r = random("theKeyOfTheDisplayIsTheKeyOfWhatWasTypedAndCleaningTwiceChangesNothing")
        val chars = "aAbBzZ09-  "
        repeat(3000) {
            val typed = (0 until r.nextInt(0, 22)).map { chars[r.nextInt(chars.length)] }.joinToString("")
            when (val result = Reg.check(typed)) {
                is Reg.Ok -> {
                    assertEquals(typed, result.key, Reg.key(result.display))
                    assertEquals(typed, result, Reg.check(result.display))
                    assertTrue(typed, Reg.matches(result.key, result.display))
                }
                is Reg.Refused -> assertTrue(typed, result.problem.code in setOf(Codes.REG_TOO_SHORT, Codes.REG_TOO_LONG))
            }
        }
    }
}
