package com.streamlinemyjob.inspect.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Place rules and the env phase (designs/stage-1-foundation.md 4.2, 4.5, 6), in a temporary home,
 * SDK and JDK folder. Covers every ENV_* code.
 */
class EnvCheckTest {
    private val dir = Fixtures.tempDir("smj-env-test")
    private val root = File(dir, "repo").apply { mkdirs() }
    private val home = File(dir, "home").apply { mkdirs() }
    private val sdk = File(dir, "sdk")
    private val jdk = File(dir, "jdk").apply { File(this, "bin").mkdirs(); File(this, "bin/javac").writeText(""); File(this, "bin/javac.exe").writeText("") }
    private val r = Fixtures.random(EnvCheckTest::class.qualifiedName!!)
    private val major = r.nextInt(30, 60)
    private val minor = r.nextInt(1, 5)

    init {
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText("android {\n    compileSdk {\n        version = release($major) {\n            minorApiLevel = $minor\n        }\n    }\n}\n")
        File(sdk, "platforms/android-$major.$minor").mkdirs()
        File(sdk, "build-tools/${r.nextInt(30, 40)}.0.0").mkdirs()
    }

    @After fun clean() { dir.deleteRecursively() }

    private var origin = "https://github.com/someone/somerepo.git"
    private var gitWorks = true

    private val proc = Proc { cmd, _ ->
        when {
            !gitWorks -> throw java.io.IOException("Cannot run program \"git\"")
            cmd == listOf("git", "--version") -> ProcResult(0, "git version 2.0.0\n", "")
            cmd == listOf("git", "remote", "get-url", "origin") -> if (origin.isEmpty()) ProcResult(2, "", "error: No such remote 'origin'") else ProcResult(0, "$origin\n", "")
            else -> error("unexpected $cmd")
        }
    }

    private fun env(place: Place, vars: Map<String, String> = mapOf("ANDROID_HOME" to sdk.path), javaHome: File = jdk) =
        EnvCheck(root, place, vars, home, javaHome, "21.0.99", proc).run()

    private fun makeKey() {
        val smj = File(home, ".smj").apply { mkdirs() }
        File(smj, "release.jks").writeText("not a real key")
        File(smj, "keystore.properties").writeText("storeFile=${File(smj, "release.jks").absolutePath.replace("\\", "\\\\")}\n")
    }

    @Test
    fun placeIsCloudOnlyWhenSmjPlaceIsExactlyCloud() {
        assertEquals(Place.CLOUD, Place.from("cloud"))
        for (other in listOf(null, "", "Cloud", "CLOUD", " cloud", "cloud ", "pc", "true", "1")) assertEquals("'$other'", Place.PC, Place.from(other))
    }

    @Test
    fun cloudWithNoKeyAndPcWithAKeyPass() {
        assertEquals(emptyList<String>(), env(Place.CLOUD))
        makeKey()
        assertEquals(emptyList<String>(), env(Place.PC))
    }

    @Test
    fun aKeyInTheCloudIsEnvKeyInCloudForEachPlace4_2Looks() {
        makeKey()
        val smj = File(home, ".smj")
        assertEquals(
            listOf("ENV_KEY_IN_CLOUD ${File(smj, "keystore.properties").path}", "ENV_KEY_IN_CLOUD ${File(smj, "release.jks").path}"),
            env(Place.CLOUD),
        )
        smj.deleteRecursively()
        val elsewhere = File(dir, "elsewhere.properties").apply { writeText("storeFile=x\n") }
        assertEquals(listOf("ENV_KEY_IN_CLOUD ${elsewhere.path}"), env(Place.CLOUD, mapOf("ANDROID_HOME" to sdk.path, "SMJ_KEYSTORE_PROPERTIES" to elsewhere.path)))
        // Only the release.jks left behind still counts.
        File(smj, "release.jks").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(listOf("ENV_KEY_IN_CLOUD ${File(smj, "release.jks").path}"), env(Place.CLOUD))
    }

    @Test
    fun pcWithNoKeyIsEnvKey() {
        val got = env(Place.PC)
        assertEquals(1, got.size)
        assertTrue(got[0], got[0].startsWith("ENV_KEY ${File(home, ".smj/keystore.properties").path}"))
        // A properties file whose keystore is gone is not a key either.
        File(home, ".smj").mkdirs(); File(home, ".smj/keystore.properties").writeText("storeFile=${File(dir, "gone.jks").path.replace("\\", "\\\\")}\n")
        assertTrue(env(Place.PC).single().startsWith("ENV_KEY keystore named in"))
    }

    @Test
    fun noJavacIsEnvJdk() {
        val jre = File(dir, "jre").apply { File(this, "bin").mkdirs() }
        assertEquals(listOf("ENV_JDK no javac in ${jre.path} (a JRE, not a JDK)"), env(Place.CLOUD, javaHome = jre))
    }

    @Test
    fun sdkFromEachSourceAndEnvSdkWhenNone() {
        assertEquals(emptyList<String>(), env(Place.CLOUD, mapOf("ANDROID_SDK_ROOT" to sdk.path)))
        assertEquals(listOf("ENV_SDK no ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties"), env(Place.CLOUD, emptyMap()))
        File(root, "local.properties").writeText("sdk.dir=${sdk.path.replace("\\", "\\\\")}\n")
        assertEquals(emptyList<String>(), env(Place.CLOUD, emptyMap()))
        assertEquals(listOf("ENV_SDK ${File(dir, "nope").path} is not a folder"), env(Place.CLOUD, mapOf("ANDROID_HOME" to File(dir, "nope").path)))
        File(sdk, "build-tools").deleteRecursively()
        assertEquals(listOf("ENV_SDK no build-tools folder in ${sdk.path}"), env(Place.CLOUD))
    }

    @Test
    fun missingPlatformIsEnvSdkPlatformWithTheLevel() {
        File(sdk, "platforms/android-$major.$minor").deleteRecursively()
        File(sdk, "platforms/android-$major").mkdirs() // the major level alone is not the compileSdk
        assertEquals(listOf("ENV_SDK_PLATFORM $major.$minor"), env(Place.CLOUD))
    }

    @Test
    fun compileSdkIsReadInBothForms() {
        val f = File(dir, "b.gradle.kts")
        f.writeText("android {\n    compileSdk = $major\n}\n"); assertEquals("$major", CompileSdk.read(f))
        f.writeText("android {\n    compileSdk {\n        version = release($major)\n    }\n}\n"); assertEquals("$major", CompileSdk.read(f))
        f.writeText("android {\n}\n"); assertEquals(null, CompileSdk.read(f))
    }

    @Test
    fun noGitIsEnvGit() {
        gitWorks = false
        assertEquals(listOf("ENV_GIT git not found on PATH"), env(Place.CLOUD))
    }

    @Test
    fun originMustBeAGithubComRemote() {
        for (ok in listOf("https://github.com/a/b", "git@github.com:a/b.git", "ssh://git@github.com/a/b.git")) {
            origin = ok; assertEquals(ok, emptyList<String>(), env(Place.CLOUD))
        }
        origin = "https://gitlab.com/a/b.git"
        assertEquals(listOf("ENV_REMOTE origin is not a github.com remote (gitlab.com)"), env(Place.CLOUD))
        origin = "https://github.com.evil.example/a/b.git"
        assertEquals(listOf("ENV_REMOTE origin is not a github.com remote (github.com.evil.example)"), env(Place.CLOUD))
        origin = ""
        assertEquals(listOf("ENV_REMOTE no origin remote"), env(Place.CLOUD))
    }
}
