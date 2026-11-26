package com.streamlinemyjob.inspect.tools

import java.io.File
import kotlin.system.exitProcess

/** Entry point of `tools <command>`; scripts/<command> launches it (designs/stage-1-foundation.md 4.5). */
fun main(args: Array<String>) {
    val root = File(System.getProperty("user.dir")).absoluteFile
    val rest = args.drop(1)
    val code = when (args.firstOrNull()) {
        "make-key" -> MakeKey.run(File(System.getProperty("user.home")), Place.fromEnvironment(), System.out)
        "check" -> try {
            Check(
                root = root,
                place = Place.fromEnvironment(),
                env = System.getenv(),
                home = File(System.getProperty("user.home")),
                gradle = Gradle.system(root, File(root, "build/check")),
                proc = Proc.SYSTEM,
                out = System.out,
            ).run(rest)
        } catch (e: Throwable) {
            println("CHECK FAIL tools crashed: ${e.javaClass.name}: ${e.message}")
            2
        }
        "publish" -> try {
            Publish(root, File(System.getProperty("user.home")), Place.fromEnvironment(), Proc.SYSTEM, System.out).run()
        } catch (e: Throwable) {
            println("PUBLISH_GH tools crashed: ${e.javaClass.name}: ${e.message}")
            1
        }
        "device-report" -> DeviceReport.run(root, rest, System.out)
        "selftest" -> try {
            // UTF-8, so the "…" of 4.6's lines is not printed as "?" where the JVM's console charset is ASCII.
            SelfTest(root = root, proc = Proc.SYSTEM, out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8")).run(rest)
        } catch (e: Throwable) {
            println("SELFTEST FAIL tools crashed: ${e.javaClass.name}: ${e.message}")
            2
        }
        "trimmer-testset" -> {
            // designs/stage-B-trimmer.md 4.10: regenerate trimmer/TrimmerTestData.bas and trimmer/testset/ from the seed.
            val files = com.streamlinemyjob.inspect.tools.trimmer.TestSetGenerator.files()
            com.streamlinemyjob.inspect.tools.trimmer.CheckTrimmer.write(File(root, "trimmer"), files)
            println("TRIMMER TESTSET written: ${files.size} files in trimmer/ (seed ${com.streamlinemyjob.inspect.tools.trimmer.TestSetGenerator.SEED})")
            0
        }
        "check-trimmer" -> try {
            com.streamlinemyjob.inspect.tools.trimmer.CheckTrimmer(root, Gradle.system(root, File(root, "build/check")), System.out).run()
        } catch (e: Throwable) {
            println("TRIMMER CHECK FAIL tools crashed: ${e.javaClass.name}: ${e.message}")
            1
        }
        else -> {
            System.out.println("usage: tools make-key | tools publish | tools check [options] | tools device-report [<report folder>...] | tools selftest | tools trimmer-testset | tools check-trimmer")
            2
        }
    }
    System.out.flush()
    exitProcess(code)
}
