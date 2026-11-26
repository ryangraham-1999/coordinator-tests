package com.streamlinemyjob.inspect.tools

import java.io.File
import java.io.PrintStream
import java.util.Properties

/**
 * `tools publish`: makes a GitHub Release from the signed APK, on the main PC only
 * (designs/stage-1-foundation.md 4.18). Each refusal prints one line, exits 1 and creates nothing.
 * Programs are run through [proc] so tests pass fakes; the key's password goes to keytool only
 * through its environment (never printed).
 */
class Publish(
    private val root: File,
    private val home: File,
    private val place: Place,
    private val proc: Proc,
    private val out: PrintStream,
    private val gh: String = "gh",
    private val keytool: String = MakeKey.defaultKeytool().absolutePath,
    /** The SDK's `apksigner`; null means the newest one under `$ANDROID_HOME/build-tools`. */
    private val apksigner: String? = null,
    private val keytoolRunner: (List<String>, String) -> ProcResult = { cmd, pass -> keytoolWithPassword(cmd, pass) },
) {
    fun run(): Int {
        if (place == Place.CLOUD) return refuse("PUBLISH_CLOUD", "publish runs on the main PC only")

        val head = git("rev-parse", "HEAD")
        val upstream = git("rev-parse", "@{u}")
        val dirty = proc.run(listOf("git", "status", "--porcelain"), root)
        if (head == null || upstream == null || dirty.exit != 0 || dirty.out.isNotBlank() || head != upstream) {
            return refuse("PUBLISH_NOT_PUSHED", "commit and push first: the working tree must be clean and HEAD equal to its upstream")
        }

        val pass = readLastPass()
        if (pass == null || pass["sha"] != head || pass["place"] != Place.PC.label || !Regex("""\brelease:SIGNED\b""").containsMatchIn(pass["summary"].orEmpty())) {
            return refuse("PUBLISH_NO_PASS", "run the full scripts/check on the PC first (it must pass for this commit with release:SIGNED)")
        }

        if (!ghSignedIn()) return refuse("PUBLISH_GH", "the GitHub CLI `gh` is not installed or not signed in")

        val apk = File(root, APK_PATH)
        val keyFingerprint = keyFingerprint()
        val apkFingerprint = if (apk.isFile) apkFingerprint(apk) else null
        if (keyFingerprint == null || apkFingerprint == null || norm(keyFingerprint) != norm(apkFingerprint)) {
            return refuse("PUBLISH_CERT", "the release APK's certificate is not the release key's (or one of them could not be read)")
        }

        val versionName = versionName() ?: return refuse("PUBLISH_CERT", "versionName not found in app/build.gradle.kts")
        val branch = git("rev-parse", "--abbrev-ref", "HEAD") ?: "detached"
        val short = head.take(7)
        val tag = "v$versionName-$branch-$short"
        val exists = try { proc.run(listOf(gh, "release", "view", tag), root).exit == 0 } catch (e: Exception) { false }
        if (exists) return refuse("PUBLISH_EXISTS", "release $tag already exists; it is never edited or deleted")

        val copy = File(root, "build/publish/coordinator-$versionName-$short.apk")
        copy.parentFile.mkdirs()
        apk.copyTo(copy, overwrite = true)
        val created = try {
            proc.run(
                listOf(
                    gh, "release", "create", tag, copy.path, "--target", head, "--prerelease",
                    "--title", "Coordinator $versionName ($branch, $short)", "--notes", pass["summary"].orEmpty(),
                ),
                root,
            )
        } catch (e: Exception) {
            null
        }
        if (created == null || created.exit != 0) return refuse("PUBLISH_GH", "gh could not create the release; nothing was published")
        out.println(created.out.trim().lineSequence().lastOrNull().orEmpty())
        out.println(keyFingerprint)
        return 0
    }

    private fun refuse(code: String, why: String): Int {
        out.println("$code $why")
        return 1
    }

    private fun git(vararg args: String): String? = try {
        proc.run(listOf("git") + args, root).takeIf { it.exit == 0 }?.out?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun readLastPass(): Map<String, String>? {
        val f = File(root, "build/check/last-pass.txt")
        if (!f.isFile) return null
        return f.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
    }

    private fun ghSignedIn(): Boolean = try { proc.run(listOf(gh, "auth", "status"), root).exit == 0 } catch (e: Exception) { false }

    private fun keyFingerprint(): String? = try {
        val props = MakeKey.propertiesFile(home)
        val jks = MakeKey.keystoreFile(home)
        if (!props.isFile || !jks.isFile) null else {
            val p = Properties().apply { props.reader().use { load(it) } }
            val pass = p.getProperty("storePassword")
            if (pass.isNullOrEmpty()) null else {
                val r = keytoolRunner(
                    listOf(keytool, "-list", "-v", "-keystore", jks.absolutePath, "-storetype", "PKCS12", "-alias", p.getProperty("keyAlias") ?: MakeKey.ALIAS, "-storepass:env", PASS_ENV),
                    pass,
                )
                if (r.exit == 0) MakeKey.sha256Fingerprint(r.out) else null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun apkFingerprint(apk: File): String? = try {
        val tool = apksigner ?: newestApksigner() ?: return null
        val r = proc.run(listOf(tool, "verify", "--print-certs", apk.path), root)
        if (r.exit != 0) null else Regex("""certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)""").find(r.out)?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }

    private fun newestApksigner(): String? {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        val dir = File(sdk, "build-tools").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return null
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        return File(dir, if (windows) "apksigner.bat" else "apksigner").takeIf { it.isFile }?.path
    }

    private fun versionName(): String? =
        Regex("""versionName\s*=\s*"([^"]+)"""").find(File(root, "app/build.gradle.kts").takeIf { it.isFile }?.readText().orEmpty())?.groupValues?.get(1)

    private fun norm(fp: String) = fp.replace(":", "").lowercase()

    companion object {
        const val APK_PATH = "app/build/outputs/apk/release/app-release.apk"
        private const val PASS_ENV = "SMJ_PUBLISH_STOREPASS"

        private fun keytoolWithPassword(cmd: List<String>, password: String): ProcResult {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()[PASS_ENV] = password
            val p = pb.start()
            p.outputStream.close()
            val text = p.inputStream.bufferedReader().readText()
            return ProcResult(p.waitFor(), text, "")
        }
    }
}
