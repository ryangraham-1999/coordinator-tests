package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * `tools selftest` (designs/stage-1-foundation.md 4.6) against real git repos. The clone's check is
 * replaced by a fake that reads the clone's files the way the real phases would (a planted test
 * fails `unit`, the INTERNET permission or backups on fail `manifest`, an id used twice in the seed
 * file fails `seed`), so the tests show that each
 * sabotage is planted, reset before the next, and judged by its code.
 */
class SelfTestTest {
    private lateinit var dir: File
    private lateinit var work: File
    private lateinit var temps: File
    private val r = Fixtures.random("SelfTestTest")
    private val owner = "o${r.nextInt(100, 999)}"
    private val repo = "r${r.nextInt(100, 999)}"
    private val calls = mutableListOf<Pair<List<String>, Map<String, Boolean>>>()
    private val origins = mutableListOf<String>()

    @Before fun setUp() {
        dir = Fixtures.tempDir("selftest-test")
        work = File(dir, "work")
        temps = File(dir, "temps").apply { mkdirs() }
    }

    @After fun clean() { dir.deleteRecursively() }

    private fun git(vararg args: String, cwd: File = work): String {
        val res = Proc.SYSTEM.run(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false") + args, cwd)
        assertEquals("git ${args.joinToString(" ")}: ${res.err}", 0, res.exit)
        return res.out.trim()
    }

    private fun write(path: String, text: String) = File(work, path).apply { parentFile.mkdirs(); writeText(text) }

    /** A committed and pushed repo with the files the sabotages look for; origin set to a GitHub URL. */
    private fun pushedRepo(device: String = "actions", manifest: String = MANIFEST, seed: String = SEED, links: String = LINKS, readme: String = "Run `scripts/check` and `scripts/check --fast`; `scripts/check-trimmer` is separate.\n") {
        val bare = File(dir, "remote.git")
        git("init", "-q", "--bare", bare.path, cwd = dir)
        git("init", "-q", "-b", "stage-x", work.path, cwd = dir)
        write("config/check.properties", "device=$device\n")
        write(Sabotage.MANIFEST, manifest)
        write(Sabotage.SEED, seed)
        write(Sabotage.LINKS, links)
        write(Sabotage.README, readme)
        write("${Sabotage.CORE_TEST_DIR}/SomeTest.kt", "package x\n")
        write(".gitignore", "build/\n")
        git("add", "-A"); git("commit", "-q", "-m", "one")
        git("remote", "add", "origin", bare.path)
        git("push", "-q", "-u", "origin", "stage-x")
        git("remote", "set-url", "origin", "https://github.com/$owner/$repo.git")
    }

    /** What the real check would print for the clone as it is now. */
    private fun fakeCheck(cleanPass: Boolean = true, override: (String) -> ProcResult? = { null }) =
        { clone: File, args: List<String>, log: File ->
            val manifest = File(clone, Sabotage.MANIFEST).readText()
            val state = mapOf(
                "test" to File(clone, "${Sabotage.CORE_TEST_DIR}/${Sabotage.SABOTAGE_CLASS}.kt").isFile,
                "internet" to manifest.contains("android.permission.INTERNET"),
                "backup" to manifest.contains(Sabotage.BACKUP_ON),
                "dupid" to File(clone, Sabotage.SEED).readText().let { t -> ID.findAll(t).map { it.groupValues[1] }.toList().let { it.size != it.toSet().size } },
                "badlink" to File(clone, Sabotage.LINKS).readText().contains("no-such-region"),
                "noreadme" to !File(clone, Sabotage.README).readText().contains("scripts/check`"),
                "leftover" to File(clone, "build/leftover.txt").isFile,
            )
            calls += args to state
            origins += Proc.SYSTEM.run(listOf("git", "remote", "get-url", "origin"), clone).out.trim()
            File(clone, "build").mkdirs(); File(clone, "build/leftover.txt").writeText("kept by git clean -e build\n")
            val only = args.getOrNull(args.indexOf("--only") + 1)?.takeIf { "--only" in args }
            val failures = mutableListOf<String>()
            if (only == null || "unit" in only) if (state["test"] == true) failures += "unit: UNIT com.streamlinemyjob.inspect.core.SelfTestSabotage.fails: planted by scripts/selftest"
            if (only == null || "manifest" in only) {
                if (state["internet"] == true) failures += "manifest: MANIFEST_DENIED android.permission.INTERNET (release, debug)"
                if (state["backup"] == true) failures += "manifest: MANIFEST_BACKUP"
            }
            if (only == null || "seed" in only) {
                if (state["dupid"] == true) failures += "seed: SEED_ID_DUP lists[0].values[1].id (planted)"
                if (state["badlink"] == true) failures += "seed: LINKS_REGION_UNKNOWN links[0].region (planted)"
            }
            if (only == null || "docs" in only) if (state["noreadme"] == true) failures += "docs: DOCS_SCRIPT_MISSING check"
            val name = if (only == null) "clean" else only
            override(name) ?: run {
                val pass = failures.isEmpty() && (only != null || cleanPass)
                val text = buildString {
                    append("Picked up JAVA_TOOL_OPTIONS: -Dx\n")
                    append(if (pass) "CHECK PASS  place=cloud  …\n" else "CHECK FAIL  place=cloud  …\n")
                    failures.forEach { append(it).append('\n') }
                    if (!pass && failures.isEmpty()) append("device: DEVICE_TIMEOUT (planted)\n")
                }
                log.writeText(text)
                ProcResult(if (pass) 0 else 1, text, "")
            }
        }

    private fun selftest(check: (File, List<String>, File) -> ProcResult, args: List<String> = emptyList()): Pair<Int, List<String>> {
        val buf = ByteArrayOutputStream()
        val st = SelfTest(work, Proc.SYSTEM, PrintStream(buf, true), sabotages = Sabotage.ALL, runCheck = check, tempParent = temps)
        val code = st.run(args)
        assertEquals("the temporary clone is deleted, pass or fail", emptyList<String>(), temps.list()!!.toList())
        return code to buf.toString().lines().filter { it.isNotBlank() }
    }

    @Test
    fun everyRunAsExpectedPassesSevenOfSevenWithTheCloneDeleted() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck())
        assertEquals(lines.joinToString("\n"), 0, code)
        assertEquals(8, lines.size)
        assertEquals("selftest clean                PASS (as expected)", lines[0])
        assertEquals("selftest failing-test         FAIL unit UNIT …SelfTestSabotage.fails (as expected)", lines[1])
        assertEquals("selftest internet             FAIL manifest MANIFEST_DENIED android.permission.INTERNET (release, debug) (as expected)", lines[2])
        assertEquals("selftest allow-backup         FAIL manifest MANIFEST_BACKUP (as expected)", lines[3])
        assertEquals("selftest seed-dup-id          FAIL seed SEED_ID_DUP lists[0].values[1].id (planted) (as expected)", lines[4])
        assertEquals("selftest ${"links-unknown-region".padEnd(20)} FAIL seed LINKS_REGION_UNKNOWN links[0].region (planted) (as expected)", lines[5])
        assertEquals("selftest readme               FAIL docs DOCS_SCRIPT_MISSING check (as expected)", lines[6])
        assertTrue(lines[7], Regex("SELFTEST PASS  7/7  time=\\d+m\\d\\ds").matches(lines[7]))
    }

    @Test
    fun theCleanRunIsTheFullCheckWithASeedAndEachSabotageRunsOnlyItsPhasesOnAResetClone() {
        pushedRepo()
        selftest(fakeCheck())
        assertEquals(7, calls.size)
        assertEquals("--seed", calls[0].first[0]); assertEquals(2, calls[0].first.size); calls[0].first[1].toLong()
        assertEquals(listOf("--only", "unit"), calls[1].first)
        assertEquals(listOf("--only", "build,manifest"), calls[2].first)
        assertEquals(listOf("--only", "build,manifest"), calls[3].first)
        assertEquals(listOf("--only", "seed"), calls[4].first)
        assertEquals(listOf("--only", "seed"), calls[5].first)
        assertEquals(listOf("--only", "docs"), calls[6].first)
        // nothing planted in the clean run; each sabotage alone in its own run (the one before was reset)
        assertEquals(mapOf("test" to false, "internet" to false, "backup" to false, "dupid" to false, "badlink" to false, "noreadme" to false, "leftover" to false), calls[0].second)
        assertEquals(mapOf("test" to true, "internet" to false, "backup" to false, "dupid" to false, "badlink" to false, "noreadme" to false, "leftover" to true), calls[1].second)
        assertEquals(mapOf("test" to false, "internet" to true, "backup" to false, "dupid" to false, "badlink" to false, "noreadme" to false, "leftover" to true), calls[2].second)
        assertEquals(mapOf("test" to false, "internet" to false, "backup" to true, "dupid" to false, "badlink" to false, "noreadme" to false, "leftover" to true), calls[3].second)
        assertEquals(mapOf("test" to false, "internet" to false, "backup" to false, "dupid" to true, "badlink" to false, "noreadme" to false, "leftover" to true), calls[4].second)
        assertEquals(mapOf("test" to false, "internet" to false, "backup" to false, "dupid" to false, "badlink" to true, "noreadme" to false, "leftover" to true), calls[5].second)
        assertEquals(mapOf("test" to false, "internet" to false, "backup" to false, "dupid" to false, "badlink" to false, "noreadme" to true, "leftover" to true), calls[6].second)
        // the clone keeps the repo's origin, so env and device see the same remote
        assertEquals(List(7) { "https://github.com/$owner/$repo.git" }, origins)
        // the repo itself is untouched
        assertEquals("", git("status", "--porcelain"))
    }

    @Test
    fun aDirtyTreeIsRefusedAndNothingRuns() {
        pushedRepo()
        write("new.txt", "x\n")
        val (code, lines) = selftest(fakeCheck())
        assertEquals(1, code)
        assertEquals(listOf("SELFTEST FAIL  SELFTEST_DIRTY (the working tree has changes; commit first)"), lines)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun inActionsModeAnUnpushedHeadIsRefusedButInGmdModeItRuns() {
        pushedRepo()
        write("a.txt", "a\n"); git("add", "a.txt"); git("commit", "-q", "-m", "two")
        val (code, lines) = selftest(fakeCheck())
        assertEquals(1, code)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("SELFTEST FAIL  SELFTEST_NOT_PUSHED (HEAD is not pushed"))
        assertTrue(calls.isEmpty())

        write("config/check.properties", "device=gmd\n"); git("commit", "-q", "-am", "gmd")
        val (gmdCode, _) = selftest(fakeCheck())
        assertEquals(0, gmdCode)
    }

    @Test
    fun aSabotageThatPassesIsNotAsExpected() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck { name ->
            if (name == "unit") ProcResult(0, "CHECK PASS (only: unit)  place=cloud  …\n", "") else null
        })
        assertEquals(1, code)
        assertEquals("selftest failing-test         CHECK PASS (only: unit)  place=cloud  … (expected FAIL unit UNIT; see build/check/selftest-failing-test.log)", lines[1])
        assertTrue(lines.last(), lines.last().startsWith("SELFTEST FAIL  6/7  time="))
        assertTrue(lines.last(), lines.last().endsWith("not as expected: failing-test"))
    }

    @Test
    fun aSabotageThatFailsWithAnotherCodeIsNotAsExpected() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck { name ->
            if (name == "build,manifest") ProcResult(1, "CHECK FAIL (only: build,manifest)\nbuild: BUILD e: something\n", "") else null
        })
        assertEquals(1, code)
        assertTrue(lines[2], lines[2].startsWith("selftest internet             FAIL build: BUILD e: something (expected FAIL manifest MANIFEST_DENIED android.permission.INTERNET;"))
        assertTrue(lines[3], lines[3].contains("(expected FAIL manifest MANIFEST_BACKUP;"))
        assertTrue(lines.last(), lines.last().startsWith("SELFTEST FAIL  5/7") && lines.last().endsWith("internet, allow-backup"))
    }

    @Test
    fun theCodeMustBeInAFailureLineOfThatPhaseNotJustAnywhere() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck { name ->
            if (name == "unit") ProcResult(1, "CHECK FAIL (only: unit)\nunit: UNIT_NONE\nenv: UNIT com.streamlinemyjob.inspect.core.SelfTestSabotage.fails: in another phase\n", "") else null
        })
        assertEquals(1, code)
        assertTrue(lines[1], lines[1].startsWith("selftest failing-test         FAIL unit: UNIT_NONE (expected FAIL unit UNIT;"))
    }

    @Test
    fun aCheckThatCouldNotRunIsNotAsExpected() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck { name ->
            if (name == "clean") ProcResult(2, "CHECK FAIL tools did not build; see build/check/tools-build.log\n", "") else null
        })
        assertEquals(1, code)
        assertTrue(lines[0], lines[0].startsWith("selftest clean                COULD NOT RUN (exit 2) CHECK FAIL tools did not build"))
    }

    @Test
    fun aFailingCleanRunIsNotAsExpected() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck(cleanPass = false))
        assertEquals(1, code)
        assertTrue(lines[0], lines[0].startsWith("selftest clean                CHECK FAIL  place=cloud  … (expected PASS; seed="))
        assertTrue(lines.last(), lines.last().startsWith("SELFTEST FAIL  6/7") && lines.last().endsWith("not as expected: clean"))
    }

    @Test
    fun aMissingAnchorIsSelftestAnchorNeverASilentPass() {
        pushedRepo(manifest = MANIFEST.replace(Sabotage.BACKUP_OFF, ""))
        val (code, lines) = selftest(fakeCheck())
        assertEquals(1, code)
        assertEquals("selftest allow-backup         SELFTEST_ANCHOR allow-backup", lines[3])
        assertEquals(6, calls.size) // no check is run for a sabotage that could not be planted
        assertTrue(lines.last(), lines.last().endsWith("not as expected: allow-backup"))
    }

    @Test
    fun aSeedWithoutTwoValueIdsIsSelftestAnchorForSeedDupId() {
        pushedRepo(seed = "{ \"format\": \"smj-seed\", \"version\": 1, \"lists\": [] }\n")
        val (code, lines) = selftest(fakeCheck())
        assertEquals(1, code)
        assertEquals("selftest seed-dup-id          SELFTEST_ANCHOR seed-dup-id", lines[4])
        assertEquals(6, calls.size)
    }

    @Test
    fun aLinkFileWithoutARegionIsSelftestAnchorForLinksUnknownRegion() {
        pushedRepo(links = "{ \"format\": \"smj-links\", \"version\": 1, \"drawing\": \"d\", \"links\": [] }\n")
        val (code, lines) = selftest(fakeCheck())
        assertEquals(1, code)
        assertEquals("selftest ${"links-unknown-region".padEnd(20)} SELFTEST_ANCHOR links-unknown-region", lines[5])
        assertEquals(6, calls.size)
    }

    @Test
    fun aReadmeWithoutScriptsCheckIsSelftestAnchorForReadme() {
        pushedRepo(readme = "Nothing about the check here.\n")
        val (code, lines) = selftest(fakeCheck())
        assertEquals(1, code)
        assertEquals("selftest readme               SELFTEST_ANCHOR readme", lines[6])
        assertEquals(6, calls.size)
    }

    @Test
    fun linksUnknownRegionChangesOnlyTheFirstRegionValue() {
        val r = Fixtures.random("linksUnknownRegionChangesOnlyTheFirstRegionValue")
        val names = List(3) { "reg${r.nextInt(1000, 9999)}-$it" }
        val text = """{ "format": "smj-links", "version": 1, "drawing": "d", "links": [
            |  { "region": "${names[0]}", "value": "v1" }, {"region" :"${names[1]}","value":"v2"}, { "region": "${names[2]}", "value": "v3" } ] }""".trimMargin()
        assertEquals(text.replace("\"${names[0]}\"", "\"no-such-region\""), Sabotage.firstRegionUnknown(text))
        assertEquals(null, Sabotage.firstRegionUnknown("{ \"links\": [] }"))
    }

    @Test
    fun seedDupIdGivesTheSecondValueTheFirstValuesIdAndChangesNothingElse() {
        val r = Fixtures.random("seedDupIdGivesTheSecondValueTheFirstValuesIdAndChangesNothingElse")
        val ids = List(4) { "v${r.nextInt(1000, 9999)}-$it" }
        val text = """{ "format": "smj-seed", "version": 1, "lists": [ { "id": "l-${r.nextInt(100)}", "role": "AREA", "name": "N",
            |  "values": [ { "id": "${ids[0]}", "name": "a" }, {"id":"${ids[1]}","name":"b"}, { "id": "${ids[2]}", "name": "c" } ] },
            |  { "id": "${ids[3]}", "role": "DAMAGE", "name": "M", "values": [] } ] }""".trimMargin()
        val changed = Sabotage.secondValueIdAsFirst(text)!!
        assertEquals(text.replace("\"${ids[1]}\"", "\"${ids[0]}\""), changed)
        assertEquals(null, Sabotage.secondValueIdAsFirst("{ \"lists\": [ { \"id\": \"x\", \"values\": [ { \"id\": \"y\" } ] } ] }"))
    }

    @Test
    fun argumentsAreRefusedWithExitTwo() {
        pushedRepo()
        val (code, lines) = selftest(fakeCheck(), listOf("--fast"))
        assertEquals(2, code)
        assertTrue(lines[0], lines[0].startsWith("SELFTEST FAIL bad arguments: --fast"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aGitFailureStillDeletesTheClone() {
        pushedRepo()
        val failingGit = Proc { cmd, d -> if (cmd.getOrNull(1) == "clean") ProcResult(1, "", "planted") else Proc.SYSTEM.run(cmd, d) }
        val st = SelfTest(work, failingGit, PrintStream(ByteArrayOutputStream()), runCheck = fakeCheck(), tempParent = temps)
        val e = runCatching { st.run(emptyList()) }.exceptionOrNull()
        assertTrue("$e", e is IllegalStateException && e.message!!.contains("planted"))
        assertEquals(emptyList<String>(), temps.list()!!.toList())
        assertFalse(st.lastClone!!.exists())
    }

    companion object {
        val LINKS = """
            |{ "format": "smj-links", "version": 1, "drawing": "d", "links": [
            |  { "region": "r-1", "value": "a-1" }, { "region": "r-2", "value": "a-2" } ] }
            |""".trimMargin()
        private val ID = Regex("\"id\"\\s*:\\s*\"([^\"]*)\"")
        val SEED = """
            |{ "format": "smj-seed", "version": 1, "lists": [
            |  { "id": "a", "role": "AREA", "name": "A", "values": [ { "id": "a-1", "name": "One" }, { "id": "a-2", "name": "Two" } ] },
            |  { "id": "d", "role": "DAMAGE", "name": "D", "values": [ { "id": "d-1", "name": "One" } ] },
            |  { "id": "c", "role": "ACTION", "name": "C", "values": [] } ] }
            |""".trimMargin()
        val MANIFEST = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |
            |    <application
            |        android:allowBackup="false"
            |        android:label="x">
            |    </application>
            |</manifest>
            |""".trimMargin()
    }
}
