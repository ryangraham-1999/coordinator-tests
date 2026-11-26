package com.streamlinemyjob.inspect.tools

import java.io.File
import java.util.Properties

/**
 * The env phase (designs/stage-1-foundation.md 4.5): JDK, Android SDK and its platform and
 * build-tools, git, the `origin` remote, and the release key rule for the place (4.2).
 * Everything it reads from outside is passed in, so tests can use a temporary home and SDK.
 */
class EnvCheck(
    private val root: File,
    private val place: Place,
    private val env: Map<String, String>,
    private val home: File,
    private val javaHome: File,
    private val javaVersion: String,
    private val proc: Proc,
) {
    /** Lines for `build/check/env.log`: what was found (4.1: the env phase prints the JDK version). */
    val found = mutableListOf<String>()

    fun run(): List<String> {
        val problems = mutableListOf<String>()
        problems += jdk()
        problems += sdk()
        problems += git()
        problems += key()
        return problems
    }

    private fun jdk(): List<String> {
        val javac = File(javaHome, "bin/" + if (isWindows()) "javac.exe" else "javac")
        if (!javac.isFile) return listOf("ENV_JDK no javac in ${javaHome.path} (a JRE, not a JDK)")
        found += "jdk=$javaVersion at ${javaHome.path}"
        return emptyList()
    }

    /** `ANDROID_HOME`, else `ANDROID_SDK_ROOT`, else `sdk.dir` in `local.properties`. */
    fun sdkDir(): File? {
        env["ANDROID_HOME"]?.takeIf { it.isNotBlank() }?.let { return File(it) }
        env["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val local = File(root, "local.properties")
        if (local.isFile) {
            val p = Properties().apply { local.inputStream().use { load(it) } }
            p.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        }
        return null
    }

    private fun sdk(): List<String> {
        val sdk = sdkDir() ?: return listOf("ENV_SDK no ANDROID_HOME, ANDROID_SDK_ROOT or sdk.dir in local.properties")
        if (!sdk.isDirectory) return listOf("ENV_SDK ${sdk.path} is not a folder")
        found += "sdk=${sdk.path}"
        val problems = mutableListOf<String>()
        val level = CompileSdk.read(File(root, "app/build.gradle.kts"))
        if (level == null) {
            problems += "ENV_SDK_PLATFORM unknown (no compileSdk found in app/build.gradle.kts)"
        } else {
            val platform = File(sdk, "platforms/android-$level")
            if (!platform.isDirectory) problems += "ENV_SDK_PLATFORM $level" else found += "platform=${platform.path}"
        }
        val buildTools = File(sdk, "build-tools").listFiles()?.filter { it.isDirectory }.orEmpty()
        if (buildTools.isEmpty()) problems += "ENV_SDK no build-tools folder in ${sdk.path}" else found += "build-tools=${buildTools.joinToString(",") { it.name }}"
        return problems
    }

    private fun git(): List<String> {
        val version = try { proc.run(listOf("git", "--version"), root) } catch (e: Exception) { null }
        if (version == null || version.exit != 0) return listOf("ENV_GIT git not found on PATH")
        found += version.out.trim()
        val url = try { proc.run(listOf("git", "remote", "get-url", "origin"), root) } catch (e: Exception) { null }
        if (url == null || url.exit != 0) return listOf("ENV_REMOTE no origin remote")
        val host = GitHub.host(url.out.trim())
        if (host != "github.com") return listOf("ENV_REMOTE origin is not a github.com remote (${host ?: url.out.trim()})")
        found += "origin=github.com"
        return emptyList()
    }

    private fun key(): List<String> = when (place) {
        Place.PC -> {
            val props = KeyLocations.propertiesFile(env, home)
            if (!props.isFile) listOf("ENV_KEY ${props.path} not found; run scripts/make-key once, on the main PC")
            else {
                val storeFile = Properties().apply { props.inputStream().use { load(it) } }.getProperty("storeFile")
                if (storeFile.isNullOrBlank() || !File(storeFile).isFile) listOf("ENV_KEY keystore named in ${props.path} not found")
                else { found += "key=${props.path}"; emptyList() }
            }
        }
        Place.CLOUD -> KeyLocations.all(env, home).filter { it.exists() }.map { "ENV_KEY_IN_CLOUD ${it.path}" }
    }
}

/** Where 4.2 looks for the release key. */
object KeyLocations {
    fun propertiesFile(env: Map<String, String>, home: File): File =
        env["SMJ_KEYSTORE_PROPERTIES"]?.takeIf { it.isNotBlank() }?.let { File(it) } ?: MakeKey.propertiesFile(home)

    /** Every place 4.2 looks: the override, `<home>/.smj/keystore.properties` and `<home>/.smj/release.jks`. */
    fun all(env: Map<String, String>, home: File): List<File> = listOfNotNull(
        env["SMJ_KEYSTORE_PROPERTIES"]?.takeIf { it.isNotBlank() }?.let { File(it) },
        MakeKey.propertiesFile(home),
        MakeKey.keystoreFile(home),
    ).distinctBy { it.absolutePath }
}

/** The app's compileSdk, read from `app/build.gradle.kts`: `compileSdk = N` or `release(N) { minorApiLevel = M }`. */
object CompileSdk {
    fun read(buildFile: File): String? {
        if (!buildFile.isFile) return null
        val text = buildFile.readText()
        Regex("""compileSdk\s*=\s*(\d+)""").find(text)?.let { return it.groupValues[1] }
        val block = Regex("""compileSdk\s*\{(.*?)\n\s*}""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
        val major = Regex("""release\(\s*(\d+)\s*\)""").find(block)?.groupValues?.get(1) ?: return null
        val minor = Regex("""minorApiLevel\s*=\s*(\d+)""").find(block)?.groupValues?.get(1)
        return if (minor == null || minor == "0") major else "$major.$minor"
    }
}

/** Reads owner and repo from a GitHub remote URL. */
object GitHub {
    private val patterns = listOf(
        Regex("""^https?://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?/?$"""),
        Regex("""^ssh://(?:[^@/]+@)?([^/:]+)(?::\d+)?/([^/]+)/([^/]+?)(?:\.git)?/?$"""),
        Regex("""^(?:[^@/]+@)?([^/:]+):([^/]+)/([^/]+?)(?:\.git)?/?$"""),
    )

    fun parse(url: String): Triple<String, String, String>? =
        patterns.firstNotNullOfOrNull { it.matchEntire(url.trim()) }?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    fun host(url: String): String? = parse(url)?.first?.lowercase()
}

fun isWindows() = System.getProperty("os.name").startsWith("Windows")
