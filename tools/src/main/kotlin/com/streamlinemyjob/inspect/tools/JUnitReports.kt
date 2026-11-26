package com.streamlinemyjob.inspect.tools

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads JUnit XML reports (designs/stage-1-foundation.md 4.5, "JUnit XML"): counts `<testcase>`
 * elements and their `<failure>`/`<error>` and `<skipped>` children. Reports are found by
 * searching a folder for `.xml` files whose root is `testsuite` or `testsuites`; no AGP sub-folder
 * name is written here.
 */
object JUnitReports {
    data class Case(val name: String, val failure: String?, val skipped: Boolean)

    data class Tally(val cases: List<Case>) {
        val total get() = cases.size
        val skipped get() = cases.filter { it.skipped }
        val failed get() = cases.filter { it.failure != null }
        /** Tests that ran: every test case except skipped ones. */
        val ran get() = total - skipped.size
        val passed get() = ran - failed.size

        operator fun plus(other: Tally) = Tally(cases + other.cases)
    }

    val EMPTY = Tally(emptyList())

    fun read(dir: File): Tally {
        if (!dir.isDirectory) return EMPTY
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.sortedBy { it.path }.toList()
        return Tally(files.flatMap { readFile(it) })
    }

    fun readFile(file: File): List<Case> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        if (root.tagName != "testsuite" && root.tagName != "testsuites") return emptyList()
        val nodes = root.getElementsByTagName("testcase")
        return (0 until nodes.length).map { i ->
            val tc = nodes.item(i) as Element
            val cls = tc.getAttribute("classname")
            val name = if (cls.isEmpty()) tc.getAttribute("name") else "$cls.${tc.getAttribute("name")}"
            val problem = child(tc, "failure") ?: child(tc, "error")
            val message = problem?.let { firstLine(it.getAttribute("message")) ?: firstLine(it.textContent) ?: "(no message)" }
            Case(name, message, child(tc, "skipped") != null)
        }
    }

    private fun child(e: Element, tag: String): Element? {
        val kids = e.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k is Element && k.tagName == tag) return k
        }
        return null
    }

    private fun firstLine(s: String?): String? = s?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

    /** `412`, `411/412`, `410, 2 skipped`, `408/410, 2 skipped`. */
    fun counts(t: Tally, suffix: String? = null): String {
        val base = if (t.failed.isEmpty()) "${t.ran}" else "${t.passed}/${t.ran}"
        val skipped = if (t.skipped.isNotEmpty()) ", ${t.skipped.size} skipped" else ""
        val extra = if (suffix != null) ", $suffix" else ""
        return base + skipped + extra
    }

    /** Failure lines capped at [max], then `… and N more`. */
    fun cap(lines: List<String>, max: Int = 20): List<String> =
        if (lines.size <= max) lines else lines.take(max) + "… and ${lines.size - max} more"
}
