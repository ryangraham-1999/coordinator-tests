package com.streamlinemyjob.inspect.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Database version 3 to 4 (designs/stage-2-camera.md 4.4, section 6 `PhotoMigrationTest`): version 3 databases are made from
 * generated snapshots (the confirmed seed and a generated seed, 100 findings each, with dots and all three action states), filled
 * by plain SQL, migrated to 4 and validated against `4.json`. Every version 3 row reads back unchanged; `photo` and `app_setting`
 * are empty; a version 1 database migrated 1 to 2 to 3 to 4 reads back as it did before (as `MigrationTest` shows for 1 to 3).
 * A `MIGRATION_3_4` without the unique index on `rel_path` fails validation against `4.json`.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoMigrationTest {
    private val random = TestRandom.forTest(PhotoMigrationTest::class.java.name)

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private val drawing = ConfirmedDrawing.drawing
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A snapshot of generated data with dots (version 3 had them), made through the repository on a current database. */
    private fun generatedWithDots(seedText: String): Snapshot {
        TestDb(seedText).use { t ->
            val start = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val port = RepositoryAdapter(t.db, t.drawing)
            // A generated seed starts with no region linked (a link file belongs to its seed), so link some: dots need links.
            if (seedText != ConfirmedSeed.text) Mutation.relink(random, port)
            DataGenerator.generate(random, port, findings = FINDINGS, start = start, drawing = t.drawing)
            return t.snapshot()
        }
    }

    /** As [generatedWithDots] but without a drawing: the data of a database made before dots existed (as `MigrationTest`). */
    private fun generatedWithoutDots(seedText: String): Snapshot {
        TestDb(seedText).use { t ->
            val start = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            DataGenerator.generate(random, RepositoryAdapter(t.db), findings = FINDINGS, start = start)
            return t.snapshot().copy(regionLinks = emptyList())
        }
    }

    private fun fillCommon(db: SupportSQLiteDatabase, s: Snapshot, version: Int) {
        for (v in s.vehicles) {
            if (version >= 3) {
                db.execSQL(
                    "INSERT INTO vehicle (id, reg_display, reg_key, created_at, vehicle_type) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(v.id, v.regDisplay, v.regKey, v.createdAt, v.vehicleType),
                )
            } else {
                db.execSQL(
                    "INSERT INTO vehicle (id, reg_display, reg_key, created_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(v.id, v.regDisplay, v.regKey, v.createdAt),
                )
            }
        }
        for (i in s.inspections) {
            db.execSQL(
                "INSERT INTO inspection (id, vehicle_id, date, mileage, created_at) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(i.id, i.vehicleId, i.date, i.mileage, i.createdAt),
            )
        }
        for (l in s.lists) {
            if (version >= 3) {
                db.execSQL(
                    "INSERT INTO tag_list (id, role, name, name_key, position, removed, vehicle_type) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(l.id, l.role.name, l.name, l.nameKey, l.position, if (l.removed) 1 else 0, l.vehicleType),
                )
            } else {
                db.execSQL(
                    "INSERT INTO tag_list (id, role, name, name_key, position, removed) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(l.id, l.role.name, l.name, l.nameKey, l.position, if (l.removed) 1 else 0),
                )
            }
        }
        for (v in s.values) {
            db.execSQL(
                "INSERT INTO tag_value (id, list_id, name, name_key, position, removed) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(v.id, v.listId, v.name, v.nameKey, v.position, if (v.removed) 1 else 0),
            )
        }
    }

    /** Writes [s] into a version 3 database: dots, action states, vehicle types and region links included. */
    private fun fill3(db: SupportSQLiteDatabase, s: Snapshot) {
        fillCommon(db, s, 3)
        for (f in s.findings) {
            db.execSQL(
                "INSERT INTO finding (id, inspection_id, area_value_id, damage_value_id, action_value_id, no_action, note, " +
                    "created_at, updated_at, dot_x, dot_y, dot_drawing) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    f.id, f.inspectionId, f.areaValueId, f.damageValueId, f.actionValueId, if (f.noAction) 1 else 0, f.note,
                    f.createdAt, f.updatedAt, f.dotX, f.dotY, f.dotDrawing,
                ),
            )
        }
        for (r in s.regionLinks) {
            db.execSQL("INSERT INTO region_link (drawing_id, region_id, value_id) VALUES (?, ?, ?)", arrayOf<Any?>(r.drawingId, r.regionId, r.valueId))
        }
    }

    /** Writes [s] into a version 1 database: its `finding` has no `no_action` and no dot columns. */
    private fun fill1(db: SupportSQLiteDatabase, s: Snapshot) {
        fillCommon(db, s, 1)
        for (f in s.findings) {
            db.execSQL(
                "INSERT INTO finding (id, inspection_id, area_value_id, damage_value_id, action_value_id, note, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(f.id, f.inspectionId, f.areaValueId, f.damageValueId, f.actionValueId, f.note, f.createdAt, f.updatedAt),
            )
        }
    }

    /** Upgrades the database [name] of version [from] to 4 through [migrations], opens it like the app does, and reads it back. */
    private fun migrateAndRead(name: String, from: Int, migrations: List<Migration>, fill: (SupportSQLiteDatabase) -> Unit): Snapshot {
        try {
            helper.createDatabase(name, from).use { fill(it) }
            helper.runMigrationsAndValidate(name, 4, true, *migrations.toTypedArray()).use { db ->
                for (table in listOf("photo", "app_setting")) {
                    db.query("SELECT COUNT(*) FROM $table").use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals("$table is empty after the upgrade", 0, c.getInt(0))
                    }
                }
            }
            val db = AppDatabase.build(context, name, allowMainThread = true)
            try {
                if (from < 3) RegionLinkLoader.run(db, drawing, ConfirmedDrawing.links)
                assertEquals("clean after the upgrade", emptyList<Violation>(), DataCheck.run(db, drawing))
                return Snapshot.read(db)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun check3to4(seedText: String) {
        val before = generatedWithDots(seedText)
        assertTrue("the data has dots", before.findings.any { it.dotX != null })
        val withoutAction = before.findings.filter { it.actionValueId == null }
        assertTrue("both \"No action\" and \"not chosen yet\" are in the data", withoutAction.map { it.noAction }.toSet() == setOf(true, false))
        assertTrue("some finding has an action", before.findings.any { it.actionValueId != null })
        assertTrue("every region has a row", before.regionLinks.size == drawing.regions.size)
        val after = migrateAndRead("photo-migration-${UUID.randomUUID()}.db", 3, listOf(MIGRATION_3_4)) { fill3(it, before) }
        Snapshot.assertSame(before, after, "version 3 to 4: snapshot differs")
        assertEquals(emptyList<Snapshot.PhotoRow>(), after.photos)
        assertEquals(emptyMap<String, String>(), after.settings)
    }

    @Test
    fun confirmedSeedUpgradesFrom3() = check3to4(ConfirmedSeed.text)

    @Test
    fun generatedSeedUpgradesFrom3() = check3to4(SeedGenerator.generate(random, ConfirmedSeed.listNames))

    private fun expectedLinks(s: Snapshot): List<Snapshot.RegionLinkRow> {
        val areaValues = s.values.filter { v -> s.lists.first { it.id == v.listId }.role == Role.AREA }.map { it.id }.toSet()
        val start = ConfirmedDrawing.links.links.associate { it.region to it.value }
        return drawing.regions.map { r -> Snapshot.RegionLinkRow(drawing.id, r.id, start[r.id]?.takeIf { it in areaValues }) }
            .sortedWith(compareBy({ it.drawingId }, { it.regionId }))
    }

    private fun check1to4(seedText: String) {
        val before = generatedWithoutDots(seedText)
        val expected = before.copy(findings = before.findings.map { it.copy(noAction = it.actionValueId == null) })
        val after = migrateAndRead(
            "photo-migration-${UUID.randomUUID()}.db", 1, listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4),
        ) { fill1(it, before) }
        Snapshot.assertSame(expected.copy(regionLinks = expectedLinks(expected)), after, "version 1 to 4: snapshot differs")
    }

    @Test
    fun confirmedSeedUpgradesFrom1ThroughEveryVersion() = check1to4(ConfirmedSeed.text)

    @Test
    fun generatedSeedUpgradesFrom1ThroughEveryVersion() = check1to4(SeedGenerator.generate(random, ConfirmedSeed.listNames))

    @Test
    fun aMigrationWithoutTheUniqueIndexFailsValidationAgainst4Json() {
        val before = generatedWithDots(ConfirmedSeed.text)
        val wrong = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo` (`id` TEXT NOT NULL, `finding_id` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`rel_path` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, " +
                        "`quality` INTEGER NOT NULL, `reduced` INTEGER NOT NULL, `taken_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`finding_id`) REFERENCES `finding`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_finding_id` ON `photo` (`finding_id`)")
                // the unique index `index_photo_rel_path` is missing on purpose
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        val name = "photo-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 3).use { fill3(it, before) }
            try {
                helper.runMigrationsAndValidate(name, 4, true, wrong).close()
                fail("a migration without the unique index must fail validation against 4.json")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("photo"))
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val FINDINGS = 100
    }
}
