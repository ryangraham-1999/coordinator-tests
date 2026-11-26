package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** `tools publish` (designs/stage-1-foundation.md 4.18, 6): one test per PUBLISH_* code, each asserting no release was created. */
class PublishTest {
    private val root = Fixtures.tempDir("smj-publish-root")
    private val home = Fixtures.tempDir("smj-publish-home")
    private val r = Fixtures.random(PublishTest::class.qualifiedName!!)
    private val sha = List(40) { "0123456789abcdef"[r.nextInt(16)] }.joinToString("")
    private val version = "${r.nextInt(0, 9)}.${r.nextInt(0, 20)}.${r.nextInt(0, 20)}"
    private val branch = "stage-${r.nextInt(2, 90)}"
    private val fp = List(32) { "%02X".format(r.nextInt(256)) }.joinToString(":")
    private val commands = mutableListOf<List<String>>()

    // Switches the fakes read.
    private var dirty = ""
    private var upstream = sha
    private var ghAuthExit = 0
    private var ghInstalled = true
    private var releaseExists = false
    private var apkDigest = fp.replace(":", "").lowercase()
    private var createExit = 0

    @After fun clean() { root.deleteRecursively(); home.deleteRecursively() }

    private fun proc() = Proc { cmd, _ ->
        commands += cmd
        when {
            cmd[0] == "git" && cmd[1] == "status" -> ProcResult(0, dirty, "")
            cmd[0] == "git" && cmd.getOrNull(2) == "HEAD" && cmd[1] == "rev-parse" && cmd.size == 3 -> ProcResult(0, "$sha\n", "")
            cmd[0] == "git" && cmd[2] == "@{u}" -> ProcResult(0, "$upstream\n", "")
            cmd[0] == "git" && cmd[2] == "--abbrev-ref" -> ProcResult(0, "$branch\n", "")
            cmd[0] == "git" -> ProcResult(0, "$sha\n", "")
            cmd[0] == "fake-gh" -> {
                if (!ghInstalled) throw java.io.IOException("not found")
                when (cmd[1]) {
                    "auth" -> ProcResult(ghAuthExit, "", "")
                    "release" -> when (cmd[2]) {
                        "view" -> ProcResult(if (releaseExists) 0 else 1, "", "")
                        "create" -> ProcResult(createExit, "https://github.com/o/r/releases/tag/${cmd[3]}\n", "")
                        else -> ProcResult(2, "", "")
                    }
                    else -> ProcResult(2, "", "")
                }
            }
            cmd[0] == "fake-apksigner" -> ProcResult(0, "Signer #1 certificate DN: CN=Coordinator\nSigner #1 certificate SHA-256 digest: $apkDigest\n", "")
            else -> ProcResult(2, "", "unexpected $cmd")
        }
    }

    private fun setUp(summary: String = "CHECK PASS place=pc release:SIGNED time=1m") {
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText("android { defaultConfig { versionName = \"$version\" } }\n")
        File(root, PUBLISH_APK).apply { parentFile.mkdirs(); writeText("apk") }
        File(root, "build/check").mkdirs()
        File(root, "build/check/last-pass.txt").writeText("sha=$sha\nplace=pc\nsummary=$summary\n")
        File(home, ".smj").mkdirs()
        File(home, ".smj/release.jks").writeText("jks")
        File(home, ".smj/keystore.properties").writeText("storeFile=x\nstorePassword=secretpw${r.nextInt(1000)}\nkeyAlias=smj\nkeyPassword=same\n")
    }

    private val passwordsSeen = mutableListOf<String>()

    private fun publish(place: Place = Place.PC): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val code = Publish(
            root, home, place, proc(), PrintStream(buf, true), gh = "fake-gh", keytool = "fake-keytool", apksigner = "fake-apksigner",
            keytoolRunner = { cmd, pass ->
                commands += cmd; passwordsSeen += pass
                ProcResult(0, "Certificate fingerprints:\n\t SHA256: $fp\n", "")
            },
        ).run()
        return code to buf.toString().trim()
    }

    private fun createdARelease() = commands.any { it.size > 2 && it[0] == "fake-gh" && it[1] == "release" && it[2] == "create" }

    private fun assertRefused(code: String, result: Pair<Int, String>) {
        assertEquals(1, result.first)
        assertTrue(result.second, result.second.startsWith("$code "))
        assertEquals("one line", 1, result.second.lines().size)
        assertFalse("the fake gh was asked to create a release", createdARelease())
        assertFalse(File(root, "build/publish").exists())
    }

    @Test fun aGoodRunMakesTheReleaseAndPrintsItsUrlAndTheFingerprint() {
        setUp()
        val (code, text) = publish()
        assertEquals(text, 0, code)
        val tag = "v$version-$branch-${sha.take(7)}"
        assertEquals(listOf("https://github.com/o/r/releases/tag/$tag", fp), text.lines())
        val create = commands.single { it.getOrNull(2) == "create" }
        assertEquals(File(root, "build/publish/coordinator-$version-${sha.take(7)}.apk").path, create[4])
        assertTrue(create.containsAll(listOf("--target", sha, "--prerelease", "--title", "Coordinator $version ($branch, ${sha.take(7)})", "--notes", "CHECK PASS place=pc release:SIGNED time=1m")))
        assertTrue(File(create[4]).isFile)
        // The password reaches keytool only through the environment, never the output or the arguments.
        assertTrue(passwordsSeen.single().startsWith("secretpw"))
        assertFalse(text.contains(passwordsSeen.single()))
        assertFalse(commands.any { c -> c.any { it.contains(passwordsSeen.single()) } })
    }

    @Test fun cloudIsPublishCloud() { setUp(); assertRefused("PUBLISH_CLOUD", publish(Place.CLOUD)) }

    @Test fun aDirtyTreeIsNotPushed() { setUp(); dirty = " M README.md\n"; assertRefused("PUBLISH_NOT_PUSHED", publish()) }

    @Test fun aHeadAheadOfItsUpstreamIsNotPushed() { setUp(); upstream = "f".repeat(40); assertRefused("PUBLISH_NOT_PUSHED", publish()) }

    @Test fun noLastPassIsNoPass() { setUp(); File(root, "build/check/last-pass.txt").delete(); assertRefused("PUBLISH_NO_PASS", publish()) }

    @Test fun aPassForAnotherCommitIsNoPass() {
        setUp(); File(root, "build/check/last-pass.txt").writeText("sha=${"e".repeat(40)}\nplace=pc\nsummary=CHECK PASS place=pc release:SIGNED\n")
        assertRefused("PUBLISH_NO_PASS", publish())
    }

    @Test fun aCloudPassIsNoPass() {
        setUp(); File(root, "build/check/last-pass.txt").writeText("sha=$sha\nplace=cloud\nsummary=CHECK PASS place=cloud release:CLOUD\n")
        assertRefused("PUBLISH_NO_PASS", publish())
    }

    @Test fun aPcPassWithoutSignedIsNoPass() { setUp("CHECK PASS place=pc release:FAIL time=1m"); assertRefused("PUBLISH_NO_PASS", publish()) }

    @Test fun ghNotSignedInIsPublishGh() { setUp(); ghAuthExit = 1; assertRefused("PUBLISH_GH", publish()) }

    @Test fun ghNotInstalledIsPublishGh() { setUp(); ghInstalled = false; assertRefused("PUBLISH_GH", publish()) }

    @Test fun aDifferentCertificateIsPublishCert() { setUp(); apkDigest = "ab".repeat(32); assertRefused("PUBLISH_CERT", publish()) }

    @Test fun aMissingApkIsPublishCert() { setUp(); File(root, PUBLISH_APK).delete(); assertRefused("PUBLISH_CERT", publish()) }

    @Test fun anExistingReleaseIsPublishExists() { setUp(); releaseExists = true; assertRefused("PUBLISH_EXISTS", publish()) }

    @Test fun aFailedReleaseCreateIsPublishGhAndPrintsNoUrl() {
        setUp(); createExit = 1
        val (code, text) = publish()
        assertEquals(1, code)
        assertTrue(text, text.startsWith("PUBLISH_GH "))
    }

    private companion object { const val PUBLISH_APK = Publish.APK_PATH }
}
