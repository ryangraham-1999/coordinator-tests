package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Deps phase (designs/stage-1-foundation.md 4.4, 6): fixture dependency lists for every DEPS_* code. */
class DepsRulesTest {
    private val dir = Fixtures.tempDir("smj-deps-test")
    private val deps = File(dir, "release-runtime-deps.txt")
    private val allow = File(dir, "allowed-dependency-groups.txt")
    private val r = Fixtures.random(DepsRulesTest::class.qualifiedName!!)

    @After fun clean() { dir.deleteRecursively() }

    /** A made-up allowlist and modules under it, so nothing depends on the stage 1 list. */
    private val prefixes = List(r.nextInt(2, 6)) { "org.p${r.nextInt(100, 999)}x$it" }

    private fun module(group: String) = "$group:lib${r.nextInt(1, 99)}:${r.nextInt(0, 9)}.${r.nextInt(0, 20)}.${r.nextInt(0, 9)}"

    private fun check(lines: List<String>, allowed: List<String> = prefixes): List<String> {
        allow.writeText("# groups\n" + allowed.joinToString("\n") + "\n")
        deps.writeText(lines.joinToString("\n", postfix = "\n"))
        return DepsRules.check(deps, allow)
    }

    private fun okLines() = prefixes.flatMap { p -> listOf(module(p), module("$p.sub${r.nextInt(1, 9)}")) } + "project :core"

    @Test
    fun allowedGroupsAndSubgroupsAndCorePass() {
        assertEquals(emptyList<String>(), check(okLines().shuffled(r)))
    }

    @Test
    fun aGroupOutsideTheAllowlistIsNotAllowed() {
        val bad = module("net.other${r.nextInt(100, 999)}")
        assertEquals(listOf("DEPS_NOT_ALLOWED $bad"), check(okLines() + bad))
    }

    @Test
    fun aPrefixOnlyMatchesAtADot() {
        val bad = module(prefixes[0] + "extra")
        assertEquals(listOf("DEPS_NOT_ALLOWED $bad"), check(okLines() + bad))
    }

    @Test
    fun everyDeniedPrefixIsDeniedEvenWhenAllowedAndCheckedFirst() {
        for (denied in DepsRules.DENIED) {
            val exact = module(denied)
            val sub = module("$denied.inner")
            // The allowlist also lists the denied group's first part: deny still wins.
            assertEquals(listOf("DEPS_DENIED $exact", "DEPS_DENIED $sub"), check(listOf(exact, sub), prefixes + denied.substringBefore('.')))
        }
    }

    @Test
    fun onlyCoreMayBeAProjectDependency() {
        val got = check(okLines() + "project :testdata" + "project :tools")
        assertEquals(listOf("DEPS_PROJECT :testdata", "DEPS_PROJECT :tools"), got)
    }

    @Test
    fun anEmptyOrMissingListIsDepsEmpty() {
        assertEquals(listOf("DEPS_EMPTY"), check(emptyList()))
        deps.delete()
        assertEquals(listOf("DEPS_EMPTY"), DepsRules.check(deps, allow))
    }
}
