package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.photo.DirPhotoFolder
import com.streamlinemyjob.inspect.photo.PhotoFolder
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Random

/**
 * Stage 2 design 4.12, section 6 (`ReinstallTest`, S2.4): a fresh database with the folder an earlier database filled, as after an
 * uninstall and a reinstall. No file is deleted (the start-up sweep takes only `.part` leftovers); every old file is counted as an
 * unknown file; new photos get names that do not clash with the old ones. The two runs use the same random numbers, so the new
 * database makes the very same regs, dates, areas and times, and every new name would clash if the app did not look at the folder.
 * A sweep that deleted unknown files is caught. Runs on the confirmed seed and on a generated seed with `Mutation.apply`.
 */
@RunWith(RobolectricTestRunner::class)
class ReinstallTest {
    private val random = TestRandom.forTest(ReinstallTest::class.java.name)
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** What [PhotoFolder.sweepParts] must do; the sabotage test swaps in a worse one. */
    private fun interface Sweep {
        fun run(folder: PhotoFolder): Int
    }

    private val realSweep = Sweep { it.sweepParts() }

    /** Fills a database and [folder] with generated photos: [seedText], the same `Random(seed)` and the same [start] every time. */
    private fun fill(seedText: String, mutate: Boolean, seed: Long, start: Instant, folder: PhotoFolder): Pair<TestDb, Snapshot> {
        val t = TestDb(seedText, folder = folder)
        val port = RepositoryAdapter(t.db, t.drawing, folder)
        val r = Random(seed)
        if (mutate) Mutation.apply(r, port)
        val expected = DataGenerator.generate(r, port, findings = FINDINGS, start = start, drawing = t.drawing, photos = true)
        return t to expected
    }

    private fun reinstall(seedText: String, mutate: Boolean, sweep: Sweep) {
        val dir = Files.createTempDirectory("reinstall-test").toFile().also { dirs += it }
        val folder = DirPhotoFolder(dir)
        val seed = random.nextLong()
        val start = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        // The first install: its photos, a file the app never made, and a half-written leftover.
        val (first, firstExpected) = fill(seedText, mutate, seed, start, folder)
        first.use { }
        folder.write("AB12CDE/holiday snapshot.jpg", ByteArray(77) { 7 })
        folder.write("misc/notes.txt", ByteArray(5) { 1 })
        folder.write(PhotoFolder.NOMEDIA, ByteArray(0))
        folder.write("AB12CDE/half written.jpg.part", ByteArray(9))
        val oldFiles = folder.list().associate { it.relPath to it.length }
        val oldPhotoPaths = firstExpected.photos.map { it.relPath }.toSet()
        assertTrue("the first install made photos", oldPhotoPaths.size > 10)
        assertEquals(oldPhotoPaths + setOf("AB12CDE/holiday snapshot.jpg", "misc/notes.txt", "AB12CDE/half written.jpg.part"), oldFiles.keys)

        // The reinstall: a fresh database and the same folder. The app's start-up sweep runs first.
        val swept = sweep.run(folder)
        val afterSweep = folder.list().associate { it.relPath to it.length }
        assertEquals("the sweep removed the .part leftover", 1, swept)
        assertEquals(
            "no file but the .part leftover was deleted",
            oldFiles - "AB12CDE/half written.jpg.part", afterSweep,
        )
        assertTrue(folder.exists(PhotoFolder.NOMEDIA))

        val fresh = TestDb(seedText, folder = folder)
        try {
            val photosBefore = Snapshot.read(fresh.db, folder)
            assertEquals("a fresh database has no photo", emptyList<Snapshot.PhotoRow>(), photosBefore.photos)
            // Every old file is an unknown file: in the folder, with no row.
            val unknown = folder.list().map { it.relPath }.toSet() - fresh.db.photoDao().all().map { it.relPath }.toSet()
            assertEquals("every old file is unknown", afterSweep.keys, unknown)
            assertEquals(
                "the integrity check reports each as an orphan file and nothing else",
                afterSweep.keys.sorted().map { Violation(DataCheck.IC_PHOTO_ORPHAN_FILE, listOf(it)) },
                fresh.check(),
            )

            // New photos: the same regs, dates, areas and times as before, so only the folder keeps the names apart.
            val port = RepositoryAdapter(fresh.db, fresh.drawing, folder)
            val r = Random(seed)
            if (mutate) Mutation.apply(r, port)
            val expected = DataGenerator.generate(r, port, findings = FINDINGS, start = start, drawing = fresh.drawing, photos = true)
            val newPaths = expected.photos.map { it.relPath }.toSet()
            assertTrue("new photos were made", newPaths.size > 10)
            assertEquals("no new name clashes with an old file", emptySet<String>(), newPaths intersect afterSweep.keys)
            // The same photo (same time) was named differently the second time: the clash really happened and was avoided.
            val oldByTime = firstExpected.photos.associate { it.takenAt to it.relPath }
            for (p in expected.photos) {
                val old = oldByTime[p.takenAt] ?: fail("no photo of the first install was taken at ${p.takenAt}").let { "" }
                assertTrue("photo taken at ${p.takenAt} reused the name ${p.relPath}", old != p.relPath)
            }
            Snapshot.assertSame(expected, Snapshot.read(fresh.db, folder), "after the reinstall")
            for ((path, length) in afterSweep) assertEquals("old file $path unchanged", length, folder.length(path))
            // Now only the old files are orphans.
            assertEquals(
                afterSweep.keys.sorted().map { Violation(DataCheck.IC_PHOTO_ORPHAN_FILE, listOf(it)) },
                fresh.check(),
            )
        } finally {
            fresh.close()
        }
    }

    @Test
    fun aReinstallKeepsEveryFileAndNamesNewPhotosApartWithTheConfirmedSeed() = reinstall(ConfirmedSeed.text, false, realSweep)

    @Test
    fun aReinstallKeepsEveryFileAndNamesNewPhotosApartWithAGeneratedSeedAndMutation() =
        reinstall(SeedGenerator.generate(random, ConfirmedSeed.listNames), true, realSweep)

    /** The proof the test can fail: a sweep that also deleted the unknown files is caught by it. */
    @Test
    fun aSweepThatDeletesUnknownFilesFailsTheTest() {
        val deleteEverything = Sweep { folder ->
            val all = folder.list()
            all.forEach { folder.delete(it.relPath) }
            1
        }
        try {
            reinstall(ConfirmedSeed.text, false, deleteEverything)
            fail("a sweep that deletes unknown files must fail the reinstall test")
        } catch (e: AssertionError) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("sweep") || e.message.orEmpty().contains("deleted"))
        }
    }

    @Test
    fun theUnknownCountIsTheFilesNoRowPointsTo() {
        val dir = Files.createTempDirectory("reinstall-test").toFile().also { dirs += it }
        val folder = DirPhotoFolder(dir)
        val t = TestDb(ConfirmedSeed.text, folder = folder)
        try {
            val v = (t.repo.addVehicle("AB12 CDE") as Ok).value
            val i = (t.repo.addInspection(v, "27/11/2025", "1") as Ok).value
            val f = (t.repo.addFinding(
                i, t.activeValues(Role.AREA).first().id, t.activeValues(Role.DAMAGE).first().id,
                com.streamlinemyjob.inspect.core.ActionChoice.NoAction, null, true,
            ) as Ok).value
            assertTrue(t.repo.addPhoto(f, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), 10, 10, 85, false) is Ok)
            folder.write("AB12CDE/other.jpg", byteArrayOf(1))
            val unknown = folder.list().map { it.relPath }.toSet() - t.db.photoDao().all().map { it.relPath }.toSet()
            assertEquals(setOf("AB12CDE/other.jpg"), unknown)
        } finally {
            t.close()
        }
    }

    private companion object {
        const val FINDINGS = 60
    }
}
