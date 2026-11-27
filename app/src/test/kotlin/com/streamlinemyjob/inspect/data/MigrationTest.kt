package com.streamlinemyjob.inspect.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The upgrades (designs/stage-1-foundation.md 4.29, 4.23): a database of the old version is built from its
 * exported schema file, filled by plain SQL from a generated snapshot, upgraded, validated against the new
 * version's file, opened with the loader, and read back. Version 1 to 2: every finding that had no action
 * reads "No action". Version 2 to 3: nothing changes except that every vehicle and list is a `car`, no
 * finding has a dot, and the region-link loader makes one row per region of the drawing. The current database is version 4 (stage 2),
 * so the upgraded database is opened with `MIGRATION_3_4` as well; `PhotoMigrationTest` is the test of that step.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val random = TestRandom.forTest(MigrationTest::class.java.name)

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val drawing = ConfirmedDrawing.drawing

    private fun generated(seedText: String): Snapshot {
        TestDb(seedText).use { t ->
            val start = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            // No drawing: the data of a database made before dots existed.
            DataGenerator.generate(random, RepositoryAdapter(t.db), findings = FINDINGS, start = start)
            return t.snapshot().copy(regionLinks = emptyList())
        }
    }

    /** Writes [s] into a database of [version] (1 or 2): the finding table of version 1 has no `no_action` column. */
    private fun fill(db: SupportSQLiteDatabase, s: Snapshot, version: Int) {
        for (v in s.vehicles) {
            db.execSQL("INSERT INTO vehicle (id, reg_display, reg_key, created_at) VALUES (?, ?, ?, ?)", arrayOf<Any?>(v.id, v.regDisplay, v.regKey, v.createdAt))
        }
        for (i in s.inspections) {
            db.execSQL("INSERT INTO inspection (id, vehicle_id, date, mileage, created_at) VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(i.id, i.vehicleId, i.date, i.mileage, i.createdAt))
        }
        for (l in s.lists) {
            db.execSQL("INSERT INTO tag_list (id, role, name, name_key, position, removed) VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any?>(l.id, l.role.name, l.name, l.nameKey, l.position, if (l.removed) 1 else 0))
        }
        for (v in s.values) {
            db.execSQL("INSERT INTO tag_value (id, list_id, name, name_key, position, removed) VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any?>(v.id, v.listId, v.name, v.nameKey, v.position, if (v.removed) 1 else 0))
        }
        for (f in s.findings) {
            if (version == 1) {
                db.execSQL(
                    "INSERT INTO finding (id, inspection_id, area_value_id, damage_value_id, action_value_id, note, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(f.id, f.inspectionId, f.areaValueId, f.damageValueId, f.actionValueId, f.note, f.createdAt, f.updatedAt),
                )
            } else {
                db.execSQL(
                    "INSERT INTO finding (id, inspection_id, area_value_id, damage_value_id, action_value_id, no_action, note, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(f.id, f.inspectionId, f.areaValueId, f.damageValueId, f.actionValueId, if (f.noAction) 1 else 0, f.note, f.createdAt, f.updatedAt),
                )
            }
        }
    }

    /** What version 1 to 2 must produce: every finding without an action is now a deliberate "No action". */
    private fun upgradedTo2(s: Snapshot): Snapshot = s.copy(findings = s.findings.map { it.copy(noAction = it.actionValueId == null) })

    /**
     * What the region-link loader must make (4.23), worked out here from the snapshot and the link file, not by
     * the loader: one row per region; a link whose value is in the snapshot's AREA lists (removed or not).
     */
    private fun expectedLinks(s: Snapshot): List<Snapshot.RegionLinkRow> {
        val areaValues = s.values.filter { v -> s.lists.first { it.id == v.listId }.role == Role.AREA }.map { it.id }.toSet()
        val start = ConfirmedDrawing.links.links.associate { it.region to it.value }
        return drawing.regions.map { r ->
            Snapshot.RegionLinkRow(drawing.id, r.id, start[r.id]?.takeIf { it in areaValues })
        }.sortedWith(compareBy({ it.drawingId }, { it.regionId }))
    }

    /** Upgrades [s] from [from] to 3 through [migrations]; returns the read-back snapshot after the loader ran. */
    private fun upgrade(s: Snapshot, from: Int, migrations: List<Migration>, prepare: (SupportSQLiteDatabase) -> Unit = {}): Snapshot {
        val name = "migration-${UUID.randomUUID()}.db"
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            helper.createDatabase(name, from).use { fill(it, s, from); prepare(it) }
            helper.runMigrationsAndValidate(name, 3, true, *migrations.toTypedArray()).close()
            val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*migrations.toTypedArray(), MIGRATION_3_4).allowMainThreadQueries().build()
            try {
                assertEquals("no region link before the loader", 0, db.regionLinkDao().all().size)
                RegionLinkLoader.run(db, drawing, ConfirmedDrawing.links)
                assertEquals("clean after the upgrade", emptyList<Violation>(), DataCheck.run(db, drawing))
                return Snapshot.read(db)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun threeActionStates(before: Snapshot) {
        val states = before.findings.groupBy { it.actionValueId == null }
        assertTrue("the data has findings with and without an action", states.keys == setOf(true, false))
        val withoutAction = before.findings.filter { it.actionValueId == null }
        assertTrue("both \"No action\" and \"not chosen yet\" are in the data", withoutAction.map { it.noAction }.toSet() == setOf(true, false))
    }

    private fun check2to3(seedText: String) {
        val before = generated(seedText)
        threeActionStates(before)
        val after = upgrade(before, 2, listOf(MIGRATION_2_3))
        Snapshot.assertSame(before.copy(regionLinks = expectedLinks(before)), after, "version 2 to 3: snapshot differs")
        assertTrue("no finding has a dot", after.findings.all { it.dotX == null && it.dotY == null && it.dotDrawing == null })
        assertTrue("every vehicle and list is a car", after.vehicles.all { it.vehicleType == "car" } && after.lists.all { it.vehicleType == "car" })
    }

    private fun check1to3(seedText: String) {
        val before = generated(seedText)
        val states = before.findings.groupBy { it.actionValueId == null }
        assertTrue("the data has findings with and without an action", states.keys == setOf(true, false))
        val expected = upgradedTo2(before)
        val after = upgrade(before, 1, listOf(MIGRATION_1_2, MIGRATION_2_3))
        Snapshot.assertSame(expected.copy(regionLinks = expectedLinks(expected)), after, "version 1 to 3: snapshot differs")
    }

    @Test
    fun confirmedSeedUpgradesFrom2() = check2to3(ConfirmedSeed.text)

    @Test
    fun generatedSeedUpgradesFrom2() = check2to3(SeedGenerator.generate(random, ConfirmedSeed.listNames))

    @Test
    fun confirmedSeedUpgradesFrom1() = check1to3(ConfirmedSeed.text)

    @Test
    fun generatedSeedUpgradesFrom1() = check1to3(SeedGenerator.generate(random, ConfirmedSeed.listNames))

    @Test
    fun confirmedSeedLinksAllRegions() {
        val before = generated(ConfirmedSeed.text)
        val after = upgrade(before, 2, listOf(MIGRATION_2_3))
        assertEquals(drawing.regions.size, after.regionLinks.size)
        assertTrue("every region links with the confirmed seed", after.regionLinks.all { it.valueId != null })
    }

    @Test
    fun generatedSeedWithConfirmedLinksStartsEveryRegionNotLinked() {
        val before = generated(SeedGenerator.generate(random, ConfirmedSeed.listNames))
        val after = upgrade(before, 2, listOf(MIGRATION_2_3))
        assertEquals(drawing.regions.size, after.regionLinks.size)
        assertTrue("a link file belongs to its seed: nothing links", after.regionLinks.all { it.valueId == null })
    }

    @Test
    fun linkedValuesThatWereRemovedStillLink() {
        val before = generated(ConfirmedSeed.text)
        val linked = ConfirmedDrawing.links.links.map { it.value }.distinct().take(2)
        assertEquals(2, linked.size)
        val after = upgrade(before, 2, listOf(MIGRATION_2_3)) { db ->
            for (v in linked) db.execSQL("UPDATE tag_value SET removed = 1 WHERE id = ?", arrayOf<Any?>(v))
            // Close the gaps in the positions of what is left, as removing through the repository would (4.13).
            db.execSQL(
                "UPDATE tag_value SET position = (SELECT COUNT(*) FROM tag_value b WHERE b.list_id = tag_value.list_id " +
                    "AND b.removed = 0 AND b.position < tag_value.position) WHERE removed = 0",
            )
        }
        val start = ConfirmedDrawing.links.links.associate { it.region to it.value }
        assertTrue("those regions still link to the removed values", linked.all { v -> after.regionLinks.any { it.valueId == v } })
        assertEquals(start, after.regionLinks.associate { it.regionId to it.valueId })
    }

    @Test
    fun aSecondOpenChangesNoRow() {
        val before = generated(ConfirmedSeed.text)
        val name = "migration-${UUID.randomUUID()}.db"
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            helper.createDatabase(name, 2).use { fill(it, before, 2) }
            helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).close()
            val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
            try {
                val first = RegionLinkLoader.run(db, drawing, ConfirmedDrawing.links)
                assertEquals(drawing.regions.size, first.linked + first.notLinked)
                val afterFirst = Snapshot.read(db)
                // The owner unlinks one region; a second run must leave that row, and every other, as it is.
                val one = afterFirst.regionLinks.first { it.valueId != null }
                db.openHelper.writableDatabase.execSQL("UPDATE region_link SET value_id = NULL WHERE region_id = ?", arrayOf<Any?>(one.regionId))
                val afterEdit = Snapshot.read(db)
                val second = RegionLinkLoader.run(db, drawing, ConfirmedDrawing.links)
                assertEquals(RegionLinkLoader.Report(0, 0), second)
                Snapshot.assertSame(afterEdit, Snapshot.read(db), "a second run changed a row")
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun anUpgradeThatLeavesOutTheUpdateIsCaught() {
        val before = generated(ConfirmedSeed.text)
        val withoutUpdate = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE finding ADD COLUMN no_action INTEGER NOT NULL DEFAULT 0")
            }
        }
        val expected = upgradedTo2(before)
        val after = upgrade(before, 1, listOf(withoutUpdate, MIGRATION_2_3))
        assertTrue("the wrong upgrade is shown", Snapshot.diff(expected.copy(regionLinks = expectedLinks(expected)), after).isNotEmpty())
    }

    @Test
    fun aRowChangedAfterMigratingIsReported() {
        val before = generated(ConfirmedSeed.text)
        val after = upgrade(before, 2, listOf(MIGRATION_2_3))
        val f = after.findings.first()
        val changed = after.copy(findings = after.findings.map { if (it.id == f.id) it.copy(note = (it.note ?: "") + "x") else it })
        assertTrue("the comparison reports a changed finding", Snapshot.diff(before.copy(regionLinks = after.regionLinks), changed).isNotEmpty())
    }

    private companion object {
        const val FINDINGS = 100
    }
}
