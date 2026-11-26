package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Reg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated regs (designs/stage-1-foundation.md 4.15, 6): 10,000 unique keys, every format, messy typing that cleans. */
class RegGeneratorTest {
    private fun random(test: String) = TestRandom.forTest("${RegGeneratorTest::class.qualifiedName}.$test")

    @Test
    fun tenThousandGeneratedRegKeysAreUniqueAndEachTypedFormCleansToItsKey() {
        val g = RegGenerator(random("tenThousandGeneratedRegKeysAreUniqueAndEachTypedFormCleansToItsKey"))
        val regs = List(10_000) { g.next() }
        val keys = regs.map { (Reg.check(it.typed) as? Reg.Ok ?: error("refused: '${it.typed}' ${Reg.check(it.typed)}")).key }
        assertEquals(10_000, keys.toSet().size)
        assertEquals(regs.map { it.key }, keys)
    }

    @Test
    fun everyFormatIsMadeAndTheTypingIsMessy() {
        val g = RegGenerator(random("everyFormatIsMadeAndTheTypingIsMessy"))
        val regs = List(2_000) { g.next() }
        assertEquals(RegGenerator.Format.entries.toSet(), regs.map { it.format }.toSet())
        val displays = regs.map { (Reg.check(it.typed) as Reg.Ok).display }
        assertTrue("lower case typed", regs.any { r -> r.typed.any { it.isLowerCase() } })
        assertTrue("hyphens typed", regs.any { '-' in it.typed })
        assertTrue("extra spaces typed", regs.any { it.typed.startsWith(" ") || it.typed.endsWith(" ") || "  " in it.typed })
        assertTrue("typed differs from display", regs.zip(displays).count { (r, d) -> r.typed != d } > 1_000)
        assertTrue("2-character keys", regs.any { it.key.length == 2 })
    }
}
