package com.streamlinemyjob.inspect.tools

import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** Temporary folders and fixture files for the tools tests (designs/stage-1-foundation.md 6). */
object Fixtures {
    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** Random for a test: the run's `smj.seed` (clock if unset) plus a salt from the test's name (4.5). */
    fun random(test: String): Random {
        val seed = System.getProperty("smj.seed")?.toLongOrNull() ?: System.currentTimeMillis().also { println("smj.seed not set; using $it") }
        return Random(seed + test.hashCode().toLong())
    }

    /** A made-up permission name that is not on any list. */
    fun permission(r: Random) = "com.example.p${r.nextInt(1000, 999999)}.permission.P${r.nextInt(100, 999)}"

    /** A made-up application id. */
    fun appId(r: Random) = "org.example.app${r.nextInt(1000, 999999)}"

    fun manifest(
        appId: String,
        permissions: List<String> = emptyList(),
        sdk23: List<String> = emptyList(),
        allowBackup: String? = "false",
        extractionRules: String? = "@xml/data_extraction_rules",
    ): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$appId">""").append('\n')
        permissions.forEach { append("""    <uses-permission android:name="$it" />""").append('\n') }
        sdk23.forEach { append("""    <uses-permission-sdk-23 android:name="$it" />""").append('\n') }
        append("    <application")
        if (allowBackup != null) append(""" android:allowBackup="$allowBackup"""")
        if (extractionRules != null) append(""" android:dataExtractionRules="$extractionRules"""")
        append(""" android:fullBackupContent="false" android:label="X">""").append('\n')
        append("    </application>\n</manifest>\n")
    }

    fun extractionRules(missing: Set<Pair<String, String>> = emptySet()): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append("\n<data-extraction-rules>\n")
        for (section in ManifestRules.SECTIONS) {
            append("    <$section>\n")
            for (domain in ManifestRules.DOMAINS) if ((section to domain) !in missing) append("""        <exclude domain="$domain" path="." />""").append('\n')
            append("    </$section>\n")
        }
        append("</data-extraction-rules>\n")
    }

    class Case(val cls: String, val name: String, val failure: String? = null, val skipped: Boolean = false, val error: Boolean = false)

    fun junitXml(suite: String, cases: List<Case>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<testsuite name="$suite" tests="${cases.size}" skipped="${cases.count { it.skipped }}" failures="${cases.count { it.failure != null }}" errors="0">""").append('\n')
        for (c in cases) {
            append("""  <testcase name="${c.name}" classname="${c.cls}" time="0.01"""")
            when {
                c.failure != null -> {
                    val tag = if (c.error) "error" else "failure"
                    append(">\n    <$tag message=\"${esc(c.failure)}\" type=\"java.lang.AssertionError\">${esc(c.failure)}\n\tat Somewhere.kt:1</$tag>\n  </testcase>\n")
                }
                c.skipped -> append(">\n    <skipped/>\n  </testcase>\n")
                else -> append("/>\n")
            }
        }
        append("  <system-out><![CDATA[]]></system-out>\n</testsuite>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;").replace("\n", "&#10;")
}
