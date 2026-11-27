package com.streamlinemyjob.inspect.photo

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [PhotoFolder] over a plain directory (designs/stage-2-camera.md 3, 4.12), for the Robolectric tests; test code only, never
 * shipped. It must pass the same `PhotoFolderContract` as the folder the phone uses. Files are listed from the directory itself
 * and from its subfolders (one level), never deeper.
 *
 * Names on disk: a name may hold any character (accents, a non-BMP character), but the machine running the tests may not be able to
 * name such a file (a POSIX locale, a Windows code page). So every character outside `A-Z a-z 0-9 space . _ - ( ) ' & , + =` is
 * written as `%XX` per UTF-8 byte (`%` itself too) and decoded again by [list]; the path the caller sees is always the one it wrote.
 */
class DirPhotoFolder(private val dir: File) : PhotoFolder {
    override fun isReady(): Boolean = dir.isDirectory && dir.canWrite()

    override fun write(relPath: String, bytes: ByteArray) {
        val target = file(relPath)
        if (target.exists()) throw FileAlreadyExistsException(relPath)
        target.parentFile?.mkdirs()
        val part = File(target.path + PhotoFolder.PART)
        try {
            FileOutputStream(part).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            // ATOMIC_MOVE without REPLACE_EXISTING: fails rather than overwrite a file made in the meantime.
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            part.delete()
            throw if (e is IOException) e else IOException(e)
        }
    }

    override fun delete(relPath: String) {
        val f = file(relPath)
        if (f.isFile && !f.delete()) throw IOException("could not delete $relPath")
    }

    override fun exists(relPath: String): Boolean = file(relPath).isFile

    override fun length(relPath: String): Long {
        val f = file(relPath)
        if (!f.isFile) throw IOException("no such file: $relPath")
        return f.length()
    }

    override fun open(relPath: String): InputStream {
        val f = file(relPath)
        if (!f.isFile) throw IOException("no such file: $relPath")
        return f.inputStream()
    }

    override fun list(): List<PhotoFolder.Entry> = allFiles()
        .filter { it.name != PhotoFolder.NOMEDIA && it.name != PhotoFolder.WRITE_TEST }
        .map { PhotoFolder.Entry(rel(it), it.length()) }
        .sortedBy { it.relPath }

    override fun sweepParts(): Int {
        var n = 0
        for (f in allFiles()) if (f.name.endsWith(PhotoFolder.PART) && f.delete()) n++
        return n
    }

    override fun freeBytes(): Long = dir.usableSpace

    private fun file(relPath: String): File = File(dir, relPath.split('/').joinToString(File.separator) { encode(it) })

    private fun rel(f: File): String = f.relativeTo(dir).path.split(File.separatorChar).joinToString("/") { decode(it) }

    private fun encode(segment: String): String {
        val sb = StringBuilder()
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in " ._-()'&,+=") sb.append(c) else sb.append("%%%02X".format(b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun decode(name: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < name.length) {
            if (name[i] == '%') {
                out.write(name.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(name[i].code)
                i++
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun allFiles(): List<File> {
        val top = dir.listFiles().orEmpty()
        return top.filter { it.isFile } + top.filter { it.isDirectory }.flatMap { d -> d.listFiles().orEmpty().filter { it.isFile } }
    }
}
