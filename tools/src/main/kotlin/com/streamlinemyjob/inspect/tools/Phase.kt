package com.streamlinemyjob.inspect.tools

/** The check's phases, in the order they always run, with their prerequisite (designs/stage-1-foundation.md 4.5). */
enum class Phase(val id: String, val prerequisite: Phase?) {
    ENV("env", null),
    BUILD("build", ENV),
    SEED("seed", ENV),
    MANIFEST("manifest", BUILD),
    DEPS("deps", BUILD),
    UNIT("unit", ENV),
    DEVICE("device", BUILD),
    DOCS("docs", null);

    /** This phase and every phase it needs, in run order. */
    fun withPrerequisites(): Set<Phase> = generateSequence(this) { it.prerequisite }.toSet()

    companion object {
        fun byId(id: String): Phase? = entries.firstOrNull { it.id == id }
    }
}

enum class Status { PASS, FAIL, SKIP }

/**
 * One phase's outcome. [counts] is what goes in brackets after the status (`412`, `411/412`,
 * `410, 2 skipped`, `18, actions`), [skipReason] the bracket of a SKIP (`fast`, `after env`), and
 * [failures] the failure lines without the `<phase>: ` prefix.
 */
data class PhaseResult(
    val status: Status,
    val counts: String? = null,
    val skipReason: String? = null,
    val failures: List<String> = emptyList(),
) {
    fun token(phase: Phase): String = phase.id + ":" + status.name + when {
        status == Status.SKIP -> "(${skipReason ?: ""})"
        counts != null -> "($counts)"
        else -> ""
    }

    companion object {
        fun of(failures: List<String>, counts: String? = null) =
            PhaseResult(if (failures.isEmpty()) Status.PASS else Status.FAIL, counts, null, failures)
    }
}
