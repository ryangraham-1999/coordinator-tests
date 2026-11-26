package com.streamlinemyjob.inspect.tools.trimmer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** `PsLint` against designs/stage-B-trimmer.md 12.4: the committed scripts pass, and each fault, planted in turn, fails its own rule. */
class PsLintTest {
    private val run = File("../trimmer/${PsLint.RUN}").readText(Charsets.ISO_8859_1)
    private val self = File("../trimmer/${PsLint.SELF_TEST}").readText(Charsets.ISO_8859_1)

    private fun rules(text: String, file: String = PsLint.RUN) = PsLint.check(file, text.toByteArray(Charsets.ISO_8859_1)).map { it.rule }

    /** [text] with one extra code line put just inside the script block. */
    private fun withLine(line: String, text: String = run) = text.replaceFirst("& {\r\n", "& {\r\n    $line\r\n")

    @Test
    fun theCommittedScriptsPass() {
        assertEquals(emptyList<PsLint.Problem>(), PsLint.checkFolder(File("../trimmer")))
    }

    @Test
    fun lfOnlyOrNoLastCrlfFails() {
        assertEquals(listOf("NOT_CRLF"), rules(run.replace("\r\n", "\n")))
        assertEquals(listOf("NOT_CRLF"), rules(run.removeSuffix("\r\n")))
    }

    @Test
    fun aNonAsciiCharacterFails() {
        assertEquals(listOf("NOT_ASCII"), rules(withLine("# price in £")))
    }

    @Test
    fun eachForbiddenCommandFailsOnItsOwn() {
        val bad = mapOf(
            "Start-Process" to "Start-Process notepad",
            "Set-ExecutionPolicy" to "Set-ExecutionPolicy Bypass -Scope Process",
            "Invoke-Expression" to "Invoke-Expression 'x'",
            "RunAs" to "Start-Job -Verb RunAs",
        )
        for ((name, line) in bad) {
            val problems = PsLint.check(PsLint.RUN, withLine(line).toByteArray(Charsets.ISO_8859_1))
            assertEquals(line, listOf("FORBIDDEN"), problems.map { it.rule }.distinct())
            assertEquals(line, true, problems.any { it.detail == name })
        }
        assertEquals(listOf("FORBIDDEN"), rules(withLine("'x' | iex")))
    }

    @Test
    fun aForbiddenWordInACommentIsAllowed() {
        assertEquals(emptyList<String>(), rules(withLine("# never Start-Process here")))
    }

    @Test
    fun aWriteUnderHklmFails() {
        assertEquals(listOf("HKLM_WRITE"), rules(withLine("New-Item -Path 'HKLM:\\Software\\X' -Force")))
    }

    @Test
    fun aRegistryValueOtherThanAccessVbomFails() {
        assertEquals(listOf("REGISTRY"), rules(withLine("Set-ItemProperty -Path \$userKey -Name VBAWarnings -Value 1")))
    }

    @Test
    fun anotherAddressFails() {
        assertEquals(listOf("URL"), rules(withLine("\$x = 'https://example.com/a.bas'")))
        assertEquals(listOf("URL"), rules(withLine("\$x = '${PsLint.BASE}Other.bas'")))
        assertEquals(listOf("URL"), rules(run.replace("/main/", "/dev/")).distinct())
    }

    @Test
    fun noRestoreInsideFinallyFails() {
        val noRemove = run.replace("else { Remove-ItemProperty -Path \$userKey -Name AccessVBOM -ErrorAction SilentlyContinue }", "else { }")
        assertEquals(listOf("NO_RESTORE"), rules(noRemove))
        val noFinally = run.replace("    finally {", "    if (\$true) {")
        assertEquals(listOf("NO_RESTORE"), rules(noFinally))
    }

    @Test
    fun theInstallScriptIsHeldToTheSameRules() {
        val install = File("../trimmer/${PsLint.INSTALL}").readText(Charsets.ISO_8859_1)
        assertEquals(emptyList<String>(), rules(install, PsLint.INSTALL))
        assertEquals(listOf("FORBIDDEN"), rules(install.replaceFirst("& {\r\n", "& {\r\n    Start-Process notepad\r\n"), PsLint.INSTALL))
        assertEquals(listOf("MODULE"), rules(install.replace("TrimmerKeys.bas", "Other.bas"), PsLint.INSTALL))
        assertEquals(listOf("URL"), rules(install.replaceFirst("& {\r\n", "& {\r\n    \$x = 'https://example.com/a.bas'\r\n"), PsLint.INSTALL))
        assertEquals(listOf("NO_RESTORE"), rules(install.replace("    finally {", "    if (\$true) {"), PsLint.INSTALL))
        assertEquals(listOf("REGISTRY"), rules(install.replaceFirst("& {\r\n", "& {\r\n    Set-ItemProperty -Path \$userKey -Name VBAWarnings -Value 1\r\n"), PsLint.INSTALL))
    }

    @Test
    fun aMissingInstallScriptFailsTheFolderCheck() {
        val dir = java.nio.file.Files.createTempDirectory("pslint").toFile()
        try {
            File("../trimmer/${PsLint.RUN}").copyTo(File(dir, PsLint.RUN))
            File("../trimmer/${PsLint.SELF_TEST}").copyTo(File(dir, PsLint.SELF_TEST))
            assertEquals(listOf("MISSING ${PsLint.INSTALL}"), PsLint.checkFolder(dir).map { "${it.rule} ${it.file}" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theWrongMacroFails() {
        assertEquals(listOf("MACRO"), rules(run.replace("\$macro = 'TrimStock'", "\$macro = 'RunSelfTest'")))
        assertEquals(listOf("MACRO"), rules(run, PsLint.SELF_TEST))
    }

    @Test
    fun theTwoScriptsDifferingBeyondTheMacroFails() {
        assertEquals(emptyList<PsLint.Problem>(), PsLint.checkPair(run, self))
        val drifted = self.replace("Say 'finished.'", "Say 'done.'")
        assertEquals(listOf("PAIR"), PsLint.checkPair(run, drifted).map { it.rule })
    }
}
