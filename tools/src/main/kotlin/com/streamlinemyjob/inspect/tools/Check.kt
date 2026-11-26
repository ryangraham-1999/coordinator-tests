package com.streamlinemyjob.inspect.tools

import java.io.File
import java.io.PrintStream
import java.util.Properties

/**
 * `tools check` (designs/stage-1-foundation.md 4.5):
 * `scripts/check [--fast] [--only <phase>[,<phase>…]] [--device gmd|actions] [--seed <long>]`.
 * Exit codes: 0 pass, 1 check failed, 2 the check itself could not run.
 */
class Check(
    private val root: File,
    private val place: Place,
    private val env: Map<String, String>,
    private val home: File,
    private val gradle: Gradle,
    private val proc: Proc,
    private val out: PrintStream,
    private val clock: () -> Long = System::currentTimeMillis,
    private val javaHome: File = File(System.getProperty("java.home")),
    private val javaVersion: String = System.getProperty("java.version"),
    private val deviceActions: (log: (String) -> Unit) -> DeviceActions = { log -> DeviceActions(root, proc, log = log) },
) {
    val checkDir = File(root, "build/check")
    val lastPass = File(checkDir, "last-pass.txt")
    val timings = File(checkDir, "timings.txt")

    data class Options(val fast: Boolean, val only: List<Phase>?, val device: String?, val seed: Long?)

    class BadArguments(message: String) : Exception(message)

    companion object {
        const val USAGE = "scripts/check [--fast] [--only <phase>[,<phase>...]] [--device gmd|actions] [--seed <long>]"
        val DEVICE_MODES = listOf("gmd", "actions")
        /** The seed phase's result lines start with one of these: the seed's, the drawing's or the link file's codes (4.5, C19). */
        val SEED_CODE_PREFIXES = listOf("SEED_", "DRAWING_", "LINKS_")
        /** Modules whose JUnit reports the unit phase reads, and the ones where a skipped test fails the phase (4.5). */
        val UNIT_MODULES = listOf("core", "testdata", "tools", "app")
        val NO_SKIP_MODULES = setOf("core", "testdata", "app")
        val UNIT_TASKS = listOf(":core:test", ":testdata:test", ":tools:test", ":app:testDebugUnitTest")
        val BUILD_TASKS_CLOUD = listOf("assembleDebug", "assembleDebugAndroidTest", "smjExportManifests", "smjExportRuntimeDeps")
        val BUILD_TASKS_PC = listOf("assembleDebug", "assembleRelease", "assembleDebugAndroidTest", "smjExportManifests", "smjExportRuntimeDeps")
        const val DEVICE_TASK = ":app:api34DebugAndroidTest"

        fun parse(args: List<String>): Options {
            var fast = false
            var only: List<Phase>? = null
            var device: String? = null
            var seed: Long? = null
            var i = 0
            fun value(flag: String): String = args.getOrNull(++i) ?: throw BadArguments("$flag needs a value")
            while (i < args.size) {
                when (val a = args[i]) {
                    "--fast" -> fast = true
                    "--only" -> only = value(a).split(',').map { id -> Phase.byId(id.trim()) ?: throw BadArguments("unknown phase '$id'") }.distinct()
                    "--device" -> device = value(a).also { if (it !in DEVICE_MODES) throw BadArguments("--device must be gmd or actions") }
                    "--seed" -> seed = value(a).toLongOrNull() ?: throw BadArguments("--seed needs a whole number")
                    else -> throw BadArguments("unknown argument '$a'")
                }
                i++
            }
            return Options(fast, only, device, seed)
        }
    }

    fun run(args: List<String>): Int {
        val start = clock()
        checkDir.mkdirs()
        lastPass.delete() // any run deletes it first, so a stale pass cannot be read (4.5)
        val options = try {
            parse(args).also { deviceMode(it) }
        } catch (e: BadArguments) {
            out.println("CHECK FAIL bad arguments: ${e.message}; usage: $USAGE")
            return 2
        }
        val mode = deviceMode(options)
        val seed = options.seed ?: start
        val selected: Set<Phase> = options.only?.flatMap { it.withPrerequisites() }?.toSet() ?: Phase.entries.toSet()
        if (Phase.UNIT in selected) timings.delete()

        val results = LinkedHashMap<Phase, PhaseResult>()
        var deviceSeed: Long? = null
        for (phase in Phase.entries) {
            if (phase !in selected) continue
            val pre = phase.prerequisite
            val preResult = pre?.let { results[it] }
            results[phase] = when {
                phase == Phase.DEVICE && options.fast -> PhaseResult(Status.SKIP, skipReason = "fast")
                pre != null && preResult?.status != Status.PASS -> PhaseResult(Status.SKIP, skipReason = "after ${pre.id}")
                else -> guarded {
                    when (phase) {
                        Phase.ENV -> env()
                        Phase.BUILD -> build()
                        Phase.SEED -> seed()
                        Phase.MANIFEST -> PhaseResult.of(ManifestRules.check(checkDir, File(root, "config/allowed-permissions.txt"), File(root, "app/src/main/res/xml")))
                        Phase.DEPS -> PhaseResult.of(DepsRules.check(DepsRules.depsFile(checkDir), File(root, "config/allowed-dependency-groups.txt")))
                        Phase.UNIT -> unit(seed)
                        Phase.DEVICE -> if (mode == "actions") {
                            deviceSeed = headSha()?.let { DeviceActions.deviceSeed(it) }
                            actions()
                        } else gmd(seed)
                        Phase.DOCS -> PhaseResult.of(DocsRules.check(root))
                    }
                }
            }
        }

        val summary = Summary(
            place = place,
            fast = options.fast,
            only = options.only,
            results = results.toList(),
            release = Summary.release(place, results[Phase.BUILD]),
            gen500 = readGen500(),
            seed = seed,
            deviceSeed = deviceSeed,
            millis = clock() - start,
        )
        val lines = summary.lines()
        lines.forEach { out.println(it) }
        if (summary.isFullPass) {
            lastPass.writeText("sha=${headSha().orEmpty()}\nplace=${place.label}\nsummary=${lines.first()}\n")
        }
        return if (summary.pass) 0 else 1
    }

    private fun deviceMode(options: Options): String {
        options.device?.let { return it }
        val file = File(root, "config/check.properties")
        val mode = if (file.isFile) Properties().apply { file.inputStream().use { load(it) } }.getProperty("device")?.trim() else null
        if (mode !in DEVICE_MODES) throw BadArguments("config/check.properties must say device=gmd or device=actions")
        return mode!!
    }

    /** A phase that throws inside tools is FAIL with `TOOLS_ERROR <exception class>: <message>`, never swallowed (4.5). */
    private fun guarded(block: () -> PhaseResult): PhaseResult = try {
        block()
    } catch (e: Exception) {
        PhaseResult(Status.FAIL, failures = listOf("TOOLS_ERROR ${e.javaClass.name}: ${e.message}"))
    }

    private fun log(name: String) = File(checkDir, "$name.log")

    private fun freshLog(name: String): File = log(name).also { it.delete() }

    private fun env(): PhaseResult {
        val check = EnvCheck(root, place, env, home, javaHome, javaVersion, proc)
        val problems = check.run()
        freshLog("env").writeText((check.found + problems).joinToString("\n", postfix = "\n"))
        return PhaseResult.of(problems)
    }

    private fun gradleFailure(logName: String): List<String> =
        Gradle.errorLines(log(logName)).map { "BUILD $it" }.ifEmpty { listOf("BUILD see build/check/$logName.log") }

    private fun build(): PhaseResult {
        ManifestRules.VARIANTS.forEach { ManifestRules.manifestFile(checkDir, it).delete() }
        DepsRules.depsFile(checkDir).delete()
        freshLog("build")
        val tasks = if (place == Place.PC) BUILD_TASKS_PC else BUILD_TASKS_CLOUD
        val exit = gradle.run("build", tasks)
        return if (exit == 0) PhaseResult(Status.PASS) else PhaseResult(Status.FAIL, failures = gradleFailure("build"))
    }

    private fun seed(): PhaseResult {
        val result = File(checkDir, "seed-result.txt").also { it.delete() }
        freshLog("seed")
        val exit = gradle.run("seed", listOf(":core:validateSeed"))
        val lines = if (result.isFile) result.readLines().filter { it.isNotBlank() } else emptyList()
        return when {
            lines == listOf("OK") && exit == 0 -> PhaseResult(Status.PASS)
            lines.isNotEmpty() && lines.all { line -> SEED_CODE_PREFIXES.any { line.startsWith(it) } } -> PhaseResult(Status.FAIL, failures = lines)
            exit != 0 && lines.isEmpty() -> PhaseResult(Status.FAIL, failures = listOf("SEED_NOT_RUN see build/check/seed.log") + gradleFailure("seed"))
            else -> PhaseResult(Status.FAIL, failures = listOf("SEED_NOT_RUN unexpected result in ${result.path}; see build/check/seed.log"))
        }
    }

    private fun seedArgs(seed: Long) = listOf("-Psmj.seed=$seed", "-Psmj.checkDir=${checkDir.absolutePath}")

    private fun unit(seed: Long): PhaseResult {
        UNIT_MODULES.forEach { File(root, "$it/build/test-results").deleteRecursively() }
        freshLog("unit")
        val exit = gradle.run("unit", listOf("--continue") + UNIT_TASKS + seedArgs(seed))
        var all = JUnitReports.EMPTY
        val skippedFailures = mutableListOf<String>()
        for (m in UNIT_MODULES) {
            val t = JUnitReports.read(File(root, "$m/build/test-results"))
            all += t
            if (m in NO_SKIP_MODULES) skippedFailures += t.skipped.map { "UNIT_SKIPPED ${it.name}" }
        }
        if (all.ran == 0 && all.skipped.isEmpty()) {
            return PhaseResult(Status.FAIL, failures = listOf("UNIT_NONE") + if (exit != 0) gradleFailure("unit") else emptyList())
        }
        val failures = all.failed.map { "UNIT ${it.name}: ${it.failure}" } + skippedFailures +
            (if (exit != 0 && all.failed.isEmpty()) gradleFailure("unit") else emptyList())
        return PhaseResult.of(failures, JUnitReports.counts(all))
    }

    private fun gmd(seed: Long): PhaseResult {
        val dir = DeviceReport.reportDir(root).also { it.deleteRecursively() }
        freshLog("device")
        val exit = gradle.run("device", listOf(DEVICE_TASK, "-Pandroid.testInstrumentationRunnerArguments.smjSeed=$seed") + seedArgs(seed))
        val tally = JUnitReports.read(dir)
        if (tally.total == 0 && exit != 0) {
            val why = Gradle.errorLines(log("device"), 1).firstOrNull() ?: "see build/check/device.log"
            return PhaseResult(Status.FAIL, failures = listOf("DEVICE_UNAVAILABLE $why"))
        }
        val r = DeviceReport.parse(DeviceReport.text(tally), null)
        return if (exit != 0 && r.status == Status.PASS) r.copy(status = Status.FAIL, failures = gradleFailure("device")) else r
    }

    private fun actions(): PhaseResult {
        val logFile = freshLog("device")
        return deviceActions { line -> logFile.appendText("$line\n") }.run()
    }

    private fun headSha(): String? = try {
        proc.run(listOf("git", "rev-parse", "HEAD"), root).takeIf { it.exit == 0 }?.out?.trim()
    } catch (e: Exception) { null }

    private fun readGen500(): String? =
        if (timings.isFile) timings.readLines().firstOrNull { it.startsWith("gen500=") }?.substringAfter('=')?.trim() else null
}
