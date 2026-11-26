package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Docs phase (designs/stage-1-foundation.md 4.18 docs rules, 6): one test per DOCS_* code. */
class DocsRulesTest {
    private val root = Fixtures.tempDir("smj-docs-test")
    private val scripts = File(root, "scripts").apply { mkdirs() }
    private val readme = File(root, "README.md")
    private val r = Fixtures.random(DocsRulesTest::class.qualifiedName!!)
    /** Made-up script names, each with a POSIX launcher and a .cmd. */
    private val names = List(r.nextInt(1, 6)) { "tool${it}x${r.nextInt(10, 99)}" }.onEach {
        File(scripts, it).writeText("#!/bin/sh\n"); File(scripts, "$it.cmd").writeText("@echo off\r\n")
    }

    @After fun clean() { root.deleteRecursively() }

    private fun good() = "# Title\n" + names.joinToString("\n") { "- `scripts/$it` or `scripts/$it --flag`" } + "\nSeed: `${DocsRules.SEED_PATH}`. Drawing: `${DocsRules.DRAWING_PATH}`, links: `${DocsRules.LINKS_PATH}`.\n"

    @Test
    fun aReadmeNamingEveryScriptAndTheSeedPathPasses() {
        readme.writeText(good())
        assertEquals(emptyList<String>(), DocsRules.check(root))
    }

    @Test
    fun noReadmeIsDocsMissing() {
        assertEquals(listOf("DOCS_MISSING"), DocsRules.check(root))
    }

    @Test
    fun aScriptNamedOnlyOutsideBackticksIsMissing() {
        val gone = names.random(r)
        readme.writeText(good().replace("`scripts/$gone`", "scripts/$gone").replace("`scripts/$gone --flag`", "the $gone script"))
        assertEquals(listOf("DOCS_SCRIPT_MISSING $gone"), DocsRules.check(root))
    }

    @Test
    fun aScriptWithOnlyACmdFileStillCounts() {
        File(scripts, "onlycmd.cmd").writeText("@echo off\r\n")
        readme.writeText(good())
        assertEquals(listOf("DOCS_SCRIPT_MISSING onlycmd"), DocsRules.check(root))
    }

    @Test
    fun noSeedPathIsDocsSeedPath() {
        readme.writeText(good().replace(DocsRules.SEED_PATH, "the seed file"))
        assertEquals(listOf("DOCS_SEED_PATH"), DocsRules.check(root))
    }

    @Test
    fun noDrawingPathIsDocsDrawingPath() {
        readme.writeText(good().replace(DocsRules.DRAWING_PATH, "the drawing"))
        assertEquals(listOf("DOCS_DRAWING_PATH"), DocsRules.check(root))
    }

    @Test
    fun noLinkFilePathIsDocsDrawingPath() {
        readme.writeText(good().replace(DocsRules.LINKS_PATH, "the link file"))
        assertEquals(listOf("DOCS_DRAWING_PATH"), DocsRules.check(root))
    }
}
