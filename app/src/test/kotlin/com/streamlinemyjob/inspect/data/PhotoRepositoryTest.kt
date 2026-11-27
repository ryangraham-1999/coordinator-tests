package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Photos
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.photo.DirPhotoFolder
import com.streamlinemyjob.inspect.photo.PhotoFolder
import com.streamlinemyjob.inspect.testdata.FakeJpeg
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Stage 2 design 4.5 (section 6, S2.4): the photo calls of the Repository over a `DirPhotoFolder`. The worked example is run call by call,
 * rows, positions and folder checked after each; every refusal is checked to leave the database and the folder as they were.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoRepositoryTest {
    private val random = TestRandom.forTest(PhotoRepositoryTest::class.java.name)
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("photo-repo-test").toFile().also { dirs += it }

    private class World(val t: TestDb, val dir: File, val folder: PhotoFolder, val clock: SetClock, val repo: Repository)

    /** A database with the confirmed seed, a folder, and a repository on a fixed clock: 2026-11-27 14:32:05 in London. */
    private fun world(folderOverride: ((PhotoFolder) -> PhotoFolder)? = null): World {
        val dir = newDir()
        val base = DirPhotoFolder(dir)
        val folder = folderOverride?.invoke(base) ?: base
        val t = TestDb(ConfirmedSeed.text, folder = base)
        val clock = SetClock(ZonedDateTime.of(2026, 11, 27, 14, 32, 5, 0, ZoneId.of("Europe/London")).toInstant())
        return World(t, dir, folder, clock, Repository(t.db, clock, t.drawing, folder))
    }

    private fun World.area(name: String): TagValue = t.activeValues(Role.AREA).first { it.name == name }
    private fun World.damage(): TagValue = t.activeValues(Role.DAMAGE).first()

    private fun World.finding(inspectionId: String, areaName: String = "DSF door"): String = (repo.addFinding(
        inspectionId, area(areaName).id, damage().id, ActionChoice.NoAction, null, confirmedSameArea = true,
    ) as Ok).value

    private fun World.inspection(reg: String = "AB12 CDE", date: String = "27/11/2026"): String {
        val v = (repo.addVehicle(reg) as Ok).value
        return (repo.addInspection(v, date, "1000") as Ok).value
    }

    private fun jpeg(size: Int = 5000): ByteArray = FakeJpeg.make(random).copyOf(size).also { it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte() }

    private fun World.add(findingId: String, bytes: ByteArray = jpeg()): String =
        (repo.addPhoto(findingId, bytes, 2000, 1500, 85, false) as Ok).value

    private fun World.rows(findingId: String): List<Pair<String, Int>> = repo.photosForFinding(findingId).map { it.id to it.position }

    private fun World.files(): List<String> = folder.list().map { it.relPath }

    private fun World.paths(ids: List<String>): List<String> = ids.map { db(it).relPath }

    private fun World.db(photoId: String): Photo = t.db.photoDao().get(photoId)!!

    private fun World.snap() = Snapshot.read(t.db, folder)

    private fun World.refusedUnchanged(code: String, call: () -> Result<*>): Refused {
        val before = snap()
        val r = call()
        assertTrue("expected $code, got $r", r is Refused && r.code == code)
        Snapshot.assertSame(before, snap(), "refused $code changed the database or the folder")
        assertEquals("clean after $code", emptyList<Violation>(), t.check())
        return r as Refused
    }

    // ---- 4.5's worked example ----

    @Test
    fun theWorkedExampleStepByStep() {
        world().apply {
            val inspection = inspection()
            val f1 = finding(inspection)
            val other = finding(inspection, "DSR door")

            val a = add(f1)
            val b = add(f1)
            val c = add(f1)
            assertEquals(listOf(a to 0, b to 1, c to 2), rows(f1))
            val pathA = db(a).relPath
            val pathB = db(b).relPath
            val pathC = db(c).relPath
            assertEquals(setOf(pathA, pathB, pathC), files().toSet())
            assertEquals("three different names", 3, setOf(pathA, pathB, pathC).size)

            // movePhoto(C, 0): C 0, A 1, B 2; no file touched or renamed.
            assertEquals(Ok(c), repo.movePhoto(c, 0))
            assertEquals(listOf(c to 0, a to 1, b to 2), rows(f1))
            assertEquals(setOf(pathA, pathB, pathC), files().toSet())
            assertEquals(pathC, db(c).relPath)

            // deletePhoto(A): A's row goes, C 0, B 1; A's file is deleted after the commit.
            assertEquals(Ok(a), repo.deletePhoto(a))
            assertEquals(listOf(c to 0, b to 1), rows(f1))
            assertNull(t.db.photoDao().get(a))
            assertEquals(setOf(pathB, pathC), files().toSet())

            // addPhoto makes D at position 2.
            val d = add(f1)
            assertEquals(listOf(c to 0, b to 1, d to 2), rows(f1))
            val pathD = db(d).relPath
            assertEquals(setOf(pathB, pathC, pathD), files().toSet())

            // A photo on another finding, and a file the app does not know, in the same reg subfolder.
            val e = add(other)
            val pathE = db(e).relPath
            folder.write("AB12CDE/not mine.txt", byteArrayOf(1, 2, 3))
            assertEquals(setOf(pathB, pathC, pathD, pathE, "AB12CDE/not mine.txt"), files().toSet())
            assertEquals("clean (the planted file is an orphan file only)", listOf(DataCheck.IC_PHOTO_ORPHAN_FILE), t.check().map { it.code })

            // deleteFinding of the other finding takes its photo and its file.
            assertEquals(Ok(other), repo.deleteFinding(other))
            assertEquals(setOf(pathB, pathC, pathD, "AB12CDE/not mine.txt"), files().toSet())
            assertEquals(emptyList<Pair<String, Int>>(), rows(other))

            // deleteInspection: rows C, B, D go with F1; their three files go after the commit; the unknown file stays.
            assertEquals(Ok(inspection), repo.deleteInspection(inspection))
            assertEquals(0, t.db.photoDao().all().size)
            assertEquals(listOf("AB12CDE/not mine.txt"), files())
        }
    }

    @Test
    fun deletingAVehicleRemovesItsFilesAndNoOthers() {
        world().apply {
            val i1 = inspection("AB12 CDE")
            val i2 = inspection("XY34 ZZZ")
            val f1 = finding(i1)
            val f2 = finding(i2)
            val x = add(f1)
            val y = add(f2)
            val vehicleOfI1 = t.db.inspectionDao().get(i1)!!.vehicleId
            assertEquals(2, repo.countsForVehicle(vehicleOfI1).let { it.inspections + it.photos })
            assertEquals(InspectionCounts(1, 1), repo.countsForInspection(i1))
            val keep = db(y).relPath
            assertEquals(Ok(vehicleOfI1), repo.deleteVehicle(vehicleOfI1))
            assertEquals(listOf(keep), files())
            assertNull(t.db.photoDao().get(x))
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    @Test
    fun aDeleteOfAnInspectionWhoseFileIsAlreadyGoneIsNotAnError() {
        world().apply {
            val inspection = inspection()
            val f = finding(inspection)
            val p = add(f)
            folder.delete(db(p).relPath)
            assertEquals(Ok(inspection), repo.deleteInspection(inspection))
            assertEquals(emptyList<String>(), files())
        }
    }

    // ---- names ----

    @Test
    fun theFileIsNamedByRegDateAreaAndTimeAndMadeUniqueAgainstTheFolderAndTheTable() {
        world().apply {
            val inspection = inspection("ab12-cde", "27/11/2026")
            val f = finding(inspection)
            val a = add(f)
            val b = add(f)
            assertEquals("AB12CDE/AB12CDE 2026-11-27 DSF door 143205.jpg", db(a).relPath)
            assertEquals("AB12CDE/AB12CDE 2026-11-27 DSF door 143205 2.jpg", db(b).relPath)
            // A file in the folder that no row points to also takes a name.
            folder.write("AB12CDE/AB12CDE 2026-11-27 DSF door 143205 3.jpg", byteArrayOf(1))
            val c = add(f)
            assertEquals("AB12CDE/AB12CDE 2026-11-27 DSF door 143205 4.jpg", db(c).relPath)
            // A later time gives a new name; the time is the clock's, in its own zone.
            clock.set(ZonedDateTime.of(2026, 11, 27, 9, 0, 0, 0, ZoneId.of("Europe/London")).toInstant().toEpochMilli())
            val d = add(f)
            assertEquals("AB12CDE/AB12CDE 2026-11-27 DSF door 090000.jpg", db(d).relPath)
            assertEquals(clock.millis(), db(d).takenAt)
            assertEquals(jpeg().size, db(d).bytes)
        }
    }

    @Test
    fun theStoredPathDoesNotChangeWhenTheRegChanges() {
        world().apply {
            val inspection = inspection("AB12 CDE")
            val f = finding(inspection)
            val a = add(f)
            val path = db(a).relPath
            val vehicle = t.db.inspectionDao().get(inspection)!!.vehicleId
            assertTrue(repo.renameVehicle(vehicle, "ZZ99 ZZZ") is Ok)
            assertEquals(path, db(a).relPath)
            assertEquals(listOf(path), files())
            val b = add(f)
            assertTrue(db(b).relPath.startsWith("ZZ99ZZZ/ZZ99ZZZ 2026-11-27 "))
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    // ---- accepted edges ----

    @Test
    fun theLimitsOfWhatIsAcceptedAreAccepted() {
        world().apply {
            val f = finding(inspection())
            val exactlyCap = jpeg(Photos.MAX_BYTES)
            assertTrue(repo.addPhoto(f, exactlyCap, 2000, 1, 60, true) is Ok)
            assertTrue(repo.addPhoto(f, jpeg(4), 1, 1, 85, false) is Ok)
            assertTrue(repo.addPhoto(f, jpeg(), 1, 2000, 70, true) is Ok)
            assertEquals(Photos.MAX_BYTES.toLong(), folder.list().maxOf { it.length })
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    // ---- refusals leave everything as it was ----

    @Test
    fun eachRefusalLeavesTheDatabaseAndTheFolderUnchanged() {
        world().apply {
            val inspection = inspection()
            val f = finding(inspection)
            val photo = add(f)
            val good = jpeg()

            refusedUnchanged(RepoCodes.NOT_FOUND) { repo.addPhoto("no-such-finding", good, 100, 100, 85, false) }
            refusedUnchanged(RepoCodes.NOT_FOUND) { repo.movePhoto("no-such-photo", 0) }
            refusedUnchanged(RepoCodes.NOT_FOUND) { repo.deletePhoto("no-such-photo") }

            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, ByteArray(0), 100, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, byteArrayOf(0xFF.toByte()), 100, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good.copyOf().also { it[0] = 0 }, 100, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good.copyOf().also { it[1] = 0xD9.toByte() }, 100, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, jpeg(Photos.MAX_BYTES + 1), 100, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, 0, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, 100, 0, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, 2001, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, 100, 2001, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, -5, 100, 85, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, 100, 100, 59, false) }
            refusedUnchanged(RepoCodes.PHOTO_BAD) { repo.addPhoto(f, good, 100, 100, 86, false) }

            refusedUnchanged(RepoCodes.PHOTO_POSITION) { repo.movePhoto(photo, -1) }
            refusedUnchanged(RepoCodes.PHOTO_POSITION) { repo.movePhoto(photo, 1) }
            assertEquals("a move to its own position is accepted", Ok(photo), repo.movePhoto(photo, 0))
        }
    }

    @Test
    fun the21stPhotoIsRefusedAndNothingChanges() {
        world().apply {
            val f = finding(inspection())
            repeat(Photos.MAX_PER_FINDING) { add(f) }
            assertEquals(20, repo.photoCount(f))
            val r = refusedUnchanged(RepoCodes.PHOTO_LIMIT) { repo.addPhoto(f, jpeg(), 100, 100, 85, false) }
            assertEquals(f, r.detail)
            assertEquals(20, folder.list().size)
            // A photo of another finding is still accepted, and a deleted one frees a place.
            val g = finding(inspection("XY34 ZZZ"))
            assertTrue(repo.addPhoto(g, jpeg(), 100, 100, 85, false) is Ok)
            assertTrue(repo.deletePhoto(repo.photosForFinding(f).first().id) is Ok)
            assertTrue(repo.addPhoto(f, jpeg(), 100, 100, 85, false) is Ok)
        }
    }

    @Test
    fun withoutAReadyFolderNothingIsSavedAndTheFolderScreenIsAsked() {
        // No folder at all.
        TestDb(ConfirmedSeed.text).use { t ->
            val repo = Repository(t.db, TickingClock(), t.drawing, null)
            val v = (repo.addVehicle("AB12 CDE") as Ok).value
            val i = (repo.addInspection(v, "27/11/2026", "1") as Ok).value
            val f = (repo.addFinding(
                i, t.activeValues(Role.AREA).first().id, t.activeValues(Role.DAMAGE).first().id, ActionChoice.NoAction, null, true,
            ) as Ok).value
            val before = t.snapshot()
            val r = repo.addPhoto(f, FakeJpeg.make(random), 100, 100, 85, false)
            assertEquals(Refused(RepoCodes.PHOTO_FOLDER_NOT_SET), r)
            Snapshot.assertSame(before, t.snapshot())
        }
        // A folder that is not ready (its directory was removed, as when the picked folder is gone).
        world().apply {
            val f = finding(inspection())
            dir.deleteRecursively()
            assertFalse(folder.isReady())
            val before = t.snapshot()
            assertEquals(Refused(RepoCodes.PHOTO_FOLDER_NOT_SET), repo.addPhoto(f, jpeg(), 100, 100, 85, false))
            Snapshot.assertSame(before, t.snapshot())
        }
    }

    // ---- write failures ----

    private class FailingWrites(private val inner: PhotoFolder, private val leavePart: Boolean) : PhotoFolder by inner {
        override fun write(relPath: String, bytes: ByteArray) {
            if (leavePart) inner.write(relPath + PhotoFolder.PART, bytes)
            throw IOException("planted: the phone said no")
        }
    }

    @Test
    fun aFolderThatCannotWriteRefusesWithPhotoWriteAndLeavesNoPartFile() {
        for (leavePart in listOf(false, true)) {
            world { FailingWrites(it, leavePart) }.apply {
                val f = finding(inspection())
                val before = snap()
                val r = repo.addPhoto(f, jpeg(), 100, 100, 85, false)
                assertEquals(RepoCodes.PHOTO_WRITE, (r as Refused).code)
                assertEquals("IOException", r.detail)
                Snapshot.assertSame(before, snap(), "leavePart=$leavePart")
                assertEquals(emptyList<String>(), files())
            }
        }
    }

    @Test
    fun aRowThatCannotBeSavedDeletesTheFileItWrote() {
        world().apply {
            val f = finding(inspection())
            t.exec("CREATE TRIGGER planted_no_photos BEFORE INSERT ON photo BEGIN SELECT RAISE(ABORT, 'planted'); END")
            val before = snap()
            val r = repo.addPhoto(f, jpeg(), 100, 100, 85, false)
            assertEquals(RepoCodes.PHOTO_WRITE, (r as Refused).code)
            Snapshot.assertSame(before, snap())
            assertEquals(emptyList<String>(), files())
            t.exec("DROP TRIGGER planted_no_photos")
            assertTrue("the next photo is saved", repo.addPhoto(f, jpeg(), 100, 100, 85, false) is Ok)
        }
    }

    // ---- the file comes before the row ----

    /** Sees the database while a file is being written: the row must not exist yet. */
    private class Watching(private val inner: PhotoFolder, private val atWrite: (String) -> Unit) : PhotoFolder by inner {
        override fun write(relPath: String, bytes: ByteArray) {
            atWrite(relPath)
            inner.write(relPath, bytes)
        }
    }

    @Test
    fun theRowIsInsertedOnlyAfterTheFileIsWritten() {
        var rowsAtWrite = -1
        lateinit var dao: PhotoDao
        world { Watching(it) { rowsAtWrite = dao.all().size } }.apply {
            dao = t.db.photoDao()
            val f = finding(inspection())
            add(f)
            assertEquals("no row while the file was being written", 0, rowsAtWrite)
            assertEquals(1, dao.all().size)
        }
    }

    @Test
    fun aFileIsDeletedOnlyAfterTheDeleteCommitted() {
        // While the folder deletes, the row is already gone (deletePhoto) and so is its finding (deleteFinding).
        var rowsAtDelete = -1
        var findingsAtDelete = -1
        lateinit var db: AppDatabase
        class Watch(inner: PhotoFolder) : PhotoFolder by inner {
            private val real = inner
            override fun delete(relPath: String) {
                rowsAtDelete = db.photoDao().all().size
                findingsAtDelete = db.findingDao().all().size
                real.delete(relPath)
            }
        }
        world { Watch(it) }.apply {
            db = t.db
            val inspection = inspection()
            val f = finding(inspection)
            val p = add(f)
            assertTrue(repo.deletePhoto(p) is Ok)
            assertEquals(0, rowsAtDelete)
            val g = finding(inspection, "DSR door")
            add(g)
            assertTrue(repo.deleteFinding(g) is Ok)
            assertEquals(0, rowsAtDelete)
            assertEquals(1, findingsAtDelete)
        }
    }

    // ---- the folder setting ----

    @Test
    fun theFolderSettingIsStoredAndRead() {
        world().apply {
            assertNull(repo.photoFolder())
            assertEquals(Ok("content://tree/primary%3ADocuments%2FCoordinator"), repo.setPhotoFolder("content://tree/primary%3ADocuments%2FCoordinator"))
            assertEquals("content://tree/primary%3ADocuments%2FCoordinator", repo.photoFolder())
            repo.setPhotoFolder("content://tree/other")
            assertEquals("content://tree/other", repo.photoFolder())
            assertEquals(mapOf("photo_folder" to "content://tree/other"), snap().settings)
        }
    }

    @Test
    fun theDateOfTheInspectionNotTodayNamesTheFile() {
        world().apply {
            val inspection = inspection("AB12 CDE", "05/03/2019")
            val f = finding(inspection)
            val a = add(f)
            assertTrue(db(a).relPath, db(a).relPath.startsWith("AB12CDE/AB12CDE 2019-03-05 DSF door "))
            assertEquals(LocalDate.of(2019, 3, 5).toString(), "2019-03-05")
        }
    }
}
