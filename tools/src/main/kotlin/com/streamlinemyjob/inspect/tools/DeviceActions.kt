package com.streamlinemyjob.inspect.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The device phase in actions mode (designs/stage-1-foundation.md 4.5): reads the `smj-device`
 * check run that the device-tests workflow (4.19.4) made for HEAD. Only a check run for the
 * HEAD sha counts.
 */
class DeviceActions(
    private val root: File,
    private val proc: Proc,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val timeoutMillis: Long = 45 * 60 * 1000L,
    private val intervalMillis: Long = 30 * 1000L,
    private val log: (String) -> Unit = {},
    /** The GitHub CLI; tests pass a fixture script that prints canned check-run JSON (design 6). */
    private val gh: String = "gh",
) {
    /** Checks the tree is pushed and returns HEAD's sha, or null (then [notPushed] says why). */
    var notPushed: String? = null
        private set

    fun pushedHead(): String? {
        val status = proc.run(listOf("git", "status", "--porcelain"), root)
        if (status.exit != 0) { notPushed = "git status failed"; return null }
        if (status.out.isNotBlank()) { notPushed = "the working tree has changes; commit and push first"; return null }
        val head = proc.run(listOf("git", "rev-parse", "HEAD"), root)
        val upstream = proc.run(listOf("git", "rev-parse", "@{u}"), root)
        if (head.exit != 0) { notPushed = "no HEAD"; return null }
        if (upstream.exit != 0) { notPushed = "the branch has no upstream; push it first"; return null }
        if (head.out.trim() != upstream.out.trim()) { notPushed = "HEAD is not pushed (HEAD ${head.out.trim().take(7)}, upstream ${upstream.out.trim().take(7)})"; return null }
        return head.out.trim()
    }

    fun run(): PhaseResult {
        val sha = pushedHead() ?: return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NOT_PUSHED ($notPushed)"))
        val origin = proc.run(listOf("git", "remote", "get-url", "origin"), root).out.trim()
        val (_, owner, repo) = GitHub.parse(origin) ?: return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NOT_PUSHED (origin is not a GitHub remote: $origin)"))
        val start = clock()
        while (true) {
            val r = proc.run(listOf(gh, "api", "repos/$owner/$repo/commits/$sha/check-runs?check_name=$CHECK_NAME"), root)
            if (r.exit != 0) {
                log("gh api failed (exit ${r.exit}): ${r.err.lineSequence().firstOrNull().orEmpty()}")
            } else {
                val run = completedRunFor(r.out, sha)
                if (run != null) {
                    log("check run ${run.id} for $sha: ${run.summary?.lineSequence()?.firstOrNull()}")
                    val summary = run.summary
                    if (summary.isNullOrBlank()) return PhaseResult(Status.FAIL, failures = listOf("DEVICE_NO_RESULT (check run ${run.id} has no summary)"))
                    return DeviceReport.parse(summary, "actions")
                }
                log("no completed $CHECK_NAME check run for $sha yet")
            }
            if (clock() - start >= timeoutMillis) {
                return PhaseResult(Status.FAIL, failures = listOf("DEVICE_TIMEOUT (no completed $CHECK_NAME check run for ${sha.take(7)} after ${timeoutMillis / 60000} min)"))
            }
            sleep(intervalMillis)
        }
    }

    data class CheckRun(val id: String, val summary: String?, val completedAt: String)

    companion object {
        const val CHECK_NAME = "smj-device"

        /** The newest completed `smj-device` check run whose head_sha is [sha]; runs for other commits are never used. */
        fun completedRunFor(json: String, sha: String): CheckRun? {
            val runs = (Json.parseToJsonElement(json) as? JsonObject)?.get("check_runs") as? JsonArray ?: return null
            return runs.mapNotNull { it as? JsonObject }
                .filter { str(it, "name") == CHECK_NAME && str(it, "head_sha") == sha && str(it, "status") == "completed" }
                .map { CheckRun(it["id"]?.toString() ?: "?", str(it["output"] as? JsonObject, "summary"), str(it, "completed_at") ?: "") }
                .maxByOrNull { it.completedAt }
        }

        private fun str(o: JsonObject?, key: String): String? = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The device seed (4.5): the first 16 hex digits of the sha as a signed 64-bit number. */
        fun deviceSeed(sha: String): Long = java.lang.Long.parseUnsignedLong(sha.substring(0, 16), 16)
    }
}
