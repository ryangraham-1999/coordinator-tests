package com.streamlinemyjob.inspect.tools.trimmer

import java.io.File

/**
 * Static checks on the trimmer's PowerShell starters `run.ps1` and `selftest.ps1`
 * (designs/stage-B-trimmer.md 12.4): plain ASCII, CRLF, no forbidden commands, no write under `HKLM:`,
 * no address but the public repo's files, every registry write names `AccessVBOM`, the setting is put
 * back inside a `finally`, and the two files are the same apart from the macro they run.
 * The scripts cannot run in the cloud (no Windows, no Excel); these rules are what can be checked here.
 */
object PsLint {
    const val RUN = "run.ps1"
    const val SELF_TEST = "selftest.ps1"
    const val INSTALL = "install.ps1"
    const val BASE = "https://raw.githubusercontent.com/ryangraham-1999/excel-trimmer/main/"

    /** The only addresses a script may name: the repo folder itself and these files in it. */
    val ALLOWED_FILES = listOf(BasLint.TRIMMER, BasLint.SELF_TEST, BasLint.TEST_DATA, BasLint.KEYS, RUN, SELF_TEST, INSTALL)

    /** Which macro each script runs (12.3 step 6). */
    val MACRO = mapOf(RUN to "TrimStock", SELF_TEST to "RunSelfTest")

    data class Problem(val file: String, val line: Int, val rule: String, val detail: String) {
        override fun toString() = "PSLINT_$rule $file:$line $detail"
    }

    /** Forbidden in code (comments removed), ignoring case. */
    val FORBIDDEN: List<Pair<String, Regex>> = listOf(
        "Start-Process" to """\bStart-Process\b""",
        "Set-ExecutionPolicy" to """\bSet-ExecutionPolicy\b""",
        "Invoke-Expression" to """\b(Invoke-Expression|iex)\b""",
        "RunAs" to """\bRunAs\b""",
    ).map { (name, re) -> name to Regex(re, RegexOption.IGNORE_CASE) }

    private val REGISTRY_WRITE = Regex("""\b(Set-ItemProperty|New-ItemProperty|Remove-ItemProperty)\b""", RegexOption.IGNORE_CASE)
    private val ANY_WRITE = Regex("""\b(Set-ItemProperty|New-ItemProperty|Remove-ItemProperty|New-Item|Remove-Item|Set-Item)\b""", RegexOption.IGNORE_CASE)
    private val NAMES_VBOM = Regex("""-Name\s+AccessVBOM\b""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""https?://[^\s'"]*""", RegexOption.IGNORE_CASE)

    /** Lints the three scripts in [dir]; all must be there. */
    fun checkFolder(dir: File): List<Problem> {
        val problems = mutableListOf<Problem>()
        val texts = mutableMapOf<String, ByteArray>()
        for (name in listOf(RUN, SELF_TEST, INSTALL)) {
            val f = File(dir, name)
            if (!f.isFile) problems += Problem(name, 0, "MISSING", "file not found in ${dir.name}/") else texts[name] = f.readBytes()
        }
        for ((name, bytes) in texts) problems += check(name, bytes)
        val run = texts[RUN]
        val self = texts[SELF_TEST]
        if (run != null && self != null) problems += checkPair(String(run, Charsets.ISO_8859_1), String(self, Charsets.ISO_8859_1))
        return problems
    }

    /** The checks on one script. */
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
        // Addresses are checked in comments too: the how-to line in the header must name the real file.
        for ((i, raw) in lines.withIndex()) {
            for (m in URL.findAll(raw)) {
                val url = m.value
                val ok = url == BASE || (url.startsWith(BASE) && url.removePrefix(BASE) in ALLOWED_FILES)
                if (!ok) problems += Problem(fileName, i + 1, "URL", url)
            }
        }

        // The module names the script downloads are joined onto the base address, so they are checked too.
        for ((i, raw) in lines.withIndex()) {
            val list = Regex("""[$]modules\s*=\s*@\(([^)]*)\)""").find(stripComment(raw)) ?: continue
            for (name in Regex("""'([^']*)'""").findAll(list.groupValues[1]).map { it.groupValues[1] }) {
                if (name !in ALLOWED_FILES) problems += Problem(fileName, i + 1, "MODULE", name)
            }
        }

        var finallyAt = -1
        var restoreSet = false
        var restoreRemove = false
        for ((i, raw) in lines.withIndex()) {
            val n = i + 1
            val code = stripComment(raw)
            for ((name, re) in FORBIDDEN) if (re.containsMatchIn(code)) problems += Problem(fileName, n, "FORBIDDEN", name)
            if (ANY_WRITE.containsMatchIn(code) && Regex("""HKLM:""", RegexOption.IGNORE_CASE).containsMatchIn(code)) {
                problems += Problem(fileName, n, "HKLM_WRITE", code.trim())
            }
            if (REGISTRY_WRITE.containsMatchIn(code) && !NAMES_VBOM.containsMatchIn(code)) {
                problems += Problem(fileName, n, "REGISTRY", "a registry value other than AccessVBOM is written")
            }
            if (finallyAt < 0 && Regex("""\bfinally\b""", RegexOption.IGNORE_CASE).containsMatchIn(code)) finallyAt = n
            if (finallyAt in 1..n && NAMES_VBOM.containsMatchIn(code)) {
                if (Regex("""\bSet-ItemProperty\b""", RegexOption.IGNORE_CASE).containsMatchIn(code)) restoreSet = true
                if (Regex("""\bRemove-ItemProperty\b""", RegexOption.IGNORE_CASE).containsMatchIn(code)) restoreRemove = true
            }
        }
        if (finallyAt < 0 || !restoreSet || !restoreRemove) {
            problems += Problem(fileName, maxOf(finallyAt, 0), "NO_RESTORE", "AccessVBOM must be set back (Set-ItemProperty) or removed (Remove-ItemProperty) inside a finally")
        }

        val macro = MACRO[fileName]
        if (macro != null && lines.none { stripComment(it).trim() == "\$macro = '$macro'" }) {
            problems += Problem(fileName, 0, "MACRO", "must set \$macro = '$macro'")
        }
        return problems
    }

    /** 12.3: `selftest.ps1` is `run.ps1` with only the macro, its own name and one word of the header changed. */
    fun checkPair(runText: String, selfText: String): List<Problem> {
        val normalised = selfText
            .replace("\$macro = 'RunSelfTest'", "\$macro = 'TrimStock'")
            .replace("main/$SELF_TEST | iex", "main/$RUN | iex")
            .replace("runs the self-test there", "runs the macro there")
        if (normalised == runText) return emptyList()
        val a = runText.split("\r\n")
        val b = normalised.split("\r\n")
        val at = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
        return listOf(Problem(SELF_TEST, at + 1, "PAIR", "differs from $RUN beyond the macro name"))
    }

    /** The line without its `#` comment (a `#` outside quotes). */
    private fun stripComment(line: String): String {
        var single = false
        var double = false
        for ((i, ch) in line.withIndex()) {
            when {
                ch == '\'' && !double -> single = !single
                ch == '"' && !single -> double = !double
                ch == '#' && !single && !double -> return line.substring(0, i)
            }
        }
        return line
    }
}
