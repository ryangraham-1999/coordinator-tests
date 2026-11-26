package com.streamlinemyjob.inspect.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Summary rules (designs/stage-1-foundation.md 4.5, 6), on the summary alone. */
class SummaryTest {
    private val r = Fixtures.random(SummaryTest::class.qualifiedName!!)

    private fun allPass(): List<Pair<Phase, PhaseResult>> = Phase.entries.map {
        it to when (it) {
            Phase.UNIT -> PhaseResult(Status.PASS, "412")
            Phase.DEVICE -> PhaseResult(Status.PASS, "18, actions")
            else -> PhaseResult(Status.PASS)
        }
    }

    private fun summary(results: List<Pair<Phase, PhaseResult>>, place: Place = Place.CLOUD, fast: Boolean = false, only: List<Phase>? = null, gen500: String? = "1.9", deviceSeed: Long? = 42L) =
        Summary(place, fast, only, results, Summary.release(place, results.toMap()[Phase.BUILD]), gen500, -3141592653589793L, deviceSeed, 401_000)

    @Test
    fun everyPhasePassIsCheckPassInTheDesignsForm() {
        val s = summary(allPass())
        assertTrue(s.pass)
        assertEquals(
            listOf("CHECK PASS  place=cloud  env:PASS build:PASS seed:PASS manifest:PASS deps:PASS unit:PASS(412) device:PASS(18, actions) docs:PASS  release:CLOUD  gen500:1.9s  seed=-3141592653589793  device-seed=42  time=6m41s  log=build/check/"),
            s.lines(),
        )
    }

    @Test
    fun releaseIsCloudInTheCloudSignedOnAPcPassAndFailOnAPcBuildFailure() {
        assertTrue(summary(allPass()).lines()[0].contains("  release:CLOUD  "))
        assertTrue(summary(allPass(), Place.PC).lines()[0].contains("place=pc"))
        assertTrue(summary(allPass(), Place.PC).lines()[0].contains("  release:SIGNED  "))
        val failed = allPass().map { if (it.first == Phase.BUILD) it.first to PhaseResult(Status.FAIL, failures = listOf("BUILD x")) else it }
        assertTrue(summary(failed, Place.PC).lines()[0].contains("  release:FAIL  "))
        assertTrue(summary(failed, Place.CLOUD).lines()[0].contains("  release:CLOUD  "))
    }

    @Test
    fun oneSkipOutsideFastIsCheckFail() {
        val phase = Phase.entries.random(r)
        val results = allPass().map { if (it.first == phase) it.first to PhaseResult(Status.SKIP, skipReason = "after env") else it }
        val s = summary(results)
        assertEquals(false, s.pass)
        assertTrue(s.lines()[0], s.lines()[0].startsWith("CHECK FAIL  place=cloud"))
        assertTrue(s.lines()[0].contains("${phase.id}:SKIP(after env)"))
    }

    @Test
    fun deviceSkippedUnderFastIsTheFastPass() {
        val results = allPass().map { if (it.first == Phase.DEVICE) it.first to PhaseResult(Status.SKIP, skipReason = "fast") else it }
        val s = summary(results, fast = true, deviceSeed = null)
        assertTrue(s.pass)
        assertEquals(false, s.isFullPass)
        assertTrue(s.lines()[0], s.lines()[0].startsWith("CHECK PASS (fast: device skipped)  place=cloud  env:PASS"))
        assertTrue(s.lines()[0].contains("device:SKIP(fast) docs:PASS"))
        // Device skipped for fast without --fast is still a fail.
        assertEquals(false, summary(results, fast = false).pass)
    }

    @Test
    fun onlyFormNamesThePhasesAsked() {
        val results = allPass().filter { it.first in setOf(Phase.ENV, Phase.BUILD, Phase.MANIFEST, Phase.DEPS) }
        val s = summary(results, only = listOf(Phase.MANIFEST, Phase.DEPS), gen500 = null, deviceSeed = null)
        assertEquals(
            "CHECK PASS (only: manifest,deps)  place=cloud  env:PASS build:PASS manifest:PASS deps:PASS  release:CLOUD  gen500:none  seed=-3141592653589793  time=6m41s  log=build/check/",
            s.lines().single(),
        )
        assertEquals(false, s.isFullPass)
    }

    @Test
    fun failureLinesFollowTheSummaryPrefixedAndCappedAtTwenty() {
        val k = r.nextInt(21, 60)
        val results = allPass().map {
            when (it.first) {
                Phase.UNIT -> it.first to PhaseResult(Status.FAIL, "1/${k + 1}", failures = List(k) { i -> "UNIT a.B.t$i: no" })
                Phase.MANIFEST -> it.first to PhaseResult(Status.FAIL, failures = listOf("MANIFEST_DENIED android.permission.INTERNET (release, debug)"))
                else -> it
            }
        }
        val lines = summary(results).lines()
        assertTrue(lines[0].startsWith("CHECK FAIL  "))
        assertTrue(lines[0].contains("manifest:FAIL deps:PASS unit:FAIL(1/${k + 1})"))
        assertEquals("manifest: MANIFEST_DENIED android.permission.INTERNET (release, debug)", lines[1])
        assertEquals((0 until 20).map { "unit: UNIT a.B.t$it: no" }, lines.subList(2, 22))
        assertEquals("unit: … and ${k - 20} more", lines[22])
        assertEquals(23, lines.size)
    }

    @Test
    fun gen500OverSixtySecondsIsANoteNotAFailure() {
        val s = summary(allPass(), gen500 = "74.2")
        assertTrue(s.pass)
        assertEquals(listOf("note: gen500 took 74.2s (over 60s; not a failure)"), s.lines().drop(1))
        assertEquals(1, summary(allPass(), gen500 = "60.0").lines().size)
    }

    @Test
    fun durationIsMinutesAndTwoDigitSeconds() {
        assertEquals("0m09s", Summary.duration(9_000))
        assertEquals("14m02s", Summary.duration(842_000))
    }
}
