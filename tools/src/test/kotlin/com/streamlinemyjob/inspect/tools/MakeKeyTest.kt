package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.util.Properties

/**
 * MakeKey against a throwaway home folder (designs/stage-1-foundation.md 4.2, 6). Every key made
 * here is test data in a temporary folder that is deleted after each test; no real key is made.
 */
class MakeKeyTest {
    private val home: File = Files.createTempDirectory("smj-make-key-test").toFile()

    @After
    fun deleteHome() {
        home.deleteRecursively()
    }

    private class Run(val code: Int, val output: String)

    private fun makeKey(place: Place = Place.PC, home: File = this.home): Run {
        val buffer = ByteArrayOutputStream()
        val code = PrintStream(buffer, true, "UTF-8").use { MakeKey.run(home, place, it) }
        return Run(code, buffer.toString("UTF-8"))
    }

    private fun props(): Properties =
        Properties().apply { MakeKey.propertiesFile(home).inputStream().use { load(it) } }

    private fun snapshot(dir: File): Map<String, Pair<Long, List<Byte>>> =
        dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to (it.lastModified() to it.readBytes().toList())
        }

    @Test
    fun makesBothFilesWithFourLinesAndOnePasswordFromTheAlphabet() {
        val run = makeKey()
        assertEquals(0, run.code)
        assertTrue(MakeKey.keystoreFile(home).isFile)
        val propsFile = MakeKey.propertiesFile(home)
        assertTrue(propsFile.isFile)

        val lines = propsFile.readLines().filter { it.isNotBlank() }
        assertEquals(4, lines.size)
        assertEquals(
            listOf("storeFile", "storePassword", "keyAlias", "keyPassword"),
            lines.map { it.substringBefore('=') },
        )
        val p = props()
        assertEquals(MakeKey.keystoreFile(home).absolutePath, p.getProperty("storeFile"))
        assertEquals("smj", p.getProperty("keyAlias"))
        val store = p.getProperty("storePassword")
        assertTrue(Regex("[A-Za-z0-9]{32}").matches(store))
        // PKCS12: one password for store and key (owner's decision at S1.4).
        assertEquals(store, p.getProperty("keyPassword"))
    }

    @Test
    fun printsPathsFingerprintAndBackUpLineAndNeverThePassword() {
        val run = makeKey()
        assertEquals(0, run.code)
        val out = run.output
        assertTrue(out.contains(MakeKey.keystoreFile(home).absolutePath))
        assertTrue(out.contains(MakeKey.propertiesFile(home).absolutePath))
        assertTrue(Regex("""SHA-256: [0-9A-F]{2}(:[0-9A-F]{2}){31}""").containsMatchIn(out))
        assertTrue(
            out.contains(
                "Back up the folder ${MakeKey.smjDir(home).absolutePath} now. " +
                    "Without it the app cannot be updated without losing its data.",
            ),
        )
        val p = props()
        assertFalse(out.contains(p.getProperty("storePassword")))
        assertFalse(out.contains(p.getProperty("keyPassword")))
        assertEquals(4, out.lines().count { it.isNotBlank() })
    }

    @Test
    fun keystoreOpensWithWrittenPasswordsHoldsRsa4096AndMatchesPrintedFingerprint() {
        val run = makeKey()
        assertEquals(0, run.code)
        val printed = Regex("""SHA-256: (\S+)""").find(run.output)!!.groupValues[1]
        val p = props()

        val ks = KeyStore.getInstance("PKCS12")
        File(p.getProperty("storeFile")).inputStream().use { ks.load(it, p.getProperty("storePassword").toCharArray()) }
        val key = ks.getKey(p.getProperty("keyAlias"), p.getProperty("keyPassword").toCharArray())
        assertTrue(key is RSAPrivateKey)
        assertEquals(4096, (key as RSAPrivateKey).modulus.bitLength())
        val cert = ks.getCertificate("smj")
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val computed = digest.joinToString(":") { "%02X".format(it) }
        assertEquals(computed, printed)

        // And keytool itself opens it with the written password (passed through the environment).
        val pb = ProcessBuilder(
            MakeKey.defaultKeytool().absolutePath, "-list", "-v", "-keystore", p.getProperty("storeFile"),
            "-storetype", "PKCS12", "-alias", "smj", "-storepass:env", "SMJ_TEST_PASS",
        ).redirectErrorStream(true)
        pb.environment()["SMJ_TEST_PASS"] = p.getProperty("storePassword")
        val proc = pb.start()
        val listed = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor())
        assertEquals(printed, MakeKey.sha256Fingerprint(listed))
    }

    @Test
    fun secondRunRefusesAndChangesNothing() {
        assertEquals(0, makeKey().code)
        val before = snapshot(home)
        val run = makeKey()
        assertEquals(1, run.code)
        assertEquals(
            "make-key: a key already exists at ${MakeKey.keystoreFile(home).absolutePath}; nothing changed",
            run.output.trim(),
        )
        assertEquals(before, snapshot(home))
    }

    @Test
    fun refusesWhenOnlyThePropertiesFileExists() {
        val props = MakeKey.propertiesFile(home)
        props.parentFile.mkdirs()
        props.writeText("storeFile=x\n")
        val before = snapshot(home)
        val run = makeKey()
        assertEquals(1, run.code)
        assertEquals("make-key: a key already exists at ${props.absolutePath}; nothing changed", run.output.trim())
        assertEquals(before, snapshot(home))
        assertFalse(MakeKey.keystoreFile(home).exists())
    }

    @Test
    fun refusesWhenOnlyTheKeystoreExists() {
        val jks = MakeKey.keystoreFile(home)
        jks.parentFile.mkdirs()
        jks.writeBytes(byteArrayOf(1, 2, 3))
        val before = snapshot(home)
        val run = makeKey()
        assertEquals(1, run.code)
        assertEquals(before, snapshot(home))
        assertArrayEquals(byteArrayOf(1, 2, 3), jks.readBytes())
        assertFalse(MakeKey.propertiesFile(home).exists())
    }

    @Test
    fun refusesInTheCloudAndCreatesNothing() {
        val run = makeKey(place = Place.CLOUD)
        assertEquals(1, run.code)
        assertEquals(MakeKey.CLOUD_REFUSAL, run.output.trim())
        assertFalse(MakeKey.smjDir(home).exists())
        assertEquals(0, home.listFiles()!!.size)
    }

    @Test
    fun twoKeysGetDifferentPasswords() {
        val other = Files.createTempDirectory("smj-make-key-test").toFile()
        try {
            assertEquals(0, makeKey().code)
            assertEquals(0, makeKey(home = other).code)
            val a = props().getProperty("storePassword")
            val b = Properties().apply { MakeKey.propertiesFile(other).inputStream().use { load(it) } }
                .getProperty("storePassword")
            assertNotNull(a)
            assertFalse(a == b)
        } finally {
            other.deleteRecursively()
        }
    }

    @Test
    fun passwordsAre32CharactersFromTheAlphabetAndUseAllOfIt() {
        val random = SecureRandom()
        val seen = HashSet<Char>()
        repeat(200) {
            val pw = MakeKey.newPassword(random)
            assertTrue(Regex("[A-Za-z0-9]{32}").matches(pw))
            seen += pw.toSet()
        }
        assertEquals(62, seen.size)
    }

    @Test
    fun placeIsCloudOnlyForExactlyCloud() {
        assertEquals(Place.CLOUD, Place.from("cloud"))
        for (v in listOf(null, "", "pc", "Cloud", "CLOUD", " cloud", "local")) assertEquals(Place.PC, Place.from(v))
    }
}
