package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The device phase in actions mode (designs/stage-1-foundation.md 4.5, 6), against a real git
 * repo in a temporary folder and a fake `gh`: a fixture script that prints canned check-run JSON.
 * The time limit is shortened by a fake clock; nothing sleeps.
 */
class DeviceActionsTest {
    private val dir = Fixtures.tempDir("smj-device-actions-test")
    private val work = File(dir, "work")
    private val response = File(dir, "response.json")
    private val runsResponse = File(dir, "runs.json")
    private val calls = File(dir, "calls.txt")
    private val windows = isWindows()
    private val gh = File(dir, if (windows) "gh.cmd" else "gh").apply {
        if (windows) writeText("@echo off\r\necho %*>>\"${calls.path}\"\r\necho %*|find \"actions/runs\" >nul && (type \"${runsResponse.path}\") || (type \"${response.path}\")\r\n")
        else { writeText("#!/bin/sh\necho \"\$*\" >> '${calls.path}'\ncase \"\$*\" in *actions/runs*) cat '${runsResponse.path}';; *) cat '${response.path}';; esac\n"); setExecutable(true) }
    }
    private val r = Fixtures.random(DeviceActionsTest::class.qualifiedName!!)
    private val owner = "owner${r.nextInt(100, 999)}"
    private val repo = "repo${r.nextInt(100, 999)}"

    private var now = 0L
    private var onSleep: (Int) -> Unit = {}
    private var sleeps = 0

    @After fun clean() { dir.deleteRecursively() }

    private fun git(vararg args: String, cwd: File = work): String {
        val res = Proc.SYSTEM.run(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false") + args, cwd)
        assertEquals("git ${args.joinToString(" ")}: ${res.err}", 0, res.exit)
        return res.out.trim()
    }

    /** A clone of a local bare repo, pushed, whose origin then names a GitHub repo. Returns HEAD's sha. */
    private fun pushedRepo(): String {
        val bare = File(dir, "remote.git")
        git("init", "-q", "--bare", bare.path, cwd = dir)
        git("init", "-q", "-b", "stage-x", work.path, cwd = dir)
        File(work, "a.txt").writeText("a\n")
        git("add", "a.txt"); git("commit", "-q", "-m", "one")
        git("remote", "add", "origin", bare.path)
        git("push", "-q", "-u", "origin", "stage-x")
        git("remote", "set-url", "origin", "https://github.com/$owner/$repo.git")
        return git("rev-parse", "HEAD")
    }

    private var deviceRepo: String? = null

    private fun actions(timeoutMin: Long = 45) = DeviceActions(
        root = work,
        proc = Proc.SYSTEM,
        clock = { now },
        sleep = { ms -> now += ms; sleeps++; onSleep(sleeps) },
        timeoutMillis = timeoutMin * 60_000,
        intervalMillis = 30_000,
        gh = gh.path,
        deviceRepo = deviceRepo,
    )

    private fun run(sha: String, summary: String?, status: String = "completed", name: String = "smj-device"): String {
        val output = if (summary == null) "\"summary\": null" else "\"summary\": ${kotlinx.serialization.json.JsonPrimitive(summary)}"
        return """{"name": "$name", "id": ${r.nextInt(1, 1_000_000)}, "head_sha": "$sha", "status": "$status", "completed_at": "2026-11-23T10:00:00Z", "output": {"title": "x", $output}}"""
    }

    private fun respond(vararg runs: String) = response.writeText("""{"total_count": ${runs.size}, "check_runs": [${runs.joinToString(",")}]}""")

    @Test
    fun aPassingRunForHeadIsPassWithActions() {
        val sha = pushedRepo()
        val n = r.nextInt(1, 50)
        respond(run(sha, "DEVICE PASS($n)"))
        assertEquals(PhaseResult(Status.PASS, "$n, actions"), actions().run())
        assertTrue(calls.readText().contains("repos/$owner/$repo/commits/$sha/check-runs?check_name=smj-device"))
    }

    @Test
    fun aDirtyTreeIsNotPushed() {
        pushedRepo()
        File(work, "new.txt").writeText("x")
        respond()
        val got = actions().run()
        assertEquals(Status.FAIL, got.status)
        assertTrue(got.failures.single(), got.failures.single().startsWith("DEVICE_NOT_PUSHED"))
        assertTrue(!calls.exists())
    }

    @Test
    fun anUnpushedCommitOrNoUpstreamIsNotPushed() {
        pushedRepo()
        File(work, "a.txt").writeText("b\n"); git("commit", "-q", "-am", "two")
        respond()
        assertTrue(actions().run().failures.single().startsWith("DEVICE_NOT_PUSHED (HEAD is not pushed"))
        git("checkout", "-q", "-b", "no-upstream")
        assertTrue(actions().run().failures.single().startsWith("DEVICE_NOT_PUSHED (the branch has no upstream"))
        assertTrue(!calls.exists())
    }

    @Test
    fun noRunUntilTheTimeLimitIsTimeout() {
        pushedRepo()
        respond()
        val got = actions(timeoutMin = 3).run()
        assertTrue(got.failures.single(), got.failures.single().startsWith("DEVICE_TIMEOUT"))
        assertEquals(6, sleeps) // every 30 s for 3 minutes
    }

    @Test
    fun aPassingRunForAnotherShaOnlyIsTimeoutNeverPass() {
        val sha = pushedRepo()
        val other = sha.reversed()
        respond(run(other, "DEVICE PASS(18)"))
        val got = actions(timeoutMin = 2).run()
        assertEquals(Status.FAIL, got.status)
        assertTrue(got.failures.single().startsWith("DEVICE_TIMEOUT"))
    }

    @Test
    fun aRunThatIsNotCompletedOrNamedOtherwiseIsNotUsed() {
        val sha = pushedRepo()
        respond(run(sha, "DEVICE PASS(3)", status = "in_progress"), run(sha, "DEVICE PASS(3)", name = "something-else"))
        assertTrue(actions(timeoutMin = 1).run().failures.single().startsWith("DEVICE_TIMEOUT"))
    }

    @Test
    fun aRunThatAppearsLaterIsRead() {
        val sha = pushedRepo()
        respond()
        val k = r.nextInt(1, 10)
        onSleep = { s -> if (s == k) respond(run(sha, "DEVICE PASS(7)")) }
        assertEquals(PhaseResult(Status.PASS, "7, actions"), actions().run())
        assertEquals(k, sleeps)
    }

    @Test
    fun aRunWithNoSummaryIsNoResult() {
        val sha = pushedRepo()
        respond(run(sha, null))
        assertTrue(actions().run().failures.single().startsWith("DEVICE_NO_RESULT"))
        respond(run(sha, ""))
        assertTrue(actions().run().failures.single().startsWith("DEVICE_NO_RESULT"))
    }

    @Test
    fun aFailingSummaryIsFailWithItsFailureLine() {
        val sha = pushedRepo()
        val line = "DEVICE com.example.ScreenTest.opens: expected tag x"
        respond(run(sha, "DEVICE FAIL(17/18)\n$line"))
        assertEquals(PhaseResult(Status.FAIL, "17/18, actions", failures = listOf(line)), actions().run())
    }

    @Test
    fun noneAndSkippedSummariesFail() {
        val sha = pushedRepo()
        respond(run(sha, "DEVICE_NONE"))
        assertEquals(listOf("DEVICE_NONE"), actions().run().failures)
        respond(run(sha, "DEVICE PASS(4, 1 skipped)\nDEVICE_SKIPPED com.example.A.b"))
        assertEquals(PhaseResult(Status.FAIL, "4, 1 skipped, actions", failures = listOf("DEVICE_SKIPPED com.example.A.b")), actions().run())
    }

    @Test
    fun deviceSeedIsTheFirstSixteenHexDigitsAsASignedLong() {
        assertEquals(0x0123456789abcdefL, DeviceActions.deviceSeed("0123456789abcdef0000"))
        assertEquals(-1L, DeviceActions.deviceSeed("ffffffffffffffff1234"))
        assertEquals(Long.MIN_VALUE, DeviceActions.deviceSeed("8000000000000000aaaa"))
    }

    private fun workflowRun(headSha: String, message: String, createdAt: String = "2026-11-26T10:00:00Z") =
        """{"head_sha": "$headSha", "created_at": "$createdAt", "head_commit": {"message": ${kotlinx.serialization.json.JsonPrimitive(message)}}}"""

    private fun respondRuns(vararg runs: String) = runsResponse.writeText("""{"total_count": ${runs.size}, "workflow_runs": [${runs.joinToString(",")}]}""")

    @Test
    fun withATestRepoTheCheckRunOfTheSnapshotOfHeadIsRead() {
        val sha = pushedRepo()
        val snap = "a".repeat(40)
        deviceRepo = "pub/tests"
        respondRuns(workflowRun(snap, "Test x at ${sha.take(7)}\n\nSource: $sha"))
        respond(run(snap, "DEVICE PASS(21)"))
        val a = actions()
        assertEquals(PhaseResult(Status.PASS, "21, actions"), a.run())
        assertEquals(snap, a.snapshotSha)
        val c = calls.readText()
        assertTrue(c, c.contains("repos/pub/tests/actions/runs"))
        assertTrue(c, c.contains("repos/pub/tests/commits/$snap/check-runs?check_name=smj-device"))
        assertTrue(c, !c.contains("repos/$owner/$repo"))
    }

    @Test
    fun aSnapshotOfAnotherCommitOrAPartialShaIsNoSnapshot() {
        val sha = pushedRepo()
        val snap = "b".repeat(40)
        deviceRepo = "pub/tests"
        respondRuns(
            workflowRun(snap, "Test x\n\nSource: ${sha.reversed()}"),
            workflowRun(snap, "Test x at ${sha.take(7)}"),
            workflowRun(snap, "Source: ${sha.take(12)}"),
        )
        respond(run(snap, "DEVICE PASS(21)"))
        val got = actions().run()
        assertEquals(Status.FAIL, got.status)
        assertTrue(got.failures.single(), got.failures.single().startsWith("DEVICE_NO_SNAPSHOT"))
        assertTrue(!calls.readText().contains("check-runs"))
    }

    @Test
    fun theNewestSnapshotOfHeadWins() {
        val sha = pushedRepo()
        val old = "c".repeat(40); val new = "d".repeat(40)
        deviceRepo = "pub/tests"
        respondRuns(workflowRun(old, "Source: $sha", "2026-11-26T09:00:00Z"), workflowRun(new, "Source: $sha", "2026-11-26T11:00:00Z"))
        respond(run(old, "DEVICE PASS(1)"), run(new, "DEVICE FAIL(3/4)\nDEVICE a.B.c: boom"))
        val got = actions().run()
        assertEquals(Status.FAIL, got.status)
        assertEquals("3/4, actions", got.counts)
    }

    @Test
    fun theTestRepoNameComesFromConfigCheckProperties() {
        File(work, "config").mkdirs()
        assertEquals(null, DeviceActions.configuredRepo(work))
        File(work, "config/check.properties").writeText("device=actions\n")
        assertEquals(null, DeviceActions.configuredRepo(work))
        File(work, "config/check.properties").writeText("device=actions\ndevice-repo= pub/tests \n")
        assertEquals("pub/tests", DeviceActions.configuredRepo(work))
    }
}
