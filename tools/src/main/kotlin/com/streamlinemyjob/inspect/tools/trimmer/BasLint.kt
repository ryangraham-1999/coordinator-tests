package com.streamlinemyjob.inspect.tools.trimmer

import java.io.File

/**
 * Static checks on the trimmer's `.bas` files (designs/stage-B-trimmer.md 4.9, 6 `BasLint`):
 * `Attribute VB_Name` first, `Option Explicit` before any code, CRLF line ends, plain ASCII, no forbidden
 * words, every procedure under 400 lines, and every `Trimmer.<name>` or shared `UPPER_CASE` constant the
 * self-test uses exists as a `Public` item of `Trimmer.bas`. Comments and string literals are not searched.
 */
object BasLint {
    /** A procedure must be under this many lines, its `Sub`/`Function` and `End` lines included. */
    const val MAX_PROC_LINES = 400

    const val TRIMMER = "Trimmer.bas"
    const val SELF_TEST = "TrimmerSelfTest.bas"
    const val TEST_DATA = "TrimmerTestData.bas"
    const val KEYS = "TrimmerKeys.bas"

    data class Problem(val file: String, val line: Int, val rule: String, val detail: String) {
        override fun toString() = "BASLINT_$rule $file:$line $detail"
    }

    /** Forbidden words (4.9, 9), searched in code with comments and strings removed, ignoring case. */
    val FORBIDDEN: List<Pair<String, Regex>> = listOf(
        "Declare" to """\bDeclare\b""",
        "Shell" to """\bShell\b""",
        "SendKeys" to """\bSendKeys\b""",
        "Kill" to """\bKill\b""",
        "FileCopy" to """\bFileCopy\b""",
        "Open ... For Output" to """\bOpen\b.*\bFor\s+(Output|Append|Binary|Random)\b""",
        "CreateObject" to """\b(CreateObject|GetObject)\b""",
        "Workbooks.Open" to """\bWorkbooks\s*\.\s*Open\b""",
        "Application.Run" to """\bApplication\s*\.\s*Run\b""",
        ".Select" to """\.\s*Select\b""",
        "Selection" to """\bSelection\b""",
        "ActiveWorkbook" to """\bActiveWorkbook\b""",
        "ActiveSheet" to """\bActiveSheet\b""",
        ".Save" to """\.\s*(Save|SaveAs|SaveCopyAs)\b""",
        ".Sort" to """\.\s*Sort\b""",
        "SortBy" to """\bSortBy\b""",
        "dynamic-array function" to """\bWorksheetFunction\s*\.\s*(Filter|Unique|Sequence|XLookup|RandArray)\b""",
        "PtrSafe" to """\bPtrSafe\b""",
        "LongLong" to """\bLongLong\b""",
    ).map { (name, re) -> name to Regex(re, RegexOption.IGNORE_CASE) }

    /** The one allowed `CreateObject` (4.9), checked on the line with its string still in. */
    private val DICTIONARY = Regex("""\bCreateObject\s*\(\s*"Scripting\.Dictionary"\s*\)""", RegexOption.IGNORE_CASE)

    /** `Application.Run` is allowed only with a name written out in quotes (4.9). */
    private val RUN_LITERAL = Regex("""\bApplication\s*\.\s*Run\s*\(?\s*"[^"]*"\s*\)?\s*(,|$)""", RegexOption.IGNORE_CASE)

    private val PROC_START = Regex("""^\s*(?:(?:Public|Private|Friend)\s+)?(?:Static\s+)?(Sub|Function|Property\s+(?:Get|Let|Set))\s+(\w+)""", RegexOption.IGNORE_CASE)
    private val PROC_END = Regex("""^\s*End\s+(Sub|Function|Property)\b""", RegexOption.IGNORE_CASE)

    /** Lints every `.bas` in [dir]; the four trimmer modules must be there (SB.5, 13). The cross-module check runs when both of its files are. */
    fun checkFolder(dir: File): List<Problem> {
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".bas") }.sortedBy { it.name }
        val problems = mutableListOf<Problem>()
        for (required in listOf(TRIMMER, SELF_TEST, TEST_DATA, KEYS)) {
            if (files.none { it.name == required }) problems += Problem(required, 0, "MISSING", "file not found in ${dir.name}/")
        }
        for (f in files) problems += check(f.name, f.readBytes())
        val trimmer = files.firstOrNull { it.name == TRIMMER }
        val selfTest = files.firstOrNull { it.name == SELF_TEST }
        if (trimmer != null && selfTest != null) problems += checkConstants(trimmer.readText(Charsets.US_ASCII), selfTest.readText(Charsets.US_ASCII))
        return problems
    }

    /** The checks on one file, by its file name (`Trimmer.bas` must say `Attribute VB_Name = "Trimmer"`). */
    fun check(fileName: String, bytes: ByteArray): List<Problem> {
        val problems = mutableListOf<Problem>()
        var lineNo = 1
        var asciiReported = false
        var crlfReported = false
        for ((i, byte) in bytes.withIndex()) {
            val b = byte.toInt() and 0xFF
            if (!asciiReported && (b > 126 || (b < 32 && b != 9 && b != 10 && b != 13))) {
                problems += Problem(fileName, lineNo, "NOT_ASCII", "byte $b")
                asciiReported = true
            }
            if (!crlfReported) {
                val bareLf = b == 10 && (i == 0 || bytes[i - 1].toInt() != 13)
                val bareCr = b == 13 && (i == bytes.lastIndex || bytes[i + 1].toInt() != 10)
                if (bareLf || bareCr) {
                    problems += Problem(fileName, lineNo, "NOT_CRLF", if (bareLf) "a line ends with LF alone" else "a CR without LF")
                    crlfReported = true
                }
            }
            if (b == 10) lineNo++
        }
        if (!crlfReported && bytes.isNotEmpty() && !(bytes.size >= 2 && bytes[bytes.size - 2].toInt() == 13 && bytes.last().toInt() == 10)) {
            problems += Problem(fileName, lineNo, "NOT_CRLF", "the last line has no CRLF")
        }

        val lines = String(bytes, Charsets.ISO_8859_1).split("\r\n", "\n", "\r")
        val module = fileName.removeSuffix(".bas")
        val first = lines.firstOrNull().orEmpty()
        if (!Regex("""^Attribute\s+VB_Name\s*=\s*"${Regex.escape(module)}"\s*$""").matches(first)) {
            problems += Problem(fileName, 1, "VB_NAME", "line 1 must be: Attribute VB_Name = \"$module\"")
        }

        val code = logicalLines(lines)
        val firstCode = code.firstOrNull { (_, text) -> text.isNotBlank() && !text.trim().startsWith("Attribute ", ignoreCase = true) }
        val explicitAt = code.firstOrNull { (_, text) -> text.trim().equals("Option Explicit", ignoreCase = true) }
        val beforeExplicit = code.filter { (n, text) ->
            text.isNotBlank() && !text.trim().startsWith("Attribute ", ignoreCase = true) && !text.trim().startsWith("Option ", ignoreCase = true) &&
                (explicitAt == null || n < explicitAt.first)
        }
        if (explicitAt == null || beforeExplicit.isNotEmpty()) {
            problems += Problem(fileName, explicitAt?.first ?: firstCode?.first ?: 1, "OPTION_EXPLICIT", "Option Explicit must come before any code")
        }

        for ((n, text) in code) {
            val noStrings = stripStrings(text)
            for ((name, re) in FORBIDDEN) {
                if (!re.containsMatchIn(noStrings)) continue
                if (name == "CreateObject" && allowedCreateObject(text)) continue
                if (name == "Application.Run" && RUN_LITERAL.containsMatchIn(text)) continue
                problems += Problem(fileName, n, "FORBIDDEN", name)
            }
        }

        var open: Pair<Int, String>? = null
        for ((n, text) in code) {
            val start = PROC_START.find(text)
            if (open == null && start != null && !text.trim().startsWith("Declare", ignoreCase = true)) {
                open = n to start.groupValues[2]
            } else if (open != null && PROC_END.containsMatchIn(text)) {
                val length = n - open.first + 1
                if (length >= MAX_PROC_LINES) problems += Problem(fileName, open.first, "PROC_TOO_LONG", "${open.second} is $length lines")
                open = null
            }
        }
        if (open != null) problems += Problem(fileName, open.first, "PROC_UNCLOSED", open.second)
        return problems
    }

    /**
     * 4.9 and 6: what `TrimmerSelfTest.bas` takes from `Trimmer.bas` must be there as a `Public` item.
     * Every `Trimmer.<name>` must be a `Public` constant, `Sub` or `Function` of `Trimmer.bas`; every
     * `UPPER_CASE_NAME` (with an underscore) the self-test uses but does not declare must be a `Public Const` there.
     */
    fun checkConstants(trimmerText: String, selfTestText: String): List<Problem> {
        val trimmerCode = logicalLines(trimmerText.split("\r\n", "\n")).map { stripStrings(it.second) }
        val publicConsts = trimmerCode.mapNotNull { Regex("""^\s*Public\s+Const\s+(\w+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase() }.toSet()
        val publicProcs = trimmerCode.mapNotNull {
            Regex("""^\s*Public\s+(?:Static\s+)?(?:Sub|Function)\s+(\w+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.uppercase()
        }.toSet()
        val selfCode = logicalLines(selfTestText.split("\r\n", "\n")).map { it.first to stripStrings(it.second) }
        // Declared in the self-test itself: every `<name> As` (variables, parameters, constants) and every `Const <name>`.
        val declared = selfCode.flatMap { (_, t) ->
            (Regex("""\b(\w+)\s+As\b""", RegexOption.IGNORE_CASE).findAll(t) + Regex("""\bConst\s+(\w+)""", RegexOption.IGNORE_CASE).findAll(t))
                .map { it.groupValues[1].uppercase() }.toList()
        }.toSet()
        val problems = mutableListOf<Problem>()
        for ((n, t) in selfCode) {
            if (t.trim().startsWith("Attribute ", ignoreCase = true)) continue
            for (m in Regex("""\bTrimmer\s*\.\s*(\w+)""", RegexOption.IGNORE_CASE).findAll(t)) {
                val name = m.groupValues[1]
                if (name.uppercase() !in publicConsts && name.uppercase() !in publicProcs) {
                    problems += Problem(SELF_TEST, n, "CONST_MISSING", "Trimmer.$name is not Public in $TRIMMER")
                }
            }
            for (m in Regex("""(?<![.\w])([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\b""").findAll(t)) {
                val name = m.groupValues[1]
                if (name.uppercase() in declared) continue
                if (name.uppercase() !in publicConsts) problems += Problem(SELF_TEST, n, "CONST_MISSING", "$name is not a Public Const in $TRIMMER")
            }
        }
        return problems
    }

    private fun allowedCreateObject(text: String): Boolean {
        val all = Regex("""\b(CreateObject|GetObject)\b""", RegexOption.IGNORE_CASE).findAll(stripComment(text)).count()
        return all == DICTIONARY.findAll(stripComment(text)).count()
    }

    /** Lines with comments removed and `_` continuations joined; each keeps the number of its first line. */
    private fun logicalLines(lines: List<String>): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var start = -1
        val acc = StringBuilder()
        for ((i, raw) in lines.withIndex()) {
            val line = stripComment(raw)
            if (start < 0) start = i + 1
            val cont = Regex("""\s_\s*$""").containsMatchIn(line)
            acc.append(if (cont) line.replace(Regex("""\s_\s*$"""), " ") else line)
            if (!cont) {
                out += start to acc.toString()
                acc.clear()
                start = -1
            }
        }
        if (acc.isNotEmpty()) out += start to acc.toString()
        return out
    }

    /** The line without its comment (`'` outside a string, or `Rem` as the first word). */
    private fun stripComment(line: String): String {
        if (Regex("""^\s*Rem(\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return ""
        var inString = false
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') inString = !inString
            if (ch == '\'' && !inString) return line.substring(0, i)
        }
        return line
    }

    /** Every string literal emptied to `""`, so words inside text are not searched. */
    private fun stripStrings(line: String): String = line.replace(Regex("\"(?:[^\"]|\"\")*\""), "\"\"")
}
