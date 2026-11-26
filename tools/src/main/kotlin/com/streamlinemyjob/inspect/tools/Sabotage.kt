package com.streamlinemyjob.inspect.tools

import java.io.File

/**
 * The self-test's sabotages (designs/stage-1-foundation.md 4.6). Each finds an anchor and inserts
 * or changes text in a clone of the repo. If the anchor is not found, [apply] returns false and the
 * self-test reports `SELFTEST_ANCHOR <name>`: a fail, never a silent pass.
 */
class Sabotage(
    val name: String,
    /** The phases given to `scripts/check --only`. */
    val only: List<String>,
    /** The phase whose failure line must show [code]. */
    val phase: String,
    /** The code (and its argument) that must appear in that failure line. */
    val code: String,
    /** Text that must also appear in that failure line, if any (`failing-test`: the test's class). */
    val mentions: String? = null,
    private val change: (File) -> Boolean,
) {
    /** Applies the change to the clone at [root]; false if the anchor was not found. */
    fun apply(root: File): Boolean = change(root)

    /** What the run must print: `FAIL` and a failure line `<phase>: <code>…` (naming [mentions] if set). */
    fun matches(failureLine: String): Boolean {
        val prefix = "$phase: "
        if (!failureLine.startsWith(prefix)) return false
        val text = failureLine.removePrefix(prefix)
        val codeHit = text == code || text.startsWith("$code ") || text.startsWith("$code:") || text.startsWith("$code (")
        return codeHit && (mentions == null || text.contains(mentions))
    }

    companion object {
        const val MANIFEST = "app/src/main/AndroidManifest.xml"
        const val SEED = "app/src/main/assets/seed/lists.json"
        const val LINKS = "app/src/main/assets/drawing/links.json"
        const val CORE_TEST_DIR = "core/src/test/kotlin/com/streamlinemyjob/inspect/core"
        const val SABOTAGE_CLASS = "SelfTestSabotage"
        const val INTERNET = """<uses-permission android:name="android.permission.INTERNET"/>"""
        const val BACKUP_OFF = """android:allowBackup="false""""
        const val BACKUP_ON = """android:allowBackup="true""""
        private val MANIFEST_OPENING = Regex("<manifest\\b[^>]*>")
        private val VALUES_KEY = Regex("\"values\"\\s*:\\s*\\[")
        private val ID_FIELD = Regex("\"id\"\\s*:\\s*\"([^\"\\\\]*)\"")
        private val REGION_FIELD = Regex("(\"region\"\\s*:\\s*\")[^\"\\\\]*")

        /** The sabotages added so far (S1.6, S1.7, S1.26), in the order 4.6's table lists them. */
        val ALL: List<Sabotage> = listOf(
            Sabotage("failing-test", listOf("unit"), "unit", "UNIT", mentions = SABOTAGE_CLASS) { root ->
                val dir = File(root, CORE_TEST_DIR)
                if (!dir.isDirectory) return@Sabotage false
                File(dir, "$SABOTAGE_CLASS.kt").writeText(
                    """
                    |package com.streamlinemyjob.inspect.core
                    |
                    |import org.junit.Assert.fail
                    |import org.junit.Test
                    |
                    |/** Planted by scripts/selftest (designs/stage-1-foundation.md 4.6); never committed. */
                    |class $SABOTAGE_CLASS {
                    |    @Test
                    |    fun fails() {
                    |        fail("planted by scripts/selftest")
                    |    }
                    |}
                    |""".trimMargin(),
                )
                true
            },
            Sabotage("internet", listOf("build", "manifest"), "manifest", "MANIFEST_DENIED android.permission.INTERNET") { root ->
                edit(File(root, MANIFEST)) { text ->
                    val opening = MANIFEST_OPENING.find(text) ?: return@edit null
                    text.substring(0, opening.range.last + 1) + "\n    " + INTERNET + text.substring(opening.range.last + 1)
                }
            },
            Sabotage("allow-backup", listOf("build", "manifest"), "manifest", "MANIFEST_BACKUP") { root ->
                edit(File(root, MANIFEST)) { text -> if (text.contains(BACKUP_OFF)) text.replace(BACKUP_OFF, BACKUP_ON) else null }
            },
            Sabotage("seed-dup-id", listOf("seed"), "seed", "SEED_ID_DUP") { root ->
                edit(File(root, SEED)) { text -> secondValueIdAsFirst(text) }
            },
            Sabotage("links-unknown-region", listOf("seed"), "seed", "LINKS_REGION_UNKNOWN") { root ->
                edit(File(root, LINKS)) { text -> firstRegionUnknown(text) }
            },
        )

        /**
         * The seed file with the second value's id changed to equal the first value's (4.6): the first
         * two `"id"` fields after the first `"values": [`. Null (no anchor) if there are not two.
         */
        internal fun secondValueIdAsFirst(text: String): String? {
            val values = VALUES_KEY.find(text) ?: return null
            val first = ID_FIELD.find(text, values.range.last + 1) ?: return null
            val second = ID_FIELD.find(text, first.range.last + 1) ?: return null
            val idRange = second.groups[1]!!.range
            return text.substring(0, idRange.first) + first.groupValues[1] + text.substring(idRange.last + 1)
        }

        /** The link file with the first `"region": "…"` value changed to `no-such-region` (4.6). Null (no anchor) if there is none. */
        internal fun firstRegionUnknown(text: String): String? {
            val region = REGION_FIELD.find(text) ?: return null
            return text.substring(0, region.groups[1]!!.range.last + 1) + "no-such-region" + text.substring(region.range.last + 1)
        }

        /** Rewrites [file] with [change]'s result; false if the file is missing or [change] found no anchor (null). */
        private fun edit(file: File, change: (String) -> String?): Boolean {
            if (!file.isFile) return false
            val changed = change(file.readText()) ?: return false
            file.writeText(changed)
            return true
        }
    }
}
