package com.streamlinemyjob.inspect.tools.trimmer

import com.streamlinemyjob.inspect.tools.Gradle
import com.streamlinemyjob.inspect.tools.JUnitReports
import java.io.File
import java.io.PrintStream

/**
 * `tools check-trimmer` (designs/stage-B-trimmer.md 4.12, 12.4): phases TRIMRULES, TESTSET, BASLINT and PSLINT, then
 * the VBA phase (the real macro in LibreOffice, [LibreOfficeVba]), whose line always shows. Prints one summary line, `TRIMMER CHECK PASS ...` or `TRIMMER CHECK FAIL <phase>... ...`,
 * then the failures. Exit 0 or 1.
 */
class CheckTrimmer(
    private val root: File,
    private val gradle: Gradle,
    private val out: PrintStream,
    private val seed: Long = TestSetGenerator.SEED,
    private val vba: VbaRunner = LibreOfficeVba,
) {
    val checkDir = File(root, "build/check")
    val trimmerDir = File(root, "trimmer")
    val generatedDir = File(checkDir, "trimmer")

    private data class Phase(val name: String, val counts: String?, val failures: List<String>) {
        val passed get() = failures.isEmpty()
        fun token() = "$name:" + (if (passed) "PASS" else "FAIL") + (counts?.let { "($it)" } ?: "")
    }

    fun run(): Int {
        checkDir.mkdirs()
        val phases = listOf(trimRules(), testSet(), basLint(), psLint())
        val vbaOutcome = vba.run(trimmerDir, File(checkDir, "trimmer-vba"))
        val vbaPhase = (vbaOutcome as? VbaOutcome.Ran)?.let { Phase("VBA", null, if (it.passed) emptyList() else it.failures.ifEmpty { listOf(it.line) }) }
        val failed = (phases + listOfNotNull(vbaPhase)).filter { !it.passed }
        val head = if (failed.isEmpty()) "TRIMMER CHECK PASS" else "TRIMMER CHECK FAIL " + failed.joinToString(" ") { it.name }
        val vbaLine = when (vbaOutcome) {
            is VbaOutcome.Skipped -> vbaOutcome.line
            is VbaOutcome.Ran -> vbaOutcome.line
        }
        out.println("$head  ${phases.joinToString(" ") { it.token() }}  $vbaLine")
        for (p in failed) JUnitReports.cap(p.failures).forEach { out.println("  ${p.name} $it") }
        return if (failed.isEmpty()) 0 else 1
    }

    /** 1. The Kotlin unit tests of the `trimmer` package, re-run every time (they read the committed `trimmer/` files, which Gradle does not watch). */
    private fun trimRules(): Phase {
        val log = File(checkDir, "trimrules.log")
        log.delete()
        val exit = gradle.run("trimrules", listOf(":tools:test", "--tests", "*.trimmer.*", "--rerun"))
        val tally = JUnitReports.Tally(JUnitReports.read(File(root, "tools/build/test-results/test")).cases.filter { ".trimmer." in it.name })
        val failures = mutableListOf<String>()
        failures += tally.failed.map { "${it.name}: ${it.failure}" }
        failures += tally.skipped.map { "${it.name}: skipped" }
        if (tally.total == 0) failures += "no trimmer tests ran"
        if (exit != 0 && tally.failed.isEmpty()) failures += "gradle exit $exit; see build/check/trimrules.log" + Gradle.errorLines(log, 3).joinToString("") { " | $it" }
        return Phase("TRIMRULES", JUnitReports.counts(tally), failures)
    }

    /** 2. Regenerate into build/check/trimmer/ and compare with the committed files. */
    private fun testSet(): Phase {
        generatedDir.deleteRecursively()
        val files = TestSetGenerator.files(seed)
        write(generatedDir, files)
        val failures = mutableListOf<String>()
        for ((path, text) in files) {
            val committed = File(trimmerDir, path)
            if (!committed.isFile || !committed.readBytes().contentEquals(text.toByteArray(Charsets.US_ASCII))) failures += "TESTSET_STALE trimmer/$path"
        }
        val listed = File(trimmerDir, TestSetGenerator.TESTSET_DIR).listFiles()?.filter { it.isFile }.orEmpty()
        for (f in listed.sortedBy { it.name }) {
            if ("${TestSetGenerator.TESTSET_DIR}/${f.name}" !in files) failures += "TESTSET_STALE trimmer/${TestSetGenerator.TESTSET_DIR}/${f.name} (not generated)"
        }
        return Phase("TESTSET", "${files.size}", failures)
    }

    /** 3. The static rules on the committed `.bas` files (all three must be there). */
    private fun basLint(): Phase {
        val bas = trimmerDir.listFiles()?.count { it.isFile && it.name.endsWith(".bas") } ?: 0
        return Phase("BASLINT", "$bas", BasLint.checkFolder(trimmerDir).map { it.toString() })
    }

    /** 4. The static rules on the PowerShell starters `run.ps1` and `selftest.ps1` (12.4; both must be there). */
    private fun psLint(): Phase {
        val ps1 = trimmerDir.listFiles()?.count { it.isFile && it.name.endsWith(".ps1") } ?: 0
        return Phase("PSLINT", "$ps1", PsLint.checkFolder(trimmerDir).map { it.toString() })
    }

    companion object {
        /** Writes the generated files under [dir] (`tools trimmer-testset` writes them into `trimmer/`). */
        fun write(dir: File, files: Map<String, String>) {
            File(dir, TestSetGenerator.TESTSET_DIR).listFiles()?.filter { it.isFile && it.name.endsWith(".listing.txt") }?.forEach { it.delete() }
            for ((path, text) in files) {
                val f = File(dir, path)
                f.parentFile.mkdirs()
                f.writeBytes(text.toByteArray(Charsets.US_ASCII))
            }
        }
    }
}
