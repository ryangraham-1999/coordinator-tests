package com.streamlinemyjob.inspect.testdata

import java.util.Random

/**
 * Generated regs (designs/stage-1-foundation.md 4.15): unique reg keys in mixed formats, each typed
 * in a random messy form (lowercase, extra spaces, hyphens) so that cleaning is exercised. The
 * formats are only for variety: the app never checks a reg against a pattern (4.8).
 */
class RegGenerator(private val random: Random) {
    enum class Format {
        /** `AB12 CDE` */ GB_CURRENT,
        /** `A123 BCD` */ GB_PREFIX,
        /** `ABC 123D` */ GB_SUFFIX,
        /** `ABZ 1234`, `XYZ 12` */ NI,
        /** `A 1`, `1234 AB` */ DATELESS,
    }

    /** What is typed, the reg key it must clean to, and the format it was made in. */
    data class Generated(val typed: String, val key: String, val format: Format)

    private val keys = HashSet<String>()

    /** The next reg; its key was not given out before by this generator. */
    fun next(): Generated {
        repeat(MAX_TRIES) {
            val format = Format.entries[random.nextInt(Format.entries.size)]
            val (front, back) = parts(format)
            val key = front + back
            if (keys.add(key)) return Generated(messy(front, back), key, format)
        }
        error("RegGenerator found no new key in $MAX_TRIES tries")
    }

    private fun letters(n: Int) = String(CharArray(n) { 'A' + random.nextInt(26) })
    private fun digits(n: Int) = String(CharArray(n) { '0' + random.nextInt(10) })
    private fun between(min: Int, max: Int) = min + random.nextInt(max - min + 1)

    /** The two halves of the plate as it is printed (upper case, no separators). */
    private fun parts(format: Format): Pair<String, String> = when (format) {
        Format.GB_CURRENT -> letters(2) + digits(2) to letters(3)
        Format.GB_PREFIX -> letters(1) + digits(between(1, 3)) to letters(3)
        Format.GB_SUFFIX -> letters(3) to digits(between(1, 3)) + letters(1)
        Format.NI -> {
            val l = letters(3).toCharArray()
            l[1 + random.nextInt(2)] = if (random.nextBoolean()) 'I' else 'Z'
            String(l) to digits(between(1, 4))
        }
        Format.DATELESS ->
            if (random.nextBoolean()) letters(between(1, 3)) to digits(between(1, 4))
            else digits(between(1, 4)) to letters(between(1, 3))
    }

    /** Random case per character, a messy separator, sometimes a stray hyphen or space inside, padding. */
    private fun messy(front: String, back: String): String {
        fun mixCase(s: String) = s.map { if (random.nextInt(3) == 0) it.lowercaseChar() else it }.joinToString("")
        fun stray(s: String): String {
            if (s.length < 2 || random.nextInt(5) != 0) return s
            val at = 1 + random.nextInt(s.length - 1)
            return s.substring(0, at) + (if (random.nextBoolean()) "-" else " ") + s.substring(at)
        }
        val separator = SEPARATORS[random.nextInt(SEPARATORS.size)]
        val pad = { PADDING[random.nextInt(PADDING.size)] }
        return pad() + stray(mixCase(front)) + separator + stray(mixCase(back)) + pad()
    }

    private companion object {
        const val MAX_TRIES = 10_000
        val SEPARATORS = listOf(" ", " ", " ", "", "  ", "   ", "-", " - ", "--")
        val PADDING = listOf("", "", "", " ", "  ")
    }
}
