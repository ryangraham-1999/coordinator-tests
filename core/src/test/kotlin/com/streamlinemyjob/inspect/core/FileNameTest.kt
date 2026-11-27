package com.streamlinemyjob.inspect.core

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Designs/stage-2-camera.md 4.12 names, worked examples 1-4 (section 6). */
class FileNameTest {
    private val date = LocalDate.of(2026, 11, 27)

    @Test
    fun example1() {
        val name = Photos.fileName("AB12CDE", date, "DSF door", LocalTime.of(14, 32, 5))
        assertEquals("AB12CDE 2026-11-27 DSF door 143205.jpg", name)
        assertEquals("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg", Photos.relPath("AB12CDE", name))
        assertEquals("AB12CDE", Photos.subfolder("AB12CDE"))
    }

    @Test
    fun example2SecondInTheSameSecondAndThird() {
        val t = LocalTime.of(14, 32, 5)
        val first = Photos.fileName("AB12CDE", date, "DSF door", t)
        val taken = mutableSetOf(first)
        val second = Photos.fileName("AB12CDE", date, "DSF door", t) { it in taken }
        assertEquals("AB12CDE 2026-11-27 DSF door 143205 2.jpg", second)
        taken += second
        assertEquals("AB12CDE 2026-11-27 DSF door 143205 3.jpg", Photos.fileName("AB12CDE", date, "DSF door", t) { it in taken })
    }

    @Test
    fun example3BadCharactersBecomeDashes() {
        val name = Photos.fileName("XYZ1234", date, "Trim / \"Mint\" é", LocalTime.of(9, 0, 0))
        assertEquals("XYZ1234 2026-11-27 Trim - -Mint- é 090000.jpg", name)
    }

    @Test
    fun example4LongAreaCutTo40CodePoints() {
        val area = "a".repeat(45)
        val name = Photos.fileName("AB12CDE", date, area, LocalTime.of(0, 0, 1))
        assertEquals("AB12CDE 2026-11-27 ${"a".repeat(40)} 000001.jpg", name)
    }

    @Test
    fun everyBadCharacterIsRemovedAndNothingElseIsSafeToKeep() {
        for (c in "/\\:*?\"<>|") {
            val name = Photos.fileName("A1", date, "x${c}y", LocalTime.of(1, 2, 3))
            assertEquals("A1 2026-11-27 x-y 010203.jpg", name)
        }
        val name = Photos.fileName("A1", date, "a\u0001b\u007Fc", LocalTime.of(1, 2, 3))
        assertEquals("A1 2026-11-27 a-b-c 010203.jpg", name)
        assertFalse(Photos.fileName("A1", date, "q/r", LocalTime.MIDNIGHT).substringAfter("A1 ").contains('/'))
    }

    @Test
    fun whitespaceCollapsesAndEmptyBecomesArea() {
        assertEquals("A1 2026-11-27 a b 000000.jpg", Photos.fileName("A1", date, "  a \t\n  b  ", LocalTime.MIDNIGHT))
        assertEquals("A1 2026-11-27 area 000000.jpg", Photos.fileName("A1", date, "  \t ", LocalTime.MIDNIGHT))
        assertEquals("A1 2026-11-27 area 000000.jpg", Photos.fileName("A1", date, "", LocalTime.MIDNIGHT))
    }

    @Test
    fun cutsBetweenCodePointsAndKeepsAccentsAndNonBmp() {
        val area = "🚗".repeat(41) // 41 cars, 82 chars
        val name = Photos.fileName("A1", date, area, LocalTime.MIDNIGHT)
        assertEquals("A1 2026-11-27 ${"🚗".repeat(40)} 000000.jpg", name)
        assertEquals("A1 2026-11-27 Ünï'cøde 000000.jpg", Photos.fileName("A1", date, "Ünï'cøde", LocalTime.MIDNIGHT))
    }
}
