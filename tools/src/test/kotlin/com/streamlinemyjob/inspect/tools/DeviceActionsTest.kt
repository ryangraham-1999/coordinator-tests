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
    private val calls = File(dir, "calls.txt")
    private val windows = isWindows()
    private val gh = File(dir, if (windows) "gh.cmd" else "gh").apply {
        if (windows) writeText("@echo off\r\necho %*>>\"${calls.path}\"\r\ntype \"${response.path}\"\r\n")
        else { writeText("#!/bin/sh\necho \"\$*\" >> '${calls.path}'\ncat '${response.path}'\n"); setExecutable(true) }
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

    private fun actions(timeoutMin: Long = 45) = DeviceActions(
        root = work,
        proc = Proc.SYSTEM,
        clock = { now },
        sleep = { ms -> now += ms; sleeps++; onSleep(sleeps) },
        timeoutMillis = timeoutMin * 60_000,
        intervalMillis = 30_000,
        gh = gh.path,
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
}
