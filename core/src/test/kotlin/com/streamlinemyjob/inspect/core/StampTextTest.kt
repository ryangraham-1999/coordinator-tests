package com.streamlinemyjob.inspect.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Designs/stage-2-camera.md 4.13 text and fitting rule, 4.8 size text (section 6). */
class StampTextTest {
    private val date = LocalDate.of(2026, 11, 27)

    /** 10 px per code point per 1 px of font, scaled: width = codePoints * fontSize / 2. */
    private val measure = { t: String, size: Int -> t.codePointCount(0, t.length) * size / 2f }

    @Test
    fun textMatchesTheExample() {
        assertEquals("AB12 CDE · 27/11/2026 · Panels › DSF door", Photos.stampText("AB12 CDE", date, "Panels", "DSF door"))
    }

    @Test
    fun bandAndPaddingFollowTheWorkedExamples() {
        assertEquals(68, Photos.stampBandHeight(1500))
        assertEquals(27, Photos.stampPadding(68))
        assertEquals(49, Photos.stampBandHeight(1084))
        assertEquals(24, Photos.stampBandHeight(48))
        assertEquals(24, Photos.stampBandHeight(10))
    }

    @Test
    fun startsAtSixTenthsOfTheBand() {
        val fit = Photos.fitStamp("AB12 CDE", date, "Panels", "DSF door", 68, 1446f, measure)
        assertEquals(41, fit.fontSize)
        assertEquals("AB12 CDE · 27/11/2026 · Panels › DSF door", fit.text)
        assertEquals(29, Photos.fitStamp("A", date, "P", "x", 49, 10_000f, measure).fontSize)
    }

    @Test
    fun shrinksOnePixelAtATimeBeforeCutting() {
        val text = "AB12 CDE · 27/11/2026 · Panels › DSF door" // 41 code points
        // Needs width 41 * size / 2: 600 px fits at size 29 (594.5), not at 30 (615).
        val fit = Photos.fitStamp("AB12 CDE", date, "Panels", "DSF door", 68, 600f, measure)
        assertEquals(text, fit.text)
        assertEquals(29, fit.fontSize)
    }

    @Test
    fun cutsTheAreaNameFromItsEndWithAnEllipsis() {
        // Smallest font for B = 68 is round(27.2) = 27; room for 36 code points there (486 px).
        val fit = Photos.fitStamp("AB12 CDE", date, "Panels", "DSF door", 68, 486f, measure)
        assertEquals(27, fit.fontSize)
        assertEquals("AB12 CDE · 27/11/2026 · Panels › DS…", fit.text)
        assertTrue(measure(fit.text, fit.fontSize) <= 486f)
    }

    @Test
    fun a64By48ImageCutsTheAreaButKeepsRegAndDate() {
        val band = Photos.stampBandHeight(48)
        assertEquals(24, band)
        val avail = (64 - 2 * Photos.stampPadding(band)).toFloat()
        // Realistic widths: about 0.5 em per character.
        val fit = Photos.fitStamp("AB12 CDE", date, "Panels", "DSF door", band, avail, measure)
        assertTrue(fit.text, fit.text.startsWith("AB12 CDE · 27/11/2026 · Panels › "))
        assertTrue(fit.text, fit.text.endsWith("…"))
        assertEquals(10, fit.fontSize)
    }

    @Test
    fun cutsByCodePointsNotChars() {
        val area = "\uD83D\uDE97\uD83D\uDE99\uD83D\uDE95" // three non-BMP characters
        // 10 px per code point; 230 px holds 23 code points; the whole text has 24.
        val fit = Photos.fitStamp("A", date, "P", area, 24, 230f) { t, _ -> t.codePointCount(0, t.length) * 10f }
        assertEquals("A · 27/11/2026 · P › \uD83D\uDE97…", fit.text)
    }

    @Test
    fun formatBytesWorkedExamples() {
        assertEquals("0.6 MB", Photos.formatBytes(640_000))
        assertEquals("7.7 MB", Photos.formatBytes(7_654_321))
        assertEquals("1.23 GB", Photos.formatBytes(1_234_567_890))
        assertEquals("1.00 GB", Photos.formatBytes(999_950_000))
        assertEquals("999.9 MB", Photos.formatBytes(999_949_999))
        assertEquals("41.20 GB", Photos.formatBytes(41_204_000_000))
        assertEquals("0.0 MB", Photos.formatBytes(0))
    }
}
