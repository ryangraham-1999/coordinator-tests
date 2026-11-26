package com.streamlinemyjob.inspect.tools

/**
 * The summary (designs/stage-1-foundation.md 4.5): exactly one line first, then the failure lines
 * (each `<phase>: <line>`, at most 20 per phase, then `… and N more`), then any note, and nothing else.
 */
data class Summary(
    val place: Place,
    val fast: Boolean,
    /** The phases named by `--only`, or null for a run without `--only`. */
    val only: List<Phase>?,
    /** The phases that ran or were skipped, in run order. Phases left out by `--only` are not listed. */
    val results: List<Pair<Phase, PhaseResult>>,
    val release: String,
    /** gen500 seconds as written in timings.txt, or null if missing. */
    val gen500: String?,
    val seed: Long,
    val deviceSeed: Long?,
    val millis: Long,
) {
    /** PASS only if every phase is PASS, apart from `device:SKIP(fast)` under `--fast`. */
    val pass: Boolean
        get() = results.all { (phase, r) ->
            r.status == Status.PASS || (fast && phase == Phase.DEVICE && r.status == Status.SKIP && r.skipReason == "fast")
        }

    val isFullPass: Boolean get() = pass && !fast && only == null

    fun lines(): List<String> {
        val word = if (pass) "PASS" else "FAIL"
        val qualifier = when {
            only != null -> " (only: ${only.joinToString(",") { it.id }})"
            fast -> " (fast: device skipped)"
            else -> ""
        }
        val tokens = results.joinToString(" ") { (phase, r) -> r.token(phase) }
        val seeds = "seed=$seed" + (deviceSeed?.let { "  device-seed=$it" } ?: "")
        val head = "CHECK $word$qualifier  place=${place.label}  $tokens  release:$release  gen500:${gen500?.let { "${it}s" } ?: "none"}  $seeds  time=${duration(millis)}  log=build/check/"
        val out = mutableListOf(head)
        for ((phase, r) in results) out += JUnitReports.cap(r.failures).map { "${phase.id}: $it" }
        gen500?.toDoubleOrNull()?.let { if (it > 60.0) out += "note: gen500 took ${gen500}s (over 60s; not a failure)" }
        return out
    }

    companion object {
        /** `6m41s`, read from the clock at start and end. */
        fun duration(millis: Long): String {
            val s = (millis + 500) / 1000
            return "${s / 60}m${"%02d".format(s % 60)}s"
        }

        /** `release:` (4.5): CLOUD in the cloud; SIGNED on the PC when the build (with assembleRelease) passed; else FAIL. */
        fun release(place: Place, build: PhaseResult?): String = when {
            place == Place.CLOUD -> "CLOUD"
            build?.status == Status.PASS -> "SIGNED"
            else -> "FAIL"
        }
    }
}
