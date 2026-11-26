package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.LinkGenerator
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.ExpectedCodes
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The 500-findings run (designs/stage-1-foundation.md 4.15, S1.9): 500 findings generated through a
 * [RepositoryAdapter] into a fresh database, read back and compared with the generator's expected
 * snapshot, times included. The confirmed-seed run writes `gen500=<seconds>` to the check's
 * `timings.txt` (generate + insert + read back, `System.nanoTime()`).
 */
@RunWith(RobolectricTestRunner::class)
class Generate500Test {
    private val random = TestRandom.forTest(Generate500Test::class.java.name)

    private class Run(val t: TestDb, val port: RecordingPort, val expected: Snapshot, val actual: Snapshot, val nanos: Long)

    /** With dots (4.26): [linksText] is the link file the database starts with (the confirmed one when null). */
    private fun run500(seedText: String, linksText: String? = null): Run {
        val t = TestDb(seedText, linksText = linksText)
        val port = RecordingPort(RepositoryAdapter(t.db, t.drawing))
        val start = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val t0 = System.nanoTime()
        val expected = DataGenerator.generate(random, port, findings = N, start = start, drawing = t.drawing)
        // Read back after closing and opening the database again from its file (S1.27).
        t.db.close()
        val reopened = AppDatabase.build(ApplicationProvider.getApplicationContext(), t.name, allowMainThread = true)
        val actual = try {
            RegionLinkLoader.run(reopened, t.drawing, linksText?.let { BundledDrawing.parseLinks(it, seedText, t.drawing) } ?: ConfirmedDrawing.links)
            assertEquals("clean after the reopen", emptyList<Violation>(), DataCheck.run(reopened, t.drawing))
            Snapshot.read(reopened)
        } finally {
            reopened.close()
        }
        val nanos = System.nanoTime() - t0
        return Run(t, port, expected, actual, nanos)
    }

    /** Dots were made, and each rests on a region linked to the finding's own area value (links did not change). */
    private fun checkDots(r: Run, expectDots: Boolean) {
        val withDot = r.actual.findings.filter { it.dotX != null }
        val linkOf = r.actual.regionLinks.associate { it.regionId to it.valueId }
        if (expectDots) assertTrue("no finding got a dot", withDot.isNotEmpty())
        assertTrue("no finding without a dot", withDot.size < r.actual.findings.size)
        for (f in withDot) {
            assertEquals(r.t.drawing.id, f.dotDrawing)
            val region = Hit.resolveDot(r.t.drawing, f.dotX!!, f.dotY!!)
            assertEquals("dot of ${f.id} resolves to a region linked to its area", f.areaValueId, linkOf[region])
        }
    }

    private fun checkRun(r: Run) {
        Snapshot.assertSame(r.expected, r.actual, "500-findings snapshot read back differs from the generator's")
        assertEquals(N, r.actual.findings.size)
        val fs = r.actual.findings
        assertTrue("no finding with an action", fs.any { it.actionValueId != null })
        assertTrue("no finding chosen as \"No action\"", fs.any { it.actionValueId == null && it.noAction })
        assertTrue("no finding with the action not chosen yet", fs.any { it.actionValueId == null && !it.noAction })
        assertTrue(
            "no lower mileage was refused and then confirmed",
            r.port.refusedThenConfirmed("inspection", ExpectedCodes.MILEAGE_LOWER_UNCONFIRMED) >= 1,
        )
        assertTrue(
            "no same-area finding was refused and then confirmed",
            r.port.refusedThenConfirmed("finding", ExpectedCodes.SAME_AREA_UNCONFIRMED) >= 1,
        )
        assertEquals("confirmed without a refusal first", emptyList<RecordingPort.Call>(), r.port.confirmedWithoutRefusal())
    }

    @Test
    fun confirmedSeed() {
        val r = run500(ConfirmedSeed.text)
        r.t.use {
            writeTiming(r.nanos)
            checkRun(r)
            checkDots(r, expectDots = true)
            val used = r.actual.findings.flatMap { listOfNotNull(it.areaValueId, it.damageValueId, it.actionValueId) }.toSet()
            for (role in Role.entries) {
                val lists = r.actual.lists.filter { it.role == role && !it.removed }.map { it.id }.toSet()
                val unused = r.actual.values.filter { !it.removed && it.listId in lists && it.id !in used }
                assertEquals("offered $role values with no finding", emptyList<Snapshot.ValueRow>(), unused)
            }
        }
    }

    @Test
    fun generatedSeed() {
        // A generated seed with the confirmed link file: no region links (a link file belongs to its seed), so no dots.
        val r = run500(SeedGenerator.generate(random, ConfirmedSeed.listNames))
        r.t.use {
            checkRun(r)
            assertTrue("every region is not linked", r.actual.regionLinks.all { it.valueId == null })
            checkDots(r, expectDots = false)
            assertTrue("no dots", r.actual.findings.all { it.dotX == null })
        }
    }

    @Test
    fun generatedSeedWithGeneratedLinks() {
        val seed = SeedGenerator.generate(random, ConfirmedSeed.listNames)
        val links = LinkGenerator.generate(random, seed, ConfirmedDrawing.drawing, LinkGenerator.Extreme.ALL_LINKED)
        val r = run500(seed, links)
        r.t.use {
            checkRun(r)
            checkDots(r, expectDots = true)
        }
    }

    /** Writes or replaces the `gen500=` line of `<smj.checkDir>/timings.txt` (4.5, 4.15). */
    private fun writeTiming(nanos: Long) {
        val dir = File(System.getProperty("smj.checkDir") ?: error("smj.checkDir not set"))
        dir.mkdirs()
        val file = File(dir, "timings.txt")
        val others = if (file.isFile) file.readLines().filter { it.isNotBlank() && !it.startsWith("gen500=") } else emptyList()
        val line = "gen500=" + String.format(Locale.ROOT, "%.1f", nanos / 1e9)
        file.writeText((others + line).joinToString("\n", postfix = "\n"))
    }

    private companion object {
        const val N = 500
    }
}
