package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Manifest phase (designs/stage-1-foundation.md 4.3, 6): fixture manifests for every MANIFEST_* code. */
class ManifestRulesTest {
    private val dir = Fixtures.tempDir("smj-manifest-test")
    private val checkDir = File(dir, "check").apply { mkdirs() }
    private val resXml = File(dir, "res/xml").apply { mkdirs() }
    private val allowlist = File(dir, "allowed-permissions.txt")
    private val r = Fixtures.random(ManifestRulesTest::class.qualifiedName!!)
    private val appId = Fixtures.appId(r)

    @After fun clean() { dir.deleteRecursively() }

    private fun write(release: String?, debug: String?, allowed: List<String>, rules: String? = Fixtures.extractionRules()) {
        release?.let { ManifestRules.manifestFile(checkDir, "release").writeText(it) }
        debug?.let { ManifestRules.manifestFile(checkDir, "debug").writeText(it) }
        allowlist.writeText("# comment line\n" + allowed.joinToString("\n") { "$it   # trailing comment" } + "\n\n")
        rules?.let { File(resXml, "data_extraction_rules.xml").writeText(it) }
    }

    private fun check() = ManifestRules.check(checkDir, allowlist, resXml)

    private fun both(f: (String) -> String) = write(f(appId), f("$appId.debug"), listOf("\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"))

    @Test
    fun placeholderExpandsToEachVariantsOwnId() {
        both { id -> Fixtures.manifest(id, listOf("$id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) }
        assertEquals(emptyList<String>(), check())
    }

    @Test
    fun placeholderDoesNotAllowAnotherVariantsId() {
        // The release id's permission in the debug manifest is not the debug variant's own.
        write(Fixtures.manifest(appId), Fixtures.manifest("$appId.debug", listOf("$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")),
            listOf("\${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"))
        assertEquals(listOf("MANIFEST_NOT_ALLOWED $appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION (debug)"), check())
    }

    @Test
    fun everyDeniedPermissionIsDeniedInBothTagsEvenWhenAllowlisted() {
        for (denied in ManifestRules.DENIED) {
            write(Fixtures.manifest(appId, listOf(denied)), Fixtures.manifest("$appId.debug", sdk23 = listOf(denied)), listOf(denied))
            assertEquals(listOf("MANIFEST_DENIED $denied (release, debug)"), check())
        }
    }

    @Test
    fun internetIsDeniedWithBothVariantsNamed() {
        both { id -> Fixtures.manifest(id, listOf("android.permission.INTERNET")) }
        assertEquals(listOf("MANIFEST_DENIED android.permission.INTERNET (release, debug)"), check())
    }

    @Test
    fun aPermissionNotInTheAllowlistIsNotAllowedUntilListed() {
        val p = Fixtures.permission(r)
        write(Fixtures.manifest(appId, listOf(p)), Fixtures.manifest("$appId.debug"), emptyList())
        assertEquals(listOf("MANIFEST_NOT_ALLOWED $p (release)"), check())
        write(Fixtures.manifest(appId, listOf(p)), Fixtures.manifest("$appId.debug"), listOf(p))
        assertEquals(emptyList<String>(), check())
    }

    @Test
    fun aListedPermissionThatIsAbsentIsFine() {
        write(Fixtures.manifest(appId), Fixtures.manifest("$appId.debug"), List(5) { Fixtures.permission(r) })
        assertEquals(emptyList<String>(), check())
    }

    @Test
    fun allowBackupMustBeExactlyFalse() {
        for (value in listOf("true", "False", "", null)) {
            both { id -> Fixtures.manifest(id, allowBackup = value) }
            assertEquals("allowBackup=$value", listOf("MANIFEST_BACKUP (release, debug)"), check())
        }
    }

    @Test
    fun extractionRulesAttributeMissing() {
        write(Fixtures.manifest(appId, extractionRules = null), Fixtures.manifest("$appId.debug"), emptyList())
        assertEquals(listOf("MANIFEST_EXTRACTION_RULES (release)"), check())
    }

    @Test
    fun extractionRulesFileMissing() {
        write(Fixtures.manifest(appId), Fixtures.manifest("$appId.debug"), emptyList(), rules = null)
        assertEquals(listOf("MANIFEST_EXTRACTION_RULES @xml/data_extraction_rules (file not found) (release, debug)"), check())
    }

    @Test
    fun everyDomainMissingFromEitherSectionIsNamed() {
        val missing = ManifestRules.SECTIONS.flatMap { s -> ManifestRules.DOMAINS.map { s to it } }.shuffled(r).take(r.nextInt(1, 6)).toSet()
        write(Fixtures.manifest(appId), Fixtures.manifest("$appId.debug"), emptyList(), Fixtures.extractionRules(missing))
        val expected = ManifestRules.SECTIONS.flatMap { s -> ManifestRules.DOMAINS.filter { (s to it) in missing }.map { "MANIFEST_EXTRACTION_RULES $s $it (release, debug)" } }
        assertEquals(expected, check())
    }

    @Test
    fun aMissingExportIsAFailNotAPass() {
        write(Fixtures.manifest(appId), null, emptyList())
        assertEquals(listOf("MANIFEST_NO_EXPORT merged-manifest-debug.xml (debug)"), check())
    }
}
