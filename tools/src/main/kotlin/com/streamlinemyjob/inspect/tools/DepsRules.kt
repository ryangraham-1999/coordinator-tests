package com.streamlinemyjob.inspect.tools

import java.io.File

/** The deps phase (designs/stage-1-foundation.md 4.4), on `build/check/release-runtime-deps.txt`. */
object DepsRules {
    /** Fixed in code, checked first (4.4 rule 2). */
    val DENIED = listOf(
        "com.squareup.okhttp3",
        "com.squareup.okhttp",
        "com.squareup.retrofit2",
        "io.ktor",
        "com.google.firebase",
        "com.google.android.gms",
        "io.sentry",
        "com.bugsnag",
        "com.android.volley",
        "com.google.android.datatransport",
    )

    /** The one project dependency the release app may have (4.4 rule 3). */
    const val ALLOWED_PROJECT = ":core"

    fun depsFile(checkDir: File) = File(checkDir, "release-runtime-deps.txt")

    /** A group matches a prefix if it equals it or starts with it followed by `.`. */
    fun matches(group: String, prefix: String) = group == prefix || group.startsWith("$prefix.")

    fun allowlist(file: File): List<String> =
        file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    fun check(depsFile: File, allowlistFile: File): List<String> {
        val lines = if (depsFile.isFile) depsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        if (lines.isEmpty()) return listOf("DEPS_EMPTY")
        val allowed = allowlist(allowlistFile)
        val problems = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("project ")) {
                val path = line.removePrefix("project ").trim()
                if (path != ALLOWED_PROJECT) problems += "DEPS_PROJECT $path"
                continue
            }
            val group = line.substringBefore(':')
            when {
                DENIED.any { matches(group, it) } -> problems += "DEPS_DENIED $line"
                allowed.none { matches(group, it) } -> problems += "DEPS_NOT_ALLOWED $line"
            }
        }
        return problems
    }
}
