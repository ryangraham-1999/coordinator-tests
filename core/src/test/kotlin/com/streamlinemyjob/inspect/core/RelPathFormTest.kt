package com.streamlinemyjob.inspect.core

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Designs/stage-2-camera.md 4.6 `IC_PHOTO_FIELDS` (a `rel_path` not of 4.12's form): what [Photos.isRelPathForm] accepts. */
class RelPathFormTest {
    private val date = LocalDate.of(2026, 11, 27)

    @Test
    fun everyNameFileNameMakesIsOfTheForm() {
        val areas = listOf("DSF door", "Trim / \"Mint\" é", "a".repeat(45), "   ", "", "\t\n", "𝔸🚗 x", "a:b*c?d")
        for (regKey in listOf("AB12CDE", "A1", "ABCDEFGHIJ")) {
            for (area in areas) {
                val taken = mutableSetOf<String>()
                repeat(3) {
                    val name = Photos.fileName(regKey, date, area, LocalTime.of(0, 0, 5)) { it in taken }
                    taken += name
                    assertTrue(name, Photos.isRelPathForm(Photos.relPath(regKey, name)))
                }
            }
        }
    }

    @Test
    fun theWorkedExampleIsOfTheForm() {
        assertTrue(Photos.isRelPathForm("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg"))
        assertTrue(Photos.isRelPathForm("AB12CDE/AB12CDE 2026-11-27 DSF door 143205 12.jpg"))
    }

    @Test
    fun otherPathsAreNot() {
        val bad = listOf(
            "", "x.jpg", "AB12CDE/x.jpg", "AB12CDE/AB12CDE 2026-11-27 DSF door 143205.png",
            "AB12CDE/AB12CDE 2026-13-27 DSF door 143205.jpg", "AB12CDE/AB12CDE 2026-11-27 DSF door 14320.jpg",
            "AB12CDE/XY12CDE 2026-11-27 DSF door 143205.jpg", "ab12cde/ab12cde 2026-11-27 DSF door 143205.jpg",
            "A/A 2026-11-27 DSF door 143205.jpg", "AB12CDE/sub/AB12CDE 2026-11-27 DSF door 143205.jpg",
            "AB12CDE/AB12CDE 2026-11-27 DSF/door 143205.jpg", "AB12CDE/AB12CDE 2026-11-27 DSF\"door 143205.jpg",
            "AB12CDE/AB12CDE 2026-11-27 DSF\u0001door 143205.jpg", "AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg.part",
            "ABCDEFGHIJK/ABCDEFGHIJK 2026-11-27 DSF door 143205.jpg",
        )
        for (p in bad) assertFalse(p, Photos.isRelPathForm(p))
        assertEquals(15, bad.size)
    }
}
