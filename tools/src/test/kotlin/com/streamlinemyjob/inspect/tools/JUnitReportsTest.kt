package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * JUnit XML reading (designs/stage-1-foundation.md 4.5, 6): counts, failures, skipped tests and
 * "no tests ran", and the same fixtures through DeviceReport give the same results.
 */
class JUnitReportsTest {
    private val dir = Fixtures.tempDir("smj-junit-test")
    private val r = Fixtures.random(JUnitReportsTest::class.qualifiedName!!)

    @After fun clean() { dir.deleteRecursively() }

    private fun cls() = "org.example.T${r.nextInt(100, 999)}Test"

    /** Writes [suites] report files into nested folders whose names are not AGP's. */
    private fun write(suites: List<List<Fixtures.Case>>): File {
        val reports = File(dir, "r${r.nextInt()}")
        suites.forEachIndexed { i, cases ->
            val sub = File(reports, "level${r.nextInt(1, 9)}/deeper$i").apply { mkdirs() }
            File(sub, "TEST-suite$i.xml").writeText(Fixtures.junitXml("suite$i", cases))
        }
        File(reports, "not-a-report.txt").writeText("<testcase/>")
        return reports
    }

    private fun passing(n: Int): List<Fixtures.Case> { val c = cls(); return List(n) { Fixtures.Case(c, "t$it") } }

    @Test
    fun countsEveryTestcaseAcrossFilesAndFolders() {
        val sizes = List(r.nextInt(1, 6)) { r.nextInt(1, 30) }
        val t = JUnitReports.read(write(sizes.map { passing(it) }))
        assertEquals(sizes.sum(), t.total)
        assertEquals("${sizes.sum()}", JUnitReports.counts(t))
        assertEquals("DEVICE PASS(${sizes.sum()})", DeviceReport.text(t))
        assertEquals(PhaseResult(Status.PASS, "${sizes.sum()}, actions"), DeviceReport.parse(DeviceReport.text(t), "actions"))
    }

    @Test
    fun failuresAndErrorsAreCountedWithTheFirstLineOfTheirMessage() {
        val c = cls()
        val cases = passing(r.nextInt(3, 10)) + Fixtures.Case(c, "breaks", "expected \"A\" but was \"B\"\nsecond line") + Fixtures.Case(c, "throws", "boom", error = true)
        val t = JUnitReports.read(write(listOf(cases)))
        assertEquals(listOf("$c.breaks" to "expected \"A\" but was \"B\"", "$c.throws" to "boom"), t.failed.map { it.name to it.failure })
        assertEquals("${cases.size - 2}/${cases.size}", JUnitReports.counts(t))
        val text = DeviceReport.text(t)
        assertEquals("DEVICE FAIL(${cases.size - 2}/${cases.size})\nDEVICE $c.breaks: expected \"A\" but was \"B\"\nDEVICE $c.throws: boom", text)
        val parsed = DeviceReport.parse(text, null)
        assertEquals(Status.FAIL, parsed.status)
        assertEquals("${cases.size - 2}/${cases.size}", parsed.counts)
        assertEquals(listOf("DEVICE $c.breaks: expected \"A\" but was \"B\"", "DEVICE $c.throws: boom"), parsed.failures)
    }

    @Test
    fun zeroTestcasesIsNoneInBoth() {
        val t = JUnitReports.read(write(listOf(emptyList())))
        assertEquals(0, t.total)
        assertEquals("DEVICE_NONE", DeviceReport.text(t))
        assertEquals(listOf("DEVICE_NONE"), DeviceReport.parse(DeviceReport.text(t), "actions").failures)
        assertEquals(0, JUnitReports.read(File(dir, "missing")).total)
    }

    @Test
    fun skippedTestsAreCountedShownAndFailTheDevicePhase() {
        val c = cls()
        val n = r.nextInt(2, 9)
        val cases = passing(n) + Fixtures.Case(c, "ignored", skipped = true)
        val t = JUnitReports.read(write(listOf(cases)))
        assertEquals("$n, 1 skipped", JUnitReports.counts(t))
        val text = DeviceReport.text(t)
        assertEquals("DEVICE PASS($n, 1 skipped)\nDEVICE_SKIPPED $c.ignored", text)
        assertEquals(PhaseResult(Status.FAIL, "$n, 1 skipped, actions", failures = listOf("DEVICE_SKIPPED $c.ignored")), DeviceReport.parse(text, "actions"))
    }

    @Test
    fun failureLinesAreCappedAtTwentyWithACount() {
        val c = cls()
        val k = r.nextInt(21, 40)
        val t = JUnitReports.read(write(listOf(List(k) { Fixtures.Case(c, "f$it", "bad $it") })))
        val lines = DeviceReport.text(t).lines()
        assertEquals("DEVICE FAIL(0/$k)", lines[0])
        assertEquals(22, lines.size)
        assertEquals("… and ${k - 20} more", lines.last())
    }

    @Test
    fun aSummaryThatIsNotUnderstoodIsNoResult() {
        assertEquals(Status.FAIL, DeviceReport.parse("Tests done", "actions").status)
        assertEquals("DEVICE_NO_RESULT (summary not understood: Tests done)", DeviceReport.parse("Tests done", "actions").failures.single())
        assertEquals(listOf("DEVICE_NONE"), DeviceReport.parse("DEVICE PASS(0)", null).failures)
    }

    @Test
    fun deviceReportCommandReadsTheFolderItIsGiven() {
        val reports = write(listOf(passing(3)))
        val buf = java.io.ByteArrayOutputStream()
        val code = java.io.PrintStream(buf, true, "UTF-8").use { DeviceReport.run(dir, listOf(reports.path), it) }
        assertEquals(0, code)
        assertEquals("DEVICE PASS(3)", buf.toString("UTF-8").trim())
    }
}
