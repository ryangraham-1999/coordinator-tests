package com.streamlinemyjob.inspect.tools

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest phase (designs/stage-1-foundation.md 4.3), on the merged manifests that
 * `smjExportManifests` writes to `build/check/merged-manifest-<variant>.xml`.
 */
object ManifestRules {
    /** Fixed in code, never configurable (4.3 rule 1). */
    val DENIED = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
    )

    /** Release first, as the summary lists them (`(release, debug)`). */
    val VARIANTS = listOf("release", "debug")

    val DOMAINS = listOf("root", "file", "database", "sharedpref", "external")
    val SECTIONS = listOf("cloud-backup", "device-transfer")

    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    fun manifestFile(checkDir: File, variant: String) = File(checkDir, "merged-manifest-$variant.xml")

    /** Lines of `config/allowed-permissions.txt` without comments and blanks. */
    fun allowlist(file: File): List<String> =
        file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    /**
     * Checks both variants. A problem found in several variants is one line naming them all, for
     * example `MANIFEST_DENIED android.permission.INTERNET (release, debug)`.
     */
    fun check(checkDir: File, allowlistFile: File, resXmlDir: File): List<String> {
        val allowed = allowlist(allowlistFile)
        val found = LinkedHashMap<String, MutableList<String>>()
        for (variant in VARIANTS) {
            for (problem in checkVariant(manifestFile(checkDir, variant), allowed, resXmlDir)) {
                found.getOrPut(problem) { mutableListOf() } += variant
            }
        }
        return found.map { (problem, variants) -> "$problem (${variants.joinToString(", ")})" }
    }

    fun checkVariant(manifest: File, allowed: List<String>, resXmlDir: File): List<String> {
        if (!manifest.isFile) return listOf("MANIFEST_NO_EXPORT ${manifest.name}")
        val root = parse(manifest)
        val appId = root.getAttribute("package")
        val allowedNames = allowed.map { it.replace("\${applicationId}", appId) }.toSet()
        val problems = mutableListOf<String>()

        val requested = listOf("uses-permission", "uses-permission-sdk-23").flatMap { tag ->
            children(root, tag).map { it.getAttributeNS(ANDROID, "name") }
        }.distinct()
        for (name in requested) {
            when {
                name in DENIED -> problems += "MANIFEST_DENIED $name"
                name !in allowedNames -> problems += "MANIFEST_NOT_ALLOWED $name"
            }
        }

        val app = children(root, "application").firstOrNull()
        if (app?.getAttributeNS(ANDROID, "allowBackup") != "false") problems += "MANIFEST_BACKUP"

        val rules = app?.getAttributeNS(ANDROID, "dataExtractionRules").orEmpty()
        if (rules.isEmpty()) {
            problems += "MANIFEST_EXTRACTION_RULES"
        } else {
            problems += checkExtractionRules(rules, resXmlDir)
        }
        return problems
    }

    /** `@xml/<name>` must be a file in `app/src/main/res/xml/` that excludes every domain in both sections. */
    fun checkExtractionRules(reference: String, resXmlDir: File): List<String> {
        val name = reference.removePrefix("@xml/")
        val file = File(resXmlDir, "$name.xml")
        if (!reference.startsWith("@xml/") || !file.isFile) return listOf("MANIFEST_EXTRACTION_RULES $reference (file not found)")
        val root = parse(file)
        val problems = mutableListOf<String>()
        for (section in SECTIONS) {
            val excluded = children(root, section).flatMap { children(it, "exclude") }
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            for (domain in DOMAINS) if (domain !in excluded) problems += "MANIFEST_EXTRACTION_RULES $section $domain"
        }
        return problems
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun children(e: Element, tag: String): List<Element> {
        val kids = e.childNodes
        return (0 until kids.length).map { kids.item(it) }.filterIsInstance<Element>().filter { (it.localName ?: it.tagName) == tag }
    }
}
