package com.streamlinemyjob.inspect.core

import java.io.File
import kotlin.system.exitProcess

/**
 * `gradlew :core:validateSeed` (designs/stage-1-foundation.md 4.5, seed phase): validates the seed
 * file, then the drawing file, then the link file against both (4.20, 4.22; C19). Arguments: the seed
 * file, the drawing file, the link file, and the file to write the result to: `OK`, or one error per
 * line (`SEED_*`, `DRAWING_*` or `LINKS_*`, then its JSON path). Exit code 0 when all are valid, 1 when not.
 * The link file is checked only when the seed and the drawing are both valid, since it is checked against both.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "usage: ValidateSeed <seed file> <drawing file> <links file> <result file>" }
    val result = File(args[3])
    val lines = validateAll(File(args[0]), File(args[1]), File(args[2]))
    result.parentFile?.mkdirs()
    result.writeText(if (lines.isEmpty()) "OK\n" else lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    lines.forEach { println(it) }
    exitProcess(if (lines.isEmpty()) 0 else 1)
}

/** The error lines for the three files; empty when all are valid. */
internal fun validateAll(seedFile: File, drawingFile: File, linksFile: File): List<String> {
    val lines = mutableListOf<String>()
    val seed = read(seedFile)
    val seedParsed = seed.text?.let { Seed.parse(it) }
    if (seed.text == null) lines += SeedError(Codes.SEED_JSON, "", seed.problem).toString()
    if (seedParsed is Seed.Invalid) lines += seedParsed.errors.map { it.toString() }

    val drawingRead = read(drawingFile)
    val drawingParsed = drawingRead.text?.let { Drawing.parse(it) }
    if (drawingRead.text == null) lines += FileError(Codes.DRAWING_JSON, "", drawingRead.problem).toString()
    if (drawingParsed is Drawing.Companion.Invalid) lines += drawingParsed.errors.map { it.toString() }

    val linksRead = read(linksFile)
    if (linksRead.text == null) {
        lines += FileError(Codes.LINKS_JSON, "", linksRead.problem).toString()
    } else if (seedParsed is Seed.Valid && drawingParsed is Drawing.Companion.Valid) {
        lines += Links.validate(linksRead.text, seedParsed.seed, drawingParsed.drawing).map { it.toString() }
    }
    return lines
}

private class Read(val text: String?, val problem: String)

private fun read(file: File): Read = try {
    Read(file.readText(Charsets.UTF_8), "")
} catch (e: java.io.IOException) {
    Read(null, "cannot read ${file.path}: ${e.message}")
}
