package com.streamlinemyjob.inspect.tools

import java.io.File

/** The docs phase (designs/stage-1-foundation.md 4.18, docs rules). */
object DocsRules {
    const val SEED_PATH = "app/src/main/assets/seed/lists.json"

    fun check(root: File): List<String> {
        val readme = File(root, "README.md")
        if (!readme.isFile) return listOf("DOCS_MISSING")
        val text = readme.readText()
        val spans = Regex("`([^`\\n]+)`").findAll(text).map { it.groupValues[1].trim() }.toList()
        val problems = mutableListOf<String>()
        for (name in scriptNames(File(root, "scripts"))) {
            val named = spans.any { it == "scripts/$name" || it.startsWith("scripts/$name ") }
            if (!named) problems += "DOCS_SCRIPT_MISSING $name"
        }
        if (!text.contains(SEED_PATH)) problems += "DOCS_SEED_PATH"
        return problems
    }

    /** Every file in `scripts/`, named without `.cmd`, sorted. */
    fun scriptNames(dir: File): List<String> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.map { it.name.removeSuffix(".cmd") }.distinct().sorted()
}
