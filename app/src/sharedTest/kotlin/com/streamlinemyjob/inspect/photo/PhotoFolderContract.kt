package com.streamlinemyjob.inspect.photo

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The one contract every [PhotoFolder] must pass (designs/stage-2-camera.md 4.12, section 6): write, `.part` then rename,
 * unique names, delete, list, sweep, a missing file, a subfolder made on demand. Test code shared by the JVM tests (over
 * `DirPhotoFolder`, S2.4) and the instrumented tests (over `SafPhotoFolder`, S2.5). A subclass makes a fresh, empty folder.
 * Files the app did not make are planted through [PhotoFolder.write] itself (a name ending `.part` is written as `<name>.part.part`
 * and renamed to `<name>.part`), so no implementation needs a second way in.
 */
abstract class PhotoFolderContract {
    /** A fresh folder with nothing in it. */
    protected abstract fun newFolder(): PhotoFolder

    private fun bytes(n: Int, seed: Int = n): ByteArray = ByteArray(n) { (it * 31 + seed).toByte() }

    private fun read(f: PhotoFolder, path: String): ByteArray = f.open(path).use { it.readBytes() }

    @Test
    fun aNewFolderIsReadyAndEmpty() {
        val f = newFolder()
        assertTrue(f.isReady())
        assertEquals(emptyList<PhotoFolder.Entry>(), f.list())
        assertTrue(f.freeBytes() > 0)
    }

    @Test
    fun writeMakesTheFileAndItsSubfolderOnDemand() {
        val f = newFolder()
        val data = bytes(1234)
        f.write("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg", data)
        assertTrue(f.exists("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg"))
        assertEquals(1234L, f.length("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg"))
        assertArrayEquals(data, read(f, "AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg"))
        // A second reg gets its own subfolder; a path with accents and a non-BMP character works.
        f.write("XYZ1234/XYZ1234 2026-11-27 é 𝔸 090000.jpg", bytes(10))
        assertEquals(
            listOf("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg" to 1234L, "XYZ1234/XYZ1234 2026-11-27 é 𝔸 090000.jpg" to 10L),
            f.list().map { it.relPath to it.length }.sortedBy { it.first },
        )
    }

    @Test
    fun noPartFileIsLeftAfterAWrite() {
        val f = newFolder()
        f.write("AB/a.jpg", bytes(50))
        assertEquals(listOf("AB/a.jpg"), f.list().map { it.relPath })
        assertFalse(f.exists("AB/a.jpg.part"))
    }

    @Test
    fun aNameThatIsTakenIsRefusedAndTheFileIsLeftAsItWas() {
        val f = newFolder()
        val first = bytes(100, 1)
        f.write("AB/a.jpg", first)
        try {
            f.write("AB/a.jpg", bytes(200, 2))
            fail("a taken name must be refused")
        } catch (e: FileAlreadyExistsException) {
            // expected
        }
        assertArrayEquals(first, read(f, "AB/a.jpg"))
        assertEquals(listOf("AB/a.jpg"), f.list().map { it.relPath })
        assertFalse("the refused write left a .part", f.exists("AB/a.jpg.part"))
    }

    @Test
    fun deleteRemovesOneFileAndOnlyThatFile() {
        val f = newFolder()
        f.write("AB/a.jpg", bytes(1))
        f.write("AB/b.jpg", bytes(2))
        f.delete("AB/a.jpg")
        assertFalse(f.exists("AB/a.jpg"))
        assertTrue(f.exists("AB/b.jpg"))
    }

    @Test
    fun deletingAFileThatIsNotThereIsNotAnError() {
        val f = newFolder()
        f.delete("AB/never.jpg")
        f.delete("NOSUCH/never.jpg")
    }

    @Test
    fun aMissingFileHasNoLengthAndCannotBeOpened() {
        val f = newFolder()
        assertFalse(f.exists("AB/missing.jpg"))
        try {
            f.length("AB/missing.jpg")
            fail("length of a missing file must throw")
        } catch (e: IOException) {
            // expected
        }
        try {
            f.open("AB/missing.jpg").close()
            fail("open of a missing file must throw")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun listGivesEveryFileOfTheFolderAndItsSubfoldersExceptTheMarkers() {
        val f = newFolder()
        f.write("AB/a.jpg", bytes(5))
        f.write("CD/b.jpg", bytes(6))
        f.write("loose.jpg", bytes(7))
        f.write(PhotoFolder.NOMEDIA, bytes(0))
        f.write(PhotoFolder.WRITE_TEST, bytes(1))
        f.write("AB/pending.jpg.part", bytes(3))
        assertEquals(
            listOf("AB/a.jpg" to 5L, "AB/pending.jpg.part" to 3L, "CD/b.jpg" to 6L, "loose.jpg" to 7L),
            f.list().map { it.relPath to it.length },
        )
    }

    @Test
    fun sweepDeletesPartFilesAndNothingElse() {
        val f = newFolder()
        f.write("AB/a.jpg", bytes(5))
        f.write("AB/x.jpg.part", bytes(1))
        f.write("CD/y.part", bytes(2))
        f.write("top.part", bytes(3))
        f.write("EF/unknown file.txt", bytes(4))
        f.write(PhotoFolder.NOMEDIA, bytes(0))
        assertEquals(3, f.sweepParts())
        assertEquals(listOf("AB/a.jpg", "EF/unknown file.txt"), f.list().map { it.relPath })
        assertTrue("the marker survives the sweep", f.exists(PhotoFolder.NOMEDIA))
        assertEquals(0, f.sweepParts())
    }

    @Test
    fun aFileWhoseNameOnlyContainsPartInTheMiddleSurvivesTheSweep() {
        val f = newFolder()
        f.write("AB/depart.jpg", bytes(5))
        f.write("AB/x.part.jpg", bytes(5))
        assertEquals(0, f.sweepParts())
        assertEquals(2, f.list().size)
    }
}
