package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * `tools check` end to end with a fake Gradle and fake git/gh (designs/stage-1-foundation.md 4.5, 6):
 * phase order and prerequisites, the summary forms, exit codes, `last-pass.txt`, stale reports,
 * the seed, and TOOLS_ERROR.
 */
class CheckTest {
    private val dir = Fixtures.tempDir("smj-check-test")
    private val root = File(dir, "repo").apply { mkdirs() }
    private val home = File(dir, "home").apply { mkdirs() }
    private val sdk = File(dir, "sdk")
    private val jdk = File(dir, "jdk").apply { File(this, "bin").mkdirs(); File(this, "bin/javac").writeText(""); File(this, "bin/javac.exe").writeText("") }
    private val checkDir = File(root, "build/check")
    private val r = Fixtures.random(CheckTest::class.qualifiedName!!)
    private val appId = Fixtures.appId(r)
    private val sha = (1..40).map { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
    private val unitCount = r.nextInt(5, 40)

    /** What the fake Gradle does for each phase's log name, and the calls it saw. */
    private val gradleCalls = mutableListOf<Pair<String, List<String>>>()
    private var onGradle: (String, List<String>) -> Int = ::goodGradle
    private var checkRun = """{"check_runs": [{"name": "smj-device", "id": 1, "head_sha": "$sha", "status": "completed", "completed_at": "x", "output": {"summary": "DEVICE PASS(3)"}}]}"""
    private var dirty = ""

    init {
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText("android {\n    compileSdk = 40\n}\n")
        File(sdk, "platforms/android-40").mkdirs()
        File(sdk, "build-tools/40.0.0").mkdirs()
        File(root, "config").mkdirs()
        File(root, "config/check.properties").writeText("device=actions\n")
        File(root, "config/allowed-permissions.txt").writeText("\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\n")
        File(root, "config/allowed-dependency-groups.txt").writeText("org.example\n")
        File(root, "app/src/main/res/xml").mkdirs()
        File(root, "app/src/main/res/xml/data_extraction_rules.xml").writeText(Fixtures.extractionRules())
        File(root, "scripts").mkdirs()
        File(root, "scripts/check").writeText("#!/bin/sh\n")
        File(root, "README.md").writeText("`scripts/check`\n${DocsRules.SEED_PATH}\n")
    }

    @After fun clean() { dir.deleteRecursively() }

    private fun goodGradle(log: String, args: List<String>): Int {
        when (log) {
            "build" -> {
                ManifestRules.manifestFile(checkDir, "release").writeText(Fixtures.manifest(appId, listOf("$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")))
                ManifestRules.manifestFile(checkDir, "debug").writeText(Fixtures.manifest("$appId.debug"))
                DepsRules.depsFile(checkDir).writeText("org.example:a:1.0\nproject :core\n")
            }
            "seed" -> File(checkDir, "seed-result.txt").writeText("OK\n")
            "unit" -> {
                writeReport("core", List(unitCount) { Fixtures.Case("c.CoreTest", "t$it") })
                writeReport("tools", listOf(Fixtures.Case("t.ToolsTest", "a")))
                File(checkDir, "timings.txt").writeText("gen500=2.5\n")
            }
        }
        return 0
    }

    private fun writeReport(module: String, cases: List<Fixtures.Case>, sub: String = "test") {
        val d = File(root, "$module/build/test-results/$sub").apply { mkdirs() }
        File(d, "TEST-$module-${r.nextInt()}.xml").writeText(Fixtures.junitXml(module, cases))
    }

    private val proc = Proc { cmd, _ ->
        when {
            cmd == listOf("git", "--version") -> ProcResult(0, "git version 2\n", "")
            cmd == listOf("git", "remote", "get-url", "origin") -> ProcResult(0, "https://github.com/o/r.git\n", "")
            cmd == listOf("git", "status", "--porcelain") -> ProcResult(0, dirty, "")
            cmd == listOf("git", "rev-parse", "HEAD") || cmd == listOf("git", "rev-parse", "@{u}") -> ProcResult(0, "$sha\n", "")
            cmd.first() == "gh" -> ProcResult(0, checkRun, "")
            else -> error("unexpected $cmd")
        }
    }

    private class Run(val code: Int, val lines: List<String>)

    private fun check(vararg args: String, place: Place = Place.CLOUD, env: Map<String, String> = mapOf("ANDROID_HOME" to sdk.path)): Run {
        val buf = ByteArrayOutputStream()
        var t = 1_000_000L
        val code = PrintStream(buf, true, "UTF-8").use { out ->
            Check(
                root, place, env, home,
                gradle = Gradle { log, a -> gradleCalls += log to a; File(checkDir, "$log.log").appendText("ran\n"); onGradle(log, a) },
                proc = proc, out = out, clock = { t.also { t += 20_000 } }, javaHome = jdk, javaVersion = "21",
                deviceActions = { log -> DeviceActions(root, proc, clock = { 0L }, sleep = {}, timeoutMillis = 0, log = log) },
            ).run(args.toList())
        }
        return Run(code, buf.toString("UTF-8").lines().filter { it.isNotEmpty() })
    }

    private val lastPass get() = File(checkDir, "last-pass.txt")

    @Test
    fun aFullPassPrintsOneLineExitsZeroAndWritesLastPass() {
        val run = check("--seed", "77")
        assertEquals(0, run.code)
        val line = run.lines.single()
        val deviceSeed = DeviceActions.deviceSeed(sha)
        assertEquals(
            "CHECK PASS  place=cloud  env:PASS build:PASS seed:PASS manifest:PASS deps:PASS unit:PASS(${unitCount + 1}) device:PASS(3, actions) docs:PASS  release:CLOUD  gen500:2.5s  seed=77  device-seed=$deviceSeed  time=",
            line.substringBefore("time=") + "time=",
        )
        assertEquals("sha=$sha\nplace=cloud\nsummary=$line\n", lastPass.readText())
        // The run's seed reaches every Gradle test task, and the build in the cloud has no assembleRelease.
        assertTrue(gradleCalls.first { it.first == "unit" }.second.containsAll(listOf("-Psmj.seed=77", "--continue")))
        assertFalse(gradleCalls.first { it.first == "build" }.second.contains("assembleRelease"))
    }

    @Test
    fun theSeedComesFromTheClockWithoutSeedFlag() {
        val line = check().lines.first()
        assertTrue(line, line.contains("  seed=1000000  "))
    }

    @Test
    fun onThePcTheBuildIncludesTheSignedReleaseAndTheKeyIsNeeded() {
        val smj = File(home, ".smj").apply { mkdirs() }
        File(smj, "release.jks").writeText("x")
        File(smj, "keystore.properties").writeText("storeFile=${File(smj, "release.jks").absolutePath.replace("\\", "\\\\")}\n")
        val run = check(place = Place.PC)
        assertEquals(0, run.code)
        assertTrue(run.lines[0].contains("place=pc") && run.lines[0].contains("release:SIGNED"))
        assertTrue(gradleCalls.first { it.first == "build" }.second.contains("assembleRelease"))
    }

    @Test
    fun everyOtherRunDeletesLastPassFirst() {
        check()
        assertTrue(lastPass.isFile)
        val fast = check("--fast")
        assertEquals(0, fast.code)
        assertTrue(fast.lines[0], fast.lines[0].startsWith("CHECK PASS (fast: device skipped)  place=cloud") && fast.lines[0].contains("device:SKIP(fast)"))
        assertFalse(lastPass.exists())
        check(); assertTrue(lastPass.isFile)
        val only = check("--only", "manifest")
        assertTrue(only.lines[0], only.lines[0].startsWith("CHECK PASS (only: manifest)  place=cloud  env:PASS build:PASS manifest:PASS  release:CLOUD"))
        assertFalse(lastPass.exists())
        check(); assertTrue(lastPass.isFile)
        onGradle = { log, a -> if (log == "unit") { writeReport("core", listOf(Fixtures.Case("c.X", "bad", "nope"))); 1 } else goodGradle(log, a) }
        assertEquals(1, check().code)
        assertFalse(lastPass.exists())
        check("--nonsense")
        assertFalse(lastPass.exists())
    }

    @Test
    fun badArgumentsExitTwo() {
        for (args in listOf(arrayOf("--nonsense"), arrayOf("--only", "nope"), arrayOf("--device", "phone"), arrayOf("--seed", "x"), arrayOf("--seed"))) {
            val run = check(*args)
            assertEquals(args.joinToString(" "), 2, run.code)
            assertTrue(run.lines.single().startsWith("CHECK FAIL bad arguments: "))
        }
        File(root, "config/check.properties").writeText("device=phone\n")
        assertEquals(2, check().code)
        assertEquals(0, check("--device", "actions").code)
    }

    @Test
    fun aFailedPrerequisiteSkipsItsDependentsAndIndependentPhasesStillRun() {
        val run = check(env = emptyMap())
        assertEquals(1, run.code)
        assertTrue(run.lines[0], run.lines[0].startsWith("CHECK FAIL  place=cloud  env:FAIL build:SKIP(after env) seed:SKIP(after env) manifest:SKIP(after build) deps:SKIP(after build) unit:SKIP(after env) device:SKIP(after build) docs:PASS  "))
        assertEquals("env: ENV_SDK no ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties", run.lines[1])
        assertTrue(gradleCalls.isEmpty())
    }

    @Test
    fun aBuildFailureShowsTheFirstErrorLinesAndSkipsWhatNeedsIt() {
        onGradle = { log, a ->
            if (log == "build") { File(checkDir, "build.log").appendText("e: file:///x/A.kt:3:1 Unresolved reference 'y'.\n\n* What went wrong:\nExecution failed for task ':app:compileDebugKotlin'.\n> Compilation error.\n\n* Try:\n"); 1 } else goodGradle(log, a)
        }
        val run = check()
        assertTrue(run.lines[0].contains("build:FAIL seed:PASS manifest:SKIP(after build) deps:SKIP(after build) unit:PASS"))
        assertEquals(listOf("build: BUILD e: file:///x/A.kt:3:1 Unresolved reference 'y'.", "build: BUILD Execution failed for task ':app:compileDebugKotlin'.", "build: BUILD > Compilation error."), run.lines.drop(1))
    }

    @Test
    fun staleExportsAndReportsAreDeletedSoTheyCannotPass() {
        check()
        onGradle = { _, _ -> 0 } // Gradle "succeeds" but writes nothing this time
        val run = check()
        assertTrue(run.lines[0], run.lines[0].contains("manifest:FAIL deps:FAIL unit:FAIL "))
        assertTrue(run.lines.contains("seed: SEED_NOT_RUN unexpected result in ${File(checkDir, "seed-result.txt").path}; see build/check/seed.log"))
        assertTrue(run.lines.contains("manifest: MANIFEST_NO_EXPORT merged-manifest-release.xml (release)"))
        assertTrue(run.lines.contains("deps: DEPS_EMPTY"))
        assertTrue(run.lines.contains("unit: UNIT_NONE"))
        assertTrue(run.lines[0].contains("gen500:none"))
    }

    @Test
    fun unitFailuresSkippedTestsAndNoTests() {
        onGradle = { log, a ->
            if (log == "unit") {
                writeReport("core", listOf(Fixtures.Case("c.A", "ok"), Fixtures.Case("c.A", "bad", "expected 1\nbut 2")))
                writeReport("app", listOf(Fixtures.Case("a.B", "off", skipped = true)), sub = "testDebugUnitTest")
                writeReport("tools", listOf(Fixtures.Case("t.C", "maybe", skipped = true)))
                1
            } else goodGradle(log, a)
        }
        val run = check()
        assertTrue(run.lines[0], run.lines[0].contains("unit:FAIL(1/2, 2 skipped)"))
        // A skipped test in core, testdata or app fails the phase; one in tools is counted and shown.
        assertEquals(listOf("unit: UNIT c.A.bad: expected 1", "unit: UNIT_SKIPPED a.B.off"), run.lines.filter { it.startsWith("unit: ") })
    }

    @Test
    fun seedErrorsAreShownWithTheirPaths() {
        onGradle = { log, a -> if (log == "seed") { File(checkDir, "seed-result.txt").writeText("SEED_ID_DUP lists[0].values[1].id (x, also at lists[0].values[0].id)\n"); 1 } else goodGradle(log, a) }
        val run = check()
        assertTrue(run.lines.contains("seed: SEED_ID_DUP lists[0].values[1].id (x, also at lists[0].values[0].id)"))
        assertEquals(1, run.code)
    }

    @Test
    fun drawingAndLinkErrorsAreShownWithTheirPathsToo() {
        val lines = listOf("DRAWING_ANCHOR regions[3].anchor (x)", "LINKS_REGION_UNKNOWN links[0].region (no-such-region is not a region of d)")
        onGradle = { log, a -> if (log == "seed") { File(checkDir, "seed-result.txt").writeText(lines.joinToString("\n", postfix = "\n")); 1 } else goodGradle(log, a) }
        val run = check()
        assertTrue(run.lines[0], run.lines[0].contains("seed:FAIL"))
        assertEquals(lines.map { "seed: $it" }, run.lines.filter { it.startsWith("seed: ") })
        assertEquals(1, run.code)
    }

    @Test
    fun deviceActionsResultsReachTheSummary() {
        dirty = " M README.md\n"
        val run = check()
        assertTrue(run.lines[0].contains("device:FAIL docs:PASS"))
        assertTrue(run.lines.any { it.startsWith("device: DEVICE_NOT_PUSHED") })
        dirty = ""
        checkRun = """{"check_runs": [{"name": "smj-device", "id": 2, "head_sha": "$sha", "status": "completed", "completed_at": "x", "output": {"summary": "DEVICE FAIL(2/3)\nDEVICE a.B.c: no"}}]}"""
        val failed = check()
        assertTrue(failed.lines[0].contains("device:FAIL(2/3, actions)"))
        assertTrue(failed.lines.contains("device: DEVICE a.B.c: no"))
        checkRun = """{"check_runs": []}"""
        assertTrue(check().lines.any { it.startsWith("device: DEVICE_TIMEOUT") })
    }

    @Test
    fun gmdModeRunsTheManagedDeviceWithTheSeedAndReportsAnEmulatorThatWouldNotStart() {
        onGradle = { log, a -> if (log == "device") { File(checkDir, "device.log").appendText("* What went wrong:\nEmulator failed to start: /dev/kvm missing\n\n"); 1 } else goodGradle(log, a) }
        val run = check("--device", "gmd", "--seed", "5")
        assertEquals("device: DEVICE_UNAVAILABLE Emulator failed to start: /dev/kvm missing", run.lines.single { it.startsWith("device:") })
        val deviceArgs = gradleCalls.first { it.first == "device" }.second
        assertTrue(deviceArgs.containsAll(listOf(":app:api34DebugAndroidTest", "-Pandroid.testInstrumentationRunnerArguments.smjSeed=5")))
        assertFalse(run.lines[0].contains("device-seed"))
        onGradle = { log, a ->
            if (log == "device") {
                val d = File(root, "app/build/outputs/androidTest-results/managedDevice/x").apply { mkdirs() }
                File(d, "TEST-a.xml").writeText(Fixtures.junitXml("d", List(4) { Fixtures.Case("d.T", "t$it") })); 0
            } else goodGradle(log, a)
        }
        val ok = check("--device", "gmd")
        assertEquals(0, ok.code)
        assertTrue(ok.lines[0].contains("device:PASS(4) docs:PASS"))
    }

    @Test
    fun aPhaseThatThrowsIsToolsErrorNotSwallowed() {
        File(root, "config/allowed-dependency-groups.txt").delete()
        val run = check()
        assertEquals(1, run.code)
        assertTrue(run.lines[0].contains("deps:FAIL"))
        val line = run.lines.single { it.startsWith("deps: ") }
        assertTrue(line, line.startsWith("deps: TOOLS_ERROR java.io.FileNotFoundException: "))
    }
}
