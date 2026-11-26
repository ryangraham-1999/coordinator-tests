package com.streamlinemyjob.inspect.tools

import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.Properties

/**
 * `tools selftest` (designs/stage-1-foundation.md 4.6), launched by `scripts/selftest`. Proves that
 * the check passes on a clean clone of the committed repo, that it reads nothing left over in the
 * working folder, and that each sabotage makes it fail with the expected code.
 * Exit codes: 0 every run as expected, 1 a run not as expected or a refusal, 2 the self-test could not run.
 */
class SelfTest(
    private val root: File,
    private val proc: Proc,
    private val out: PrintStream,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sabotages: List<Sabotage> = Sabotage.ALL,
    /** Runs the clone's `scripts/check` with [args], writing everything it prints to [log]. */
    private val runCheck: (clone: File, args: List<String>, log: File) -> ProcResult = ::systemCheck,
    /** Where the temporary clone is made (the system temp directory unless a test says otherwise). */
    private val tempParent: File? = null,
) {
    val checkDir = File(root, "build/check")

    /** The temporary clone of the last run (deleted at the end); tests read it to see it is gone. */
    var lastClone: File? = null
        private set

    fun run(args: List<String>): Int {
        if (args.isNotEmpty()) {
            out.println("SELFTEST FAIL bad arguments: ${args.joinToString(" ")}; usage: $USAGE")
            return 2
        }
        val start = clock()

        // 1. It tests what is committed (and, in actions mode, pushed).
        val status = proc.run(listOf("git", "status", "--porcelain"), root)
        if (status.exit != 0) return refuse("SELFTEST_DIRTY (git status failed: ${status.err.lineSequence().firstOrNull().orEmpty()})")
        if (status.out.isNotBlank()) return refuse("SELFTEST_DIRTY (the working tree has changes; commit first)")
        val mode = deviceMode() ?: run {
            out.println("SELFTEST FAIL config/check.properties must say device=gmd or device=actions")
            return 2
        }
        if (mode == "actions") {
            val actions = DeviceActions(root, proc)
            if (actions.pushedHead() == null) return refuse("SELFTEST_NOT_PUSHED (${actions.notPushed})")
        }

        checkDir.mkdirs()
        val temp = (if (tempParent != null) Files.createTempDirectory(tempParent.toPath(), PREFIX) else Files.createTempDirectory(PREFIX)).toFile()
        val clone = File(temp, "repo")
        lastClone = clone
        try {
            // 2. A local clone whose origin is the repo's origin, so env and device see the same remote.
            git(temp, "clone", "--local", "-q", root.absolutePath, clone.absolutePath)
            val origin = git(root, "remote", "get-url", "origin").trim()
            git(clone, "remote", "set-url", "origin", origin)

            var asExpected = 0
            val notAsExpected = mutableListOf<String>()
            fun record(name: String, ok: Boolean, text: String) {
                out.println("selftest ${name.padEnd(NAME_WIDTH)} $text")
                out.flush()
                if (ok) asExpected++ else notAsExpected += name
            }

            // 3. The full check on the clean clone must pass.
            val seed = clock()
            val clean = runCheck(clone, listOf("--seed", seed.toString()), log("clean"))
            val cleanLines = clean.out.lines().filter { it.isNotBlank() }
            val cleanHead = cleanLines.firstOrNull { it.startsWith("CHECK ") }.orEmpty()
            if (clean.exit == 0 && cleanHead.startsWith("CHECK PASS")) {
                record("clean", true, "PASS (as expected)")
            } else {
                record("clean", false, "${outcome(clean, cleanHead)} (expected PASS; seed=$seed; see build/check/selftest-clean.log)")
            }

            // 4. Each sabotage on a reset clone must fail with its code.
            for (s in sabotages) {
                git(clone, "checkout", "--", ".")
                git(clone, "clean", "-fdx", "-e", "build")
                val expected = "FAIL ${s.phase} ${s.code}"
                if (!s.apply(clone)) {
                    record(s.name, false, "SELFTEST_ANCHOR ${s.name}")
                    continue
                }
                val r = runCheck(clone, listOf("--only", s.only.joinToString(",")), log(s.name))
                val lines = r.out.lines().filter { it.isNotBlank() }
                val head = lines.firstOrNull { it.startsWith("CHECK ") }.orEmpty()
                val hit = lines.firstOrNull { s.matches(it) }
                if (r.exit == 1 && head.startsWith("CHECK FAIL") && hit != null) {
                    record(s.name, true, "FAIL ${s.phase} ${shorten(hit.removePrefix("${s.phase}: "))} (as expected)")
                } else {
                    val first = lines.drop(lines.indexOf(head) + 1).firstOrNull()
                    val shown = if (r.exit == 1 && first != null) "FAIL $first" else outcome(r, head)
                    record(s.name, false, "$shown (expected $expected; see build/check/selftest-${s.name}.log)")
                }
            }

            // 5. The final line.
            val total = 1 + sabotages.size
            val time = Summary.duration(clock() - start)
            return if (notAsExpected.isEmpty()) {
                out.println("SELFTEST PASS  $asExpected/$total  time=$time")
                0
            } else {
                out.println("SELFTEST FAIL  $asExpected/$total  time=$time  not as expected: ${notAsExpected.joinToString(", ")}")
                1
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun refuse(why: String): Int {
        out.println("SELFTEST FAIL  $why")
        return 1
    }

    private fun log(name: String) = File(checkDir, "selftest-$name.log").also { it.delete() }

    private fun git(dir: File, vararg args: String): String {
        val r = proc.run(listOf("git") + args, dir)
        if (r.exit != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed (exit ${r.exit}): ${r.err.lineSequence().firstOrNull().orEmpty()}")
        return r.out
    }

    private fun deviceMode(): String? {
        val file = File(root, "config/check.properties")
        if (!file.isFile) return null
        val mode = Properties().apply { file.inputStream().use { load(it) } }.getProperty("device")?.trim()
        return mode?.takeIf { it in Check.DEVICE_MODES }
    }

    /** How a run that was not as expected ended: its summary line, or what stopped it. */
    private fun outcome(r: ProcResult, head: String): String = when {
        r.exit == 2 || head.isEmpty() -> "COULD NOT RUN (exit ${r.exit}) ${head.ifEmpty { r.out.lines().firstOrNull { it.isNotBlank() }.orEmpty() }}".trim()
        else -> head
    }

    companion object {
        const val USAGE = "scripts/selftest (no arguments)"
        const val PREFIX = "smj-selftest-"
        private const val NAME_WIDTH = 20
        private val PACKAGE = Regex("\\b(?:[a-z][a-z0-9_]*\\.)+(?=[A-Z])")

        /** A test failure `UNIT com.x.y.SelfTestSabotage.fails: message` → `UNIT …SelfTestSabotage.fails` (4.6's example lines); other codes unchanged. */
        fun shorten(failure: String): String {
            if (!failure.startsWith("UNIT ") && !failure.startsWith("DEVICE ")) return failure
            return PACKAGE.replace(failure.substringBefore(": "), "…")
        }

        /** The clone's own launcher: `scripts/check` (or `scripts\check.cmd` on Windows); waits as long as the check takes. */
        fun systemCheck(clone: File, args: List<String>, log: File): ProcResult {
            val windows = System.getProperty("os.name").startsWith("Windows")
            val launcher = if (windows) listOf("cmd", "/c", File(clone, "scripts\\check.cmd").path) else listOf(File(clone, "scripts/check").path)
            log.parentFile.mkdirs()
            val p = ProcessBuilder(launcher + args)
                .directory(clone)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            p.outputStream.close()
            val exit = p.waitFor()
            return ProcResult(exit, log.readText(), "")
        }
    }
}
