package com.streamlinemyjob.inspect.photo

import java.io.InputStream

/**
 * The photo folder (designs/stage-2-camera.md 4.12): where photo files live, behind an interface so the Repository and the
 * integrity check do not care how it is reached (a picked folder through Android's folder picker in S2.5; a plain directory
 * in the Robolectric tests, `DirPhotoFolder`). Paths are relative to the folder, `<subfolder>/<file name>`, separated by `/`.
 * Every call blocks: callers are off the main thread. A call that cannot do what it says throws [java.io.IOException].
 *
 * The folder never deletes or overwrites a file it was not asked to: [write] refuses a path that is taken, [delete] removes
 * the one path it is given, and [sweepParts] removes only the app's own `.part` files (4.6, section 9).
 */
interface PhotoFolder {
    /** One file in the folder: its path and length in bytes. */
    data class Entry(val relPath: String, val length: Long)

    /** Whether photos can be written now (a folder is set, it exists and the app may write to it). */
    fun isReady(): Boolean

    /**
     * Writes [bytes] as [relPath]: creates `<relPath>.part` (made if absent, with its subfolder), writes and flushes it,
     * then renames it to [relPath]. Throws [java.nio.file.FileAlreadyExistsException] if [relPath] already exists (the
     * file is left as it was) and removes the `.part` if anything fails.
     */
    fun write(relPath: String, bytes: ByteArray)

    /** Deletes [relPath]; a file that is not there is not an error. */
    fun delete(relPath: String)

    fun exists(relPath: String): Boolean

    /** The length of [relPath] in bytes; throws if it is not there. */
    fun length(relPath: String): Long

    /** Opens [relPath] for reading; throws if it is not there. */
    fun open(relPath: String): InputStream

    /**
     * Every file in the folder: those in the folder itself and those in its reg subfolders (one level down), sorted by
     * path. `.nomedia` and `.coordinator-test` are left out (4.12); `.part` files are listed.
     */
    fun list(): List<Entry>

    /** Deletes every file whose name ends `.part` in the folder and its subfolders, and nothing else; returns how many (4.6). */
    fun sweepParts(): Int

    /** Free space on the phone's storage where the folder is, in bytes (4.7). */
    fun freeBytes(): Long

    companion object {
        /** The marker that keeps the Gallery out of the folder (4.12); never listed. */
        const val NOMEDIA = ".nomedia"

        /** The file the folder picker's check creates and deletes (4.12); never listed. */
        const val WRITE_TEST = ".coordinator-test"

        /** The ending of a file still being written. */
        const val PART = ".part"
    }
}
