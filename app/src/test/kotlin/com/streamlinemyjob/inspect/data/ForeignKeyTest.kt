package com.streamlinemyjob.inspect.data

import android.database.sqlite.SQLiteConstraintException
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S1.8 (designs/stage-1-foundation.md 4.11): foreign keys are on. A vehicle delete cascades to its
 * inspections and findings; a raw delete of a value a finding uses is refused by the foreign key.
 */
@RunWith(RobolectricTestRunner::class)
class ForeignKeyTest {
    private val random = TestRandom.forTest(ForeignKeyTest::class.java.name)

    private fun count(t: TestDb, sql: String, vararg args: Any?): Int =
        t.raw.query(sql, args).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun foreignKeysAreOn() {
        TestDb(ConfirmedSeed.text).use { t -> assertEquals(1, count(t, "PRAGMA foreign_keys")) }
    }

    @Test
    fun aVehicleDeleteCascadesToItsInspectionsAndFindings() {
        for (seed in listOf(ConfirmedSeed.text, SeedGenerator.generate(random, ConfirmedSeed.listNames))) {
            TestDb(seed).use { t ->
                populate(t, random, vehicles = 3)
                val vehicle = t.db.vehicleDao().all().first()
                val counts = t.repo.countsForVehicle(vehicle.id)
                assertTrue(counts.inspections > 0 && counts.findings > 0)
                val allFindings = count(t, "SELECT COUNT(*) FROM finding")
                assertEquals(Ok(vehicle.id), t.repo.deleteVehicle(vehicle.id))
                assertEquals(0, count(t, "SELECT COUNT(*) FROM inspection WHERE vehicle_id = ?", vehicle.id))
                assertEquals(allFindings - counts.findings, count(t, "SELECT COUNT(*) FROM finding"))
                assertEquals(VehicleCounts(0, 0), t.repo.countsForVehicle(vehicle.id))
                assertEquals(emptyList<Violation>(), t.check())
            }
        }
    }

    @Test
    fun anInspectionDeleteCascadesToItsFindings() {
        TestDb(ConfirmedSeed.text).use { t ->
            populate(t, random, vehicles = 2)
            val inspection = t.db.findingDao().all().first().inspectionId
            assertEquals(Ok(inspection), t.repo.deleteInspection(inspection))
            assertEquals(0, count(t, "SELECT COUNT(*) FROM finding WHERE inspection_id = ?", inspection))
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    @Test
    fun aRawDeleteOfAUsedValueIsRefusedByTheForeignKey() {
        TestDb(SeedGenerator.generate(random, ConfirmedSeed.listNames)).use { t ->
            populate(t, random, vehicles = 2)
            val before = t.snapshot()
            val f = t.db.findingDao().all().first()
            for (valueId in listOfNotNull(f.areaValueId, f.damageValueId, f.actionValueId)) {
                try {
                    t.exec("DELETE FROM tag_value WHERE id = ?", valueId)
                    fail("deleting used value $valueId was allowed")
                } catch (e: SQLiteConstraintException) {
                    assertTrue(e.message ?: "", (e.message ?: "").contains("FOREIGN KEY"))
                }
            }
            // A list that still holds values cannot be deleted either (RESTRICT).
            try {
                t.exec("DELETE FROM tag_list WHERE id = ?", t.db.tagDao().getValue(f.areaValueId)!!.listId)
                fail("deleting a list with values was allowed")
            } catch (e: SQLiteConstraintException) {
                assertTrue((e.message ?: "").contains("FOREIGN KEY"))
            }
            assertEquals(before, t.snapshot())
        }
    }
}
