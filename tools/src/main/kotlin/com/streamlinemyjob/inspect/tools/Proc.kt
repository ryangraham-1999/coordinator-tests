package com.streamlinemyjob.inspect.tools

import java.io.File
import java.util.concurrent.TimeUnit

/** The result of running a program: its exit code and what it printed. */
data class ProcResult(val exit: Int, val out: String, val err: String)

/** Runs programs. Tests replace it with fakes (designs/stage-1-foundation.md 6: a fake `gh`). */
fun interface Proc {
    /** Runs [cmd] in [dir]. Throws if the program cannot be started at all. */
    fun run(cmd: List<String>, dir: File): ProcResult

    companion object {
        val SYSTEM = Proc { cmd, dir ->
            val p = ProcessBuilder(cmd).directory(dir).start()
            p.outputStream.close()
            var err = ""
            val errReader = Thread { err = p.errorStream.bufferedReader().readText() }.apply { start() }
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.MINUTES)
            errReader.join()
            ProcResult(p.exitValue(), out, err)
        }
    }
}

/**
 * Runs Gradle for a phase. All output goes to `build/check/<log>.log` (4.5: nothing else goes to
 * the console). Returns Gradle's exit code.
 */
fun interface Gradle {
    fun run(log: String, args: List<String>): Int

    companion object {
        fun system(root: File, checkDir: File): Gradle = Gradle { log, args ->
            val windows = System.getProperty("os.name").startsWith("Windows")
            val launcher = if (windows) listOf("cmd", "/c", File(root, "gradlew.bat").path) else listOf(File(root, "gradlew").path)
            checkDir.mkdirs()
            val logFile = File(checkDir, "$log.log")
            val p = ProcessBuilder(launcher + "--console=plain" + args)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            p.outputStream.close()
            p.waitFor()
        }

        /** Up to [max] lines that say what went wrong: Kotlin `e:` lines and Gradle's "What went wrong" text. */
        fun errorLines(log: File, max: Int = 10): List<String> {
            if (!log.isFile) return emptyList()
            val lines = log.readLines()
            val found = mutableListOf<String>()
            var i = 0
            while (i < lines.size && found.size < max) {
                val line = lines[i]
                if (line.startsWith("e: ")) {
                    found += line.trim()
                } else if (line.trim() == "* What went wrong:") {
                    var j = i + 1
                    while (j < lines.size && lines[j].isNotBlank() && found.size < max) {
                        found += lines[j].trim(); j++
                    }
                    i = j
                }
                i++
            }
            return found
        }
    }
}
