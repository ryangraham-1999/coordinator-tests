package com.streamlinemyjob.inspect.core

import java.text.Normalizer
import java.util.Locale

/**
 * Name and note rules (designs/stage-1-foundation.md 4.7). The app, the seed validator and the
 * generators all call these; nothing else cleans or compares names.
 */
object Text {
    /**
     * Unicode whitespace: the 25 code points with the Unicode `White_Space` property, the same set
     * Java's Unicode-class `\s` matches (U+00A0 and the other spaces too). Listed by hand because
     * Android's regex engine does not support the Unicode-character-class flag: a regex using it
     * stops this class loading on a phone (S1.30). All of them are in the BMP, so one `Char` each.
     */
    private val WHITESPACE: Set<Char> = buildSet {
        for (c in '\u0009'..'\u000D') add(c)
        add('\u0020'); add('\u0085'); add('\u00A0'); add('\u1680')
        for (c in '\u2000'..'\u200A') add(c)
        add('\u2028'); add('\u2029'); add('\u202F'); add('\u205F'); add('\u3000')
    }

    /** True when [codePoint] is Unicode whitespace (see [WHITESPACE]). */
    fun isWhitespace(codePoint: Int): Boolean =
        codePoint <= 0xFFFF && codePoint.toChar() in WHITESPACE

    /** Within one line of a note: runs of spaces and tabs (4.7). */
    private val LINE_SPACE_RUN = Regex("[ \\t]+")

    /** Clean a raw name: NFC, trim, every run of whitespace becomes one ASCII space. Case is kept. */
    fun clean(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val out = StringBuilder(nfc.length)
        var inRun = false
        for (c in nfc) {
            if (c in WHITESPACE) {
                if (!inRun) out.append(' ')
                inRun = true
            } else {
                out.append(c)
                inRun = false
            }
        }
        return out.toString().trim(' ')
    }

    /** Length in Unicode code points (never `String.length`). */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** The name key: the cleaned name lowercased with `Locale.ROOT`. Equal keys mean "the same name". */
    fun key(raw: String): String = clean(raw).lowercase(Locale.ROOT)

    /** The result of checking one name: what is stored, its key, its length and what is wrong (empty when ok). */
    data class Name(val cleaned: String, val key: String, val codePoints: Int, val problems: List<Problem>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /** Checks a raw list or value name against 4.7: `NAME_EMPTY`, `NAME_TOO_LONG`, `NAME_BAD_CHAR <U+XXXX>`. */
    fun checkName(raw: String): Name {
        val cleaned = clean(raw)
        val length = codePoints(cleaned)
        val problems = mutableListOf<Problem>()
        if (length == 0) problems += Problem(Codes.NAME_EMPTY)
        if (length > Rules.NAME_MAX) problems += Problem(Codes.NAME_TOO_LONG, "$length")
        controlCharacters(cleaned).forEach { problems += Problem(Codes.NAME_BAD_CHAR, it) }
        return Name(cleaned, cleaned.lowercase(Locale.ROOT), length, problems)
    }

    /**
     * Checks a new name for a place that already holds [existing] names (a list's values, or the
     * lists): 4.7's rules, plus `NAME_TAKEN` when the key equals an existing name's key.
     */
    fun checkNewName(raw: String, existing: Collection<String>): Name {
        val name = checkName(raw)
        if (!name.ok) return name
        val taken = existing.firstOrNull { key(it) == name.key } ?: return name
        return name.copy(problems = listOf(Problem(Codes.NAME_TAKEN, clean(taken))))
    }

    /**
     * Cleans a finding's note (4.7): NFC; runs of spaces and tabs within a line become one space;
     * internal newlines are kept; whitespace at the start and end of the whole note is trimmed.
     */
    fun cleanNote(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        return nfc.split('\n').joinToString("\n") { LINE_SPACE_RUN.replace(it, " ") }.trim()
    }

    /** A checked note: [cleaned] is null for no note (length 0); [problem] is `NOTE_TOO_LONG` over 500 code points. */
    data class Note(val cleaned: String?, val codePoints: Int, val problem: Problem?)

    fun checkNote(raw: String?): Note {
        val cleaned = cleanNote(raw ?: "")
        val length = codePoints(cleaned)
        val problem = if (length > Rules.NOTE_MAX) Problem(Codes.NOTE_TOO_LONG, "$length") else null
        return Note(cleaned.ifEmpty { null }, length, problem)
    }

    /** Every control character (Unicode category Cc) left after cleaning, as `U+XXXX`, in order. */
    private fun controlCharacters(cleaned: String): List<String> =
        cleaned.codePoints().toArray()
            .filter { Character.getType(it) == Character.CONTROL.toInt() }
            .map { "U+%04X".format(it) }
}
