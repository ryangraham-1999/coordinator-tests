package com.streamlinemyjob.inspect.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Name and note rules (designs/stage-1-foundation.md 4.7): the worked-example table row by row, then properties on random input. */
class TextTest {
    private fun random(test: String) = TestRandom.forTest("${TextTest::class.qualifiedName}.$test")

    @Test
    fun row1PaddingAndSpaceRunsAreCleaned() {
        val n = Text.checkName("  Kerbed   wheel ")
        assertEquals("Kerbed wheel", n.cleaned)
        assertEquals(12, n.codePoints)
        assertEquals("kerbed wheel", n.key)
        assertTrue(n.ok)
    }

    @Test
    fun row2SameKeyInTheSameListIsNameTaken() {
        val n = Text.checkNewName("DSF", listOf("dsf"))
        assertEquals("DSF", n.cleaned)
        assertEquals(3, n.codePoints)
        assertEquals("dsf", n.key)
        assertEquals(listOf(Codes.NAME_TAKEN), n.problems.map { it.code })
    }

    @Test
    fun row3NfdAndNfcAreTheSameName() {
        val nfd = "Café"
        val nfc = "Café"
        val n = Text.checkNewName(nfd, listOf(nfc))
        assertEquals(nfc, n.cleaned)
        assertEquals(4, n.codePoints)
        assertEquals("café", n.key)
        assertEquals(listOf(Codes.NAME_TAKEN), n.problems.map { it.code })
    }

    @Test
    fun row4FortyCarsIsFortyCodePointsAndOk() {
        val raw = "🚗".repeat(40)
        val n = Text.checkName(raw)
        assertEquals(raw, n.cleaned)
        assertEquals(40, n.codePoints)
        assertEquals(80, n.cleaned.length)
        assertEquals(raw, n.key)
        assertTrue(n.ok)
    }

    @Test
    fun row5FortyOneLettersIsTooLong() {
        val n = Text.checkName("a".repeat(41))
        assertEquals(41, n.codePoints)
        assertEquals(listOf(Codes.NAME_TOO_LONG), n.problems.map { it.code })
    }

    @Test
    fun row6OnlySpacesIsEmpty() {
        val n = Text.checkName("   ")
        assertEquals("", n.cleaned)
        assertEquals(0, n.codePoints)
        assertEquals(listOf(Codes.NAME_EMPTY), n.problems.map { it.code })
    }

    @Test
    fun nonBreakingAndOtherUnicodeSpacesCleanToOneAsciiSpace() {
        assertEquals("Trim kit", Text.clean(" Trim   \tkit\n"))
        assertEquals("Trim kit", Text.clean("Trim　kit"))
    }

    @Test
    fun caseIsKeptAndTheKeyUsesLocaleRoot() {
        assertEquals("TITLE", Text.clean("TITLE"))
        assertEquals("title", Text.key("TITLE"))
    }

    @Test
    fun controlCharactersAreNameBadChar() {
        assertEquals(listOf(Problem(Codes.NAME_BAD_CHAR, "U+0007")), Text.checkName("Bell\u0007").problems)
        assertEquals(listOf(Problem(Codes.NAME_BAD_CHAR, "U+0000")), Text.checkName("a\u0000b").problems)
        assertEquals(listOf(Problem(Codes.NAME_BAD_CHAR, "U+007F")), Text.checkName("del\u007F").problems)
        // Tabs and newlines are whitespace: cleaning turns them into spaces before the check.
        assertTrue(Text.checkName("a\tb\nc").ok)
    }

    @Test
    fun aNameTakenOnlyByKeyNotByAnotherName() {
        assertTrue(Text.checkNewName("DSR", listOf("dsf", "PSF")).ok)
        assertEquals(Problem(Codes.NAME_TAKEN, "Psf"), Text.checkNewName(" psf ", listOf("dsf", "Psf")).problems.single())
    }

    @Test
    fun notesKeepNewlinesCollapseSpacesAndTabsWithinALineAndTrimTheWhole() {
        val n = Text.checkNote("  First  line\t\there \n\n second́ line  ")
        assertEquals("First line here \n\n second́ line".let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }, n.cleaned)
        assertNull(n.problem)
        assertNull(Text.checkNote("  \n \t ").cleaned)
        assertNull(Text.checkNote(null).cleaned)
        assertNull(Text.checkNote("é".repeat(Rules.NOTE_MAX)).problem)
        assertEquals(Rules.NOTE_MAX, Text.checkNote("🚗".repeat(Rules.NOTE_MAX)).codePoints)
        assertEquals(Codes.NOTE_TOO_LONG, Text.checkNote("a".repeat(Rules.NOTE_MAX + 1)).problem?.code)
    }

    /** Random raw strings: letters, accents (composed and combining), spaces of every kind, non-BMP, controls. */
    private fun randomRaw(r: kotlin.random.Random): String {
        val parts = listOf("a", "Z", "é", "é", "ł", "Ü", "ü", " ", "  ", " ", "\t", "\n", " ",
            "🚗", "𝔸", "'", "\"", "&", "/", "(", "7", "\u0007")
        return (0 until r.nextInt(0, 30)).joinToString("") { parts[r.nextInt(parts.size)] }
    }

    @Test
    fun cleaningTwiceEqualsCleaningOnceAndTheKeyOfTheCleanedNameIsTheKeyOfTheRaw() {
        val r = random("cleaningTwiceEqualsCleaningOnceAndTheKeyOfTheCleanedNameIsTheKeyOfTheRaw")
        repeat(2000) {
            val raw = randomRaw(r)
            val cleaned = Text.clean(raw)
            assertEquals(raw, cleaned, Text.clean(cleaned))
            assertEquals(raw, Text.key(raw), Text.key(cleaned))
            assertTrue(raw, !cleaned.startsWith(" ") && !cleaned.endsWith(" ") && !cleaned.contains("  "))
            val note = Text.cleanNote(raw)
            assertEquals(raw, note, Text.cleanNote(note))
        }
    }

    @Test
    fun lengthIsCountedInCodePointsForEveryRandomName() {
        val r = random("lengthIsCountedInCodePointsForEveryRandomName")
        repeat(2000) {
            val raw = randomRaw(r)
            val n = Text.checkName(raw)
            val expected = n.cleaned.codePoints().count().toInt()
            assertEquals(raw, expected, n.codePoints)
            assertEquals(raw, expected == 0, n.problems.any { it.code == Codes.NAME_EMPTY })
            assertEquals(raw, expected > Rules.NAME_MAX, n.problems.any { it.code == Codes.NAME_TOO_LONG })
            assertEquals(raw, raw.contains('\u0007'), n.problems.any { it.code == Codes.NAME_BAD_CHAR })
        }
    }

    /** S1.7's regex, kept here only as the reference: Java runs it, Android cannot (S1.30). */
    private val javaUnicodeWhitespace = Regex("(?U)\\s")
    private val javaUnicodeWhitespaceRun = Regex("(?U)\\s+")

    @Test
    fun whitespaceCheckMatchesJavaUnicodeWhitespaceForEveryCodePoint() {
        var count = 0
        for (cp in 0..Character.MAX_CODE_POINT) {
            val expected = javaUnicodeWhitespace.matches(String(Character.toChars(cp)))
            assertEquals("U+%04X".format(cp), expected, Text.isWhitespace(cp))
            if (expected) count++
        }
        assertEquals(25, count)
    }

    @Test
    fun cleanGivesTheSameResultAsTheUnicodeRegexOnRandomInput() {
        val r = random("cleanGivesTheSameResultAsTheUnicodeRegexOnRandomInput")
        val spaces = (0..0x3000).filter { javaUnicodeWhitespace.matches(String(Character.toChars(it))) }.map { it.toChar().toString() }
        val others = listOf("a", "é", "e\u0301", "🚗", "\u0007", "\u001C", "\u001F", "\u200B", "\uFEFF", "\u180E", "x")
        repeat(5000) {
            val raw = (0 until r.nextInt(0, 30)).joinToString("") {
                if (r.nextBoolean()) spaces[r.nextInt(spaces.size)] else others[r.nextInt(others.size)]
            }
            val nfc = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
            assertEquals(raw, javaUnicodeWhitespaceRun.replace(nfc, " ").trim(' '), Text.clean(raw))
        }
    }
}
