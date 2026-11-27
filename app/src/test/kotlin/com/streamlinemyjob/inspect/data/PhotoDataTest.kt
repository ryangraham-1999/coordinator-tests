package com.streamlinemyjob.inspect.data

import androidx.test.core.app.ApplicationProvider
import com.streamlinemyjob.inspect.core.Photos
import com.streamlinemyjob.inspect.photo.DirPhotoFolder
import com.streamlinemyjob.inspect.photo.PhotoFolder
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Random

/**
 * Stage 2 design 4.10, section 6 (`PhotoDataTest`, S2.4): the generator with photos and moves, then 200 random photo moves and
 * deletes (and finding, inspection and vehicle deletes that take photos with them), `DataCheck` clean after each, the snapshot and
 * the folder equal to what the test expects. One planted fault per `IC_PHOTO_*` code is reported alone; a planted orphan file
 * makes the check fail. Runs on the confirmed seed and on a generated seed with `Mutation.apply`.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoDataTest {
    private val random = TestRandom.forTest(PhotoDataTest::class.java.name)
    private val dirs = ArrayList<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    private class World(val t: TestDb, val folder: PhotoFolder, val expected: Snapshot)

    /** A database and folder filled by the generator with photos (100 findings): the confirmed seed, or a generated one plus `Mutation.apply`. */
    private fun world(seed: Seed): World {
        val dir = Files.createTempDirectory("photo-data-test").toFile().also { dirs += it }
        val folder = DirPhotoFolder(dir)
        val seedText = if (seed == Seed.CONFIRMED) ConfirmedSeed.text else SeedGenerator.generate(random, ConfirmedSeed.listNames)
        val t = TestDb(seedText, folder = folder)
        val port = RepositoryAdapter(t.db, t.drawing, folder)
        if (seed == Seed.GENERATED) Mutation.apply(random, port)
        val start = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val expected = DataGenerator.generate(random, port, findings = FINDINGS, start = start, drawing = t.drawing, photos = true)
        return World(t, folder, expected)
    }

    private enum class Seed { CONFIRMED, GENERATED }

    private fun assertMatches(w: World, expected: Snapshot, what: String) {
        Snapshot.assertSame(expected, w.t.snapshot(), what)
        assertEquals("$what: DataCheck", emptyList<Violation>(), w.t.check())
    }

    // ---- the generator's photos ----

    private fun checkGenerated(seed: Seed) {
        world(seed).use { w ->
            val e = w.expected
            assertMatches(w, e, "after the generator")
            val perFinding = e.photos.groupBy { it.findingId }
            assertTrue("some findings have photos", perFinding.size >= 2)
            assertTrue("one finding has exactly 20", perFinding.values.any { it.size == Photos.MAX_PER_FINDING })
            assertTrue("no finding has more than 20", perFinding.values.all { it.size <= Photos.MAX_PER_FINDING })
            assertTrue("a finding has 2 or more", perFinding.values.any { it.size >= 2 })
            assertEquals("every photo has its file", e.photos.map { it.relPath }.sorted(), e.photoFiles.keys.sorted())
            for ((_, rows) in perFinding) assertEquals((0 until rows.size).toList(), rows.map { it.position }.sorted())
            assertEquals("every file has its row's length", e.photos.associate { it.relPath to it.bytes.toLong() }, e.photoFiles)
            assertEquals("every file has a distinct name", e.photos.size, e.photos.map { it.relPath }.toSet().size)
        }
    }

    @Test
    fun theGeneratedPhotosAreSavedWithTheConfirmedSeed() = checkGenerated(Seed.CONFIRMED)

    @Test
    fun theGeneratedPhotosAreSavedWithAGeneratedSeedAndMutation() = checkGenerated(Seed.GENERATED)

    /** At least one finding is moved by the generator in some run: its positions differ from the order the photos were made in. */
    @Test
    fun theGeneratorMovesSomePhotos() {
        var moved = false
        repeat(5) {
            world(Seed.CONFIRMED).use { w ->
                val byTime = w.expected.photos.groupBy { it.findingId }.values
                if (byTime.any { rows -> rows.sortedBy { it.position }.map { it.takenAt } != rows.map { it.takenAt }.sorted() }) moved = true
            }
        }
        assertTrue("some photo was moved", moved)
    }

    // ---- 200 random deletes and moves ----

    private fun checkRandomOperations(seed: Seed) {
        world(seed).use { w ->
            var m = w.expected
            assertMatches(w, m, "start")
            fun dropFindings(ids: Set<String>): Snapshot {
                val gone = m.photos.filter { it.findingId in ids }
                return m.copy(
                    findings = m.findings.filter { it.id !in ids },
                    photos = m.photos.filter { it.findingId !in ids },
                    photoFiles = m.photoFiles - gone.map { it.relPath }.toSet(),
                )
            }
            var done = 0
            while (done < OPERATIONS) {
                val photos = m.photos
                val roll = random.nextInt(100)
                val what: String
                if (photos.isEmpty() || roll >= 92 && m.findings.isNotEmpty()) {
                    if (m.findings.isEmpty()) break
                    // a delete that takes a finding, an inspection or a vehicle (and any photos on them) with it
                    val kind = random.nextInt(10)
                    if (kind < 6) {
                        val f = m.findings[random.nextInt(m.findings.size)]
                        what = "deleteFinding ${f.id}"
                        assertTrue(w.t.repo.deleteFinding(f.id) is Ok)
                        m = dropFindings(setOf(f.id))
                    } else if (kind < 9) {
                        val i = m.inspections[random.nextInt(m.inspections.size)]
                        what = "deleteInspection ${i.id}"
                        assertTrue(w.t.repo.deleteInspection(i.id) is Ok)
                        val gone = m.findings.filter { it.inspectionId == i.id }.map { it.id }.toSet()
                        m = dropFindings(gone).let { it.copy(inspections = it.inspections.filter { x -> x.id != i.id }) }
                    } else {
                        val v = m.vehicles[random.nextInt(m.vehicles.size)]
                        what = "deleteVehicle ${v.id}"
                        assertTrue(w.t.repo.deleteVehicle(v.id) is Ok)
                        val ins = m.inspections.filter { it.vehicleId == v.id }.map { it.id }.toSet()
                        val gone = m.findings.filter { it.inspectionId in ins }.map { it.id }.toSet()
                        m = dropFindings(gone).let { it.copy(inspections = it.inspections.filter { x -> x.id !in ins }, vehicles = it.vehicles.filter { x -> x.id != v.id }) }
                    }
                } else if (roll < 50) {
                    val p = photos[random.nextInt(photos.size)]
                    val siblings = photos.filter { it.findingId == p.findingId }.sortedBy { it.position }
                    val to = random.nextInt(siblings.size)
                    what = "movePhoto ${p.id} from ${p.position} to $to"
                    assertEquals(Ok(p.id), w.t.repo.movePhoto(p.id, to))
                    val order = siblings.toMutableList().also { it.remove(p); it.add(to, p) }
                    val newPos = order.mapIndexed { i, x -> x.id to i }.toMap()
                    m = m.copy(photos = photos.map { if (it.id in newPos) it.copy(position = newPos.getValue(it.id)) else it })
                } else {
                    val p = photos[random.nextInt(photos.size)]
                    what = "deletePhoto ${p.id} (position ${p.position})"
                    assertEquals(Ok(p.id), w.t.repo.deletePhoto(p.id))
                    m = m.copy(
                        photos = photos.filter { it.id != p.id }.map {
                            if (it.findingId == p.findingId && it.position > p.position) it.copy(position = it.position - 1) else it
                        },
                        photoFiles = m.photoFiles - p.relPath,
                    )
                }
                done++
                assertMatches(w, m, "after operation $done: $what")
            }
            assertEquals("all operations were made", OPERATIONS, done)
        }
    }

    @Test
    fun twoHundredRandomOperationsWithTheConfirmedSeed() = checkRandomOperations(Seed.CONFIRMED)

    @Test
    fun twoHundredRandomOperationsWithAGeneratedSeedAndMutation() = checkRandomOperations(Seed.GENERATED)

    // ---- a database reopened from its file ----

    private fun checkReopen(seed: Seed) {
        world(seed).use { w ->
            w.t.db.close()
            val reopened = AppDatabase.build(ApplicationProvider.getApplicationContext(), w.t.name, allowMainThread = true)
            try {
                assertEquals("clean after the reopen", emptyList<Violation>(), DataCheck.run(reopened, w.t.drawing, w.folder))
                Snapshot.assertSame(w.expected, Snapshot.read(reopened, w.folder), "reopened from its file")
                assertTrue(w.expected.photos.isNotEmpty())
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun aReopenedDatabaseReadsBackTheSamePhotosWithTheConfirmedSeed() = checkReopen(Seed.CONFIRMED)

    @Test
    fun aReopenedDatabaseReadsBackTheSamePhotosWithAGeneratedSeed() = checkReopen(Seed.GENERATED)

    // ---- planted faults: one code each, alone ----

    private fun plantedAlone(code: String, seed: Seed = Seed.CONFIRMED, plant: (World) -> Unit) {
        world(seed).use { w ->
            assertEquals("clean before planting", emptyList<Violation>(), w.t.check())
            plant(w)
            val found = w.t.check()
            assertTrue("$code: nothing reported", found.isNotEmpty())
            assertEquals("$code alone, got $found", setOf(code), found.map { it.code }.toSet())
        }
    }

    private fun World.anyPhoto(): Photo = t.db.photoDao().all().first { it.position == 0 }

    private fun World.fullFinding(): String =
        t.db.photoDao().all().groupBy { it.findingId }.entries.first { it.value.size == Photos.MAX_PER_FINDING }.key

    private fun World.fakeBytes(n: Int) = ByteArray(n).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }

    /** Each photo code has a planted-fault test in this class (listed here by hand, so a new code without one fails). */
    @Test
    fun thereIsAPlantForEveryPhotoCode() = assertEquals(
        setOf(
            DataCheck.IC_PHOTO_FILE_MISSING, DataCheck.IC_PHOTO_ORPHAN_FILE, DataCheck.IC_PHOTO_BYTES,
            DataCheck.IC_PHOTO_POSITIONS, DataCheck.IC_PHOTO_COUNT, DataCheck.IC_PHOTO_FIELDS,
        ),
        DataCheck.PHOTO_CODES.toSet(),
    )

    @Test
    fun aPhotoWithoutItsFileIsReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_FILE_MISSING) { w -> w.folder.delete(w.anyPhoto().relPath) }

    @Test
    fun aFileWithoutARowIsReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_ORPHAN_FILE) { w ->
        w.folder.write("ZZ99ZZ/orphan file.jpg", w.fakeBytes(100))
    }

    @Test
    fun aPlantedOrphanFileMakesTheCheckFail() {
        world(Seed.CONFIRMED).use { w ->
            assertEquals(emptyList<Violation>(), w.t.check())
            w.folder.write("AB12CDE/stray.jpg", w.fakeBytes(10))
            assertEquals(listOf(DataCheck.IC_PHOTO_ORPHAN_FILE), w.t.check().map { it.code })
            // DataCheck only reads: the file is still there, and a second run says the same.
            assertTrue(w.folder.exists("AB12CDE/stray.jpg"))
            assertEquals(listOf(DataCheck.IC_PHOTO_ORPHAN_FILE), w.t.check().map { it.code })
        }
    }

    @Test
    fun aLengthThatIsNotTheFilesIsReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_BYTES) { w ->
        w.t.exec("UPDATE photo SET bytes = bytes + 1 WHERE id = ?", w.anyPhoto().id)
    }

    @Test
    fun aFileOverTheCapWithItsRowsLengthIsReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_BYTES) { w ->
        val p = w.anyPhoto()
        w.folder.delete(p.relPath)
        w.folder.write(p.relPath, w.fakeBytes(Photos.MAX_BYTES + 1))
        w.t.exec("UPDATE photo SET bytes = ? WHERE id = ?", Photos.MAX_BYTES + 1, p.id)
    }

    @Test
    fun positionsThatAreNotZeroToNMinusOneAreReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_POSITIONS) { w ->
        w.t.exec("UPDATE photo SET position = position + 1000 WHERE id = ?", w.anyPhoto().id)
    }

    @Test
    fun aFindingWithMoreThan20PhotosIsReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_COUNT) { w ->
        val f = w.fullFinding()
        val template = w.t.db.photoDao().forFinding(f).first()
        val path = template.relPath.replace(".jpg", " 99.jpg")
        w.folder.write(path, w.fakeBytes(300))
        w.t.exec(
            "INSERT INTO photo (id, finding_id, position, rel_path, width, height, bytes, quality, reduced, taken_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "planted-21st", f, Photos.MAX_PER_FINDING, path, 100, 100, 300, 70, 0, 1L,
        )
    }

    private fun fieldFault(sql: String) = plantedAlone(DataCheck.IC_PHOTO_FIELDS) { w -> w.t.exec(sql, w.anyPhoto().id) }

    @Test
    fun aQualityBelowTheFloorIsReportedAlone() = fieldFault("UPDATE photo SET quality = 59 WHERE id = ?")

    @Test
    fun aQualityAboveTheStartIsReportedAlone() = fieldFault("UPDATE photo SET quality = 86 WHERE id = ?")

    @Test
    fun aReducedFlagThatIsNotZeroOrOneIsReportedAlone() = fieldFault("UPDATE photo SET reduced = 2 WHERE id = ?")

    @Test
    fun aWidthUnderOneIsReportedAlone() = fieldFault("UPDATE photo SET width = 0 WHERE id = ?")

    @Test
    fun aHeightUnderOneIsReportedAlone() = fieldFault("UPDATE photo SET height = 0 WHERE id = ?")

    @Test
    fun aLongSideOverTheLimitIsReportedAlone() = fieldFault("UPDATE photo SET width = 2001 WHERE id = ?")

    @Test
    fun aPathNotOfTheFormIsReportedAlone() = plantedAlone(DataCheck.IC_PHOTO_FIELDS) { w ->
        val p = w.anyPhoto()
        val bytes = w.folder.open(p.relPath).use { it.readBytes() }
        w.folder.delete(p.relPath)
        w.folder.write("not a photo path.jpg", bytes)
        w.t.exec("UPDATE photo SET rel_path = ? WHERE id = ?", "not a photo path.jpg", p.id)
    }

    @Test
    fun aPhotoWhoseFindingIsMissingIsReportedAlone() = plantedAlone(DataCheck.IC_ORPHAN) { w ->
        val f = w.anyPhoto().findingId
        w.t.exec("PRAGMA foreign_keys = OFF")
        w.t.exec("DELETE FROM finding WHERE id = ?", f)
        w.t.exec("PRAGMA foreign_keys = ON")
    }

    @Test
    fun aPlantedFaultIsReportedWithAGeneratedSeedToo() = plantedAlone(DataCheck.IC_PHOTO_FILE_MISSING, Seed.GENERATED) { w ->
        w.folder.delete(w.anyPhoto().relPath)
    }

    private companion object {
        const val FINDINGS = 100
        const val OPERATIONS = 200
    }

    private fun World.use(block: (World) -> Unit) = this.also { try { block(it) } finally { it.t.close() } }
}
