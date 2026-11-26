package com.streamlinemyjob.inspect.tools

import java.io.File
import java.io.PrintStream
import java.security.SecureRandom

/**
 * `tools make-key`: makes the release key, once, on the main PC (designs/stage-1-foundation.md 4.2).
 *
 * Writes `<home>/.smj/release.jks` (PKCS12, alias `smj`, RSA 4096) and `<home>/.smj/keystore.properties`.
 * PKCS12 keeps one password for the store and the key (keytool ignores a separate key password for
 * PKCS12), so the one generated password is written as both storePassword and keyPassword
 * (owner's decision at S1.4, 2026-11-23). Passwords go to keytool only through its environment,
 * and are never printed.
 */
object MakeKey {
    const val CLOUD_REFUSAL =
        "make-key: the release key is made on the main PC only (designs/stage-1-foundation.md 4.2); nothing changed"
    const val ALIAS = "smj"
    const val PASSWORD_LENGTH = 32
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val STORE_PASS_ENV = "SMJ_MAKE_KEY_STOREPASS"

    fun smjDir(home: File) = File(home, ".smj")
    fun keystoreFile(home: File) = File(smjDir(home), "release.jks")
    fun propertiesFile(home: File) = File(smjDir(home), "keystore.properties")

    fun defaultKeytool(): File = File(System.getProperty("java.home"), "bin/keytool" + if (isWindows()) ".exe" else "")

    /** Returns the exit code: 0 made, 1 refused or failed (nothing kept). */
    fun run(
        home: File,
        place: Place,
        out: PrintStream,
        keytool: File = defaultKeytool(),
        random: SecureRandom = SecureRandom(),
    ): Int {
        if (place == Place.CLOUD) {
            out.println(CLOUD_REFUSAL)
            return 1
        }
        val jks = keystoreFile(home)
        val props = propertiesFile(home)
        for (f in listOf(jks, props)) {
            if (f.exists()) {
                out.println("make-key: a key already exists at ${f.absolutePath}; nothing changed")
                return 1
            }
        }
        val dir = smjDir(home)
        val dirExisted = dir.exists()
        if (!dir.mkdirs() && !dir.isDirectory) {
            out.println("make-key: could not create ${dir.absolutePath}; nothing changed")
            return 1
        }
        val password = newPassword(random)
        try {
            val gen = runKeytool(
                keytool, password,
                "-genkeypair", "-keystore", jks.absolutePath, "-storetype", "PKCS12",
                "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "4096", "-validity", "36500",
                "-dname", "CN=Coordinator",
                "-storepass:env", STORE_PASS_ENV, "-keypass:env", STORE_PASS_ENV,
            )
            if (gen.exitCode != 0 || !jks.isFile) {
                undo(jks, props, dir, dirExisted)
                out.println("make-key: keytool could not make the key (exit ${gen.exitCode}); nothing changed")
                return 1
            }
            val list = runKeytool(
                keytool, password,
                "-list", "-v", "-keystore", jks.absolutePath, "-storetype", "PKCS12",
                "-alias", ALIAS, "-storepass:env", STORE_PASS_ENV,
            )
            val fingerprint = sha256Fingerprint(list.output)
            if (list.exitCode != 0 || fingerprint == null) {
                undo(jks, props, dir, dirExisted)
                out.println("make-key: keytool could not read the new key back (exit ${list.exitCode}); nothing changed")
                return 1
            }
            props.writeText(
                "storeFile=${escape(jks.absolutePath)}\n" +
                    "storePassword=$password\n" +
                    "keyAlias=$ALIAS\n" +
                    "keyPassword=$password\n",
            )
            out.println("Keystore:   ${jks.absolutePath}")
            out.println("Properties: ${props.absolutePath}")
            out.println("SHA-256: $fingerprint")
            out.println("Back up the folder ${dir.absolutePath} now. Without it the app cannot be updated without losing its data.")
            return 0
        } catch (e: Exception) {
            undo(jks, props, dir, dirExisted)
            out.println("make-key: ${e.javaClass.simpleName}; nothing changed")
            return 1
        }
    }

    fun newPassword(random: SecureRandom): String =
        buildString(PASSWORD_LENGTH) { repeat(PASSWORD_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** The `SHA256:` line of `keytool -list -v`, as `3A:…`. */
    fun sha256Fingerprint(keytoolListOutput: String): String? =
        Regex("""SHA-?256:\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})""").find(keytoolListOutput)?.groupValues?.get(1)

    private class Result(val exitCode: Int, val output: String)

    private fun runKeytool(keytool: File, password: String, vararg args: String): Result {
        val pb = ProcessBuilder(listOf(keytool.absolutePath) + args).redirectErrorStream(true)
        pb.environment()[STORE_PASS_ENV] = password
        val p = pb.start()
        p.outputStream.close()
        val output = p.inputStream.bufferedReader().readText()
        return Result(p.waitFor(), output)
    }

    private fun undo(jks: File, props: File, dir: File, dirExisted: Boolean) {
        props.delete()
        jks.delete()
        if (!dirExisted) dir.delete()
    }

    /** java.util.Properties treats `\` as an escape; Windows paths need it doubled. */
    private fun escape(path: String) = path.replace("\\", "\\\\")

    private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows")
}
