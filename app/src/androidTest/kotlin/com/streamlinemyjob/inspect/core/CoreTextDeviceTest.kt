package com.streamlinemyjob.inspect.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Core's `Text` loads and cleans on a real Android runtime (S1.30): S1.7's `(?U)` regex passed
 * every JVM test but stopped the class loading on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class CoreTextDeviceTest {
    /** The 25 Unicode White_Space code points (designs/stage-1-foundation.md 4.7). */
    private val whitespace = (0x09..0x0D) + listOf(0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A) +
        listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)

    @Test
    fun everyUnicodeWhitespaceBecomesOneSpace() {
        assertEquals(25, whitespace.size)
        for (cp in whitespace) {
            val s = String(Character.toChars(cp))
            assertTrue("U+%04X".format(cp), Text.isWhitespace(cp))
            assertEquals("U+%04X".format(cp), "Trim kit", Text.clean("${s}Trim$s$s${s}kit$s"))
        }
    }

    @Test
    fun lookalikesAreNotWhitespace() {
        for (cp in listOf(0x1C, 0x1D, 0x1E, 0x1F, 0x180E, 0x200B, 0xFEFF, 0x41)) {
            assertFalse("U+%04X".format(cp), Text.isWhitespace(cp))
            val s = String(Character.toChars(cp))
            assertEquals("U+%04X".format(cp), "a${s}b", Text.clean("a${s}b"))
        }
    }

    @Test
    fun workedExamplesOf47HoldOnTheDevice() {
        val kerbed = Text.checkName("  Kerbed   wheel ")
        assertEquals("Kerbed wheel", kerbed.cleaned)
        assertEquals("kerbed wheel", kerbed.key)
        assertEquals(12, kerbed.codePoints)
        assertTrue(kerbed.ok)
        assertEquals(Codes.NAME_TAKEN, Text.checkNewName("DSF", listOf("dsf")).problems.single().code)
        assertEquals(Codes.NAME_TAKEN, Text.checkNewName("Café", listOf("Café")).problems.single().code)
        assertTrue(Text.checkName("🚗".repeat(Rules.NAME_MAX)).ok)
        assertEquals(Codes.NAME_TOO_LONG, Text.checkName("a".repeat(Rules.NAME_MAX + 1)).problems.single().code)
        assertEquals(Codes.NAME_EMPTY, Text.checkName("   ").problems.single().code)
        assertEquals("First line\nsecond", Text.checkNote("  First \t line\nsecond  ").cleaned)
    }
}
