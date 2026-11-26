package com.streamlinemyjob.inspect.tools

import java.io.File

/**
 * `tools device-report` (designs/stage-1-foundation.md 4.5, 4.19.4): reads the device test
 * reports and prints the summary text the workflow puts in the `smj-device` check run. The
 * device phase in actions mode parses the same text back with [parse].
 *
 * First line: `DEVICE PASS(<n>)`, `DEVICE PASS(<n>, <k> skipped)`, `DEVICE FAIL(<passed>/<total>)`
 * or, when no test ran, `DEVICE_NONE`. Then up to 20 lines `DEVICE <class>.<test>: <message>`,
 * and one `DEVICE_SKIPPED <class>.<test>` line per skipped test.
 */
object DeviceReport {
    /** Where AGP writes device test reports, searched recursively (4.5: no sub-folder names). */
    fun reportDir(root: File) = File(root, "app/build/outputs/androidTest-results")

    fun text(t: JUnitReports.Tally): String {
        val lines = mutableListOf<String>()
        lines += when {
            t.total == 0 -> "DEVICE_NONE"
            t.failed.isNotEmpty() -> "DEVICE FAIL(${t.passed}/${t.ran})"
            t.skipped.isNotEmpty() -> "DEVICE PASS(${t.ran}, ${t.skipped.size} skipped)"
            else -> "DEVICE PASS(${t.ran})"
        }
        lines += JUnitReports.cap(t.failed.map { "DEVICE ${it.name}: ${it.failure}" })
        lines += t.skipped.map { "DEVICE_SKIPPED ${it.name}" }
        return lines.joinToString("\n")
    }

    /**
     * The device phase's reading of a summary text: zero tests → `DEVICE_NONE`; any skipped test →
     * FAIL with `DEVICE_SKIPPED <test>`; `DEVICE FAIL(p/t)` → FAIL with its failure lines.
     * A text whose first line is none of the forms → `DEVICE_NO_RESULT`.
     */
    fun parse(summary: String, suffix: String?): PhaseResult {
        val lines = summary.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val first = lines.firstOrNull() ?: return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NO_RESULT (empty summary)"))
        val rest = lines.drop(1)
        val sfx = if (suffix != null) ", $suffix" else ""
        PASS.matchEntire(first)?.let { m ->
            val n = m.groupValues[1].toInt()
            val k = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (n == 0 && k == 0) return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NONE"))
            if (k == 0) return PhaseResult(Status.PASS, "$n$sfx")
            val named = rest.filter { it.startsWith("DEVICE_SKIPPED ") }
            val failures = named.ifEmpty { listOf("DEVICE_SKIPPED ($k tests, not named in the summary)") }
            return PhaseResult(Status.FAIL, "$n, $k skipped$sfx", failures = failures)
        }
        FAIL.matchEntire(first)?.let { m ->
            return PhaseResult(Status.FAIL, "${m.groupValues[1]}/${m.groupValues[2]}$sfx", failures = rest.ifEmpty { listOf("DEVICE (no failure lines in the summary)") })
        }
        if (first == "DEVICE_NONE") return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NONE"))
        return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NO_RESULT (summary not understood: $first)"))
    }

    private val PASS = Regex("""DEVICE PASS\((\d+)(?:, (\d+) skipped)?\)""")
    private val FAIL = Regex("""DEVICE FAIL\((\d+)/(\d+)\)""")

    fun run(root: File, args: List<String>, out: java.io.PrintStream): Int {
        val dirs = if (args.isEmpty()) listOf(reportDir(root)) else args.map { File(it) }
        val tally = dirs.fold(JUnitReports.EMPTY) { acc, d -> acc + JUnitReports.read(d) }
        out.println(text(tally))
        return 0
    }
}
