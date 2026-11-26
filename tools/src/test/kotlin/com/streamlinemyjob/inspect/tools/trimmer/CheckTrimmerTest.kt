package com.streamlinemyjob.inspect.tools.trimmer

import com.streamlinemyjob.inspect.tools.Gradle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/** `tools check-trimmer` (designs/stage-B-trimmer.md 4.12) with a fake Gradle and a made-up repo folder. */
class CheckTrimmerTest {
    private val root: File = Files.createTempDirectory("checktrimmer").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A fake Gradle that leaves a JUnit report with [failed] failing trimmer tests out of 3. */
    private fun gradle(failed: Int = 0, exit: Int = 0) = Gradle { _, _ ->
        val dir = File(root, "tools/build/test-results/test").apply { mkdirs() }
        val cases = (1..3).joinToString("") { i ->
            val body = if (i <= failed) "<failure message=\"boom $i\"/>" else ""
            "<testcase classname=\"com.streamlinemyjob.inspect.tools.trimmer.FakeTest\" name=\"t$i\">$body</testcase>"
        }
        File(dir, "TEST-fake.xml").writeText("<testsuite>$cases</testsuite>")
        exit
    }

    /** A fake VBA phase (the real one needs LibreOffice; it runs in `scripts/check-trimmer`). */
    private val vbaPass = VbaRunner { _, _ -> VbaOutcome.Ran(true, "VBA PASS(15) canary=FAIL (as it should) big=4.0s", emptyList()) }

    private fun run(g: Gradle = gradle(), vba: VbaRunner = vbaPass): Pair<Int, List<String>> {
        val bytes = ByteArrayOutputStream()
        val code = CheckTrimmer(root, g, PrintStream(bytes, true), vba = vba).run()
        return code to bytes.toString().trimEnd().lines()
    }

    /** The generated files plus two small clean modules standing in for the macro and the self-test. */
    private fun writeTestSet() {
        CheckTrimmer.write(File(root, "trimmer"), TestSetGenerator.files())
        for (name in listOf("Trimmer", "TrimmerSelfTest", "TrimmerKeys")) {
            File(root, "trimmer/$name.bas").writeText("Attribute VB_Name = \"$name\"\r\nOption Explicit\r\nPublic Sub Work()\r\nEnd Sub\r\n")
        }
        for (name in listOf(PsLint.RUN, PsLint.SELF_TEST, PsLint.INSTALL)) File("../trimmer/$name").copyTo(File(root, "trimmer/$name"))
    }

    @Test
    fun freshFilesPassAndTheVbaLineAlwaysShows() {
        writeTestSet()
        val (code, out) = run()
        assertEquals(0, code)
        assertEquals(
            listOf("TRIMMER CHECK PASS  TRIMRULES:PASS(3) TESTSET:PASS(17) BASLINT:PASS(4) PSLINT:PASS(3)  VBA PASS(15) canary=FAIL (as it should) big=4.0s"),
            out,
        )
    }

    @Test
    fun aFailingVbaPhaseFailsTheCheckWithItsDetails() {
        writeTestSet()
        val vba = VbaRunner { _, _ -> VbaOutcome.Ran(false, "VBA FAIL(14/15): regs canary=FAIL (as it should)", listOf("regs line 7: expected \"a\" got \"b\"")) }
        val (code, out) = run(vba = vba)
        assertEquals(1, code)
        assertEquals("TRIMMER CHECK FAIL VBA  TRIMRULES:PASS(3) TESTSET:PASS(17) BASLINT:PASS(4) PSLINT:PASS(3)  VBA FAIL(14/15): regs canary=FAIL (as it should)", out[0])
        assertEquals(listOf("  VBA regs line 7: expected \"a\" got \"b\""), out.drop(1))
    }

    @Test
    fun aSkippedVbaPhaseShowsButDoesNotFail() {
        writeTestSet()
        val (code, out) = run(vba = VbaRunner { _, _ -> VbaOutcome.Skipped("VBA SKIPPED (no LibreOffice here; runs on the work PC only)") })
        assertEquals(0, code)
        assertTrue(out[0], out[0].endsWith("  VBA SKIPPED (no LibreOffice here; runs on the work PC only)"))
    }

    @Test
    fun aMissingMacroModuleFailsBaslint() {
        writeTestSet()
        File(root, "trimmer/Trimmer.bas").delete()
        val (code, out) = run()
        assertEquals(1, code)
        assertEquals(listOf("  BASLINT BASLINT_MISSING Trimmer.bas:0 file not found in trimmer/"), out.drop(1))
    }

    @Test
    fun aMissingPowerShellStarterFailsPslint() {
        writeTestSet()
        File(root, "trimmer/${PsLint.SELF_TEST}").delete()
        val (code, out) = run()
        assertEquals(1, code)
        assertTrue(out[0], out[0].startsWith("TRIMMER CHECK FAIL PSLINT "))
        assertEquals(listOf("  PSLINT PSLINT_MISSING selftest.ps1:0 file not found in trimmer/"), out.drop(1))
    }

    @Test
    fun oneEditedLineOfTheTestDataIsStale() {
        writeTestSet()
        val bas = File(root, "trimmer/TrimmerTestData.bas")
        bas.writeText(bas.readText().replaceFirst("CaseCount = 16", "CaseCount = 15"))
        val (code, out) = run()
        assertEquals(1, code)
        assertTrue(out[0], out[0].startsWith("TRIMMER CHECK FAIL TESTSET "))
        assertTrue(out[0].contains("VBA PASS"))
        assertEquals(listOf("  TESTSET TESTSET_STALE trimmer/TrimmerTestData.bas"), out.drop(1))
    }

    @Test
    fun aMissingOrExtraListingIsStale() {
        writeTestSet()
        File(root, "trimmer/testset/regs.listing.txt").delete()
        File(root, "trimmer/testset/old.listing.txt").writeText("x\n")
        val (code, out) = run()
        assertEquals(1, code)
        assertEquals(
            listOf("  TESTSET TESTSET_STALE trimmer/testset/regs.listing.txt", "  TESTSET TESTSET_STALE trimmer/testset/old.listing.txt (not generated)"),
            out.drop(1),
        )
    }

    @Test
    fun aFailingTrimmerTestOrNoTestsFailsTrimrules() {
        writeTestSet()
        val (code, out) = run(gradle(failed = 1, exit = 1))
        assertEquals(1, code)
        assertTrue(out[0], out[0].startsWith("TRIMMER CHECK FAIL TRIMRULES  TRIMRULES:FAIL(2/3)"))
        assertTrue(out[1].contains("FakeTest.t1: boom 1"))

        File(root, "tools/build/test-results").deleteRecursively()
        val (code2, out2) = run(Gradle { _, _ -> 1 })
        assertEquals(1, code2)
        assertTrue(out2.any { it.contains("no trimmer tests ran") })
    }

    @Test
    fun aLintFaultFailsBaslint() {
        writeTestSet()
        File(root, "trimmer/Extra.bas").writeText("Attribute VB_Name = \"Extra\"\r\nOption Explicit\r\nPublic Sub X()\r\n    Shell \"x\"\r\nEnd Sub\r\n")
        val (code, out) = run()
        assertEquals(1, code)
        assertTrue(out[0], out[0].startsWith("TRIMMER CHECK FAIL BASLINT "))
        assertEquals(listOf("  BASLINT BASLINT_FORBIDDEN Extra.bas:4 Shell"), out.drop(1))
    }
}
