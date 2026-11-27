package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S1.8 proof that the integrity check can fail (designs/stage-1-foundation.md 4.14): one test per
 * `IC_*` code plants exactly that fault with raw SQL (foreign keys off for the orphan) into an
 * otherwise clean generated database and asserts that code, and only that code, is reported.
 */
@RunWith(RobolectricTestRunner::class)
class DataCheckTest {
    private val random = TestRandom.forTest(DataCheckTest::class.java.name)

    /** A clean generated database: a generated seed with at least one action value, filled through the repository. */
    private fun cleanDb(): TestDb {
        var seed: String
        do {
            seed = SeedGenerator.generate(random, ConfirmedSeed.listNames)
            val parsed = (com.streamlinemyjob.inspect.core.Seed.parse(seed) as com.streamlinemyjob.inspect.core.Seed.Valid).seed
        } while (parsed.lists.none { it.role == Role.ACTION && it.values.isNotEmpty() })
        val t = TestDb(seed)
        populate(t, random)
        assertEquals("clean before planting", emptyList<Violation>(), t.check())
        return t
    }

    private fun plantedAlone(code: String, plant: (TestDb) -> Unit) {
        cleanDb().use { t ->
            plant(t)
            val found = t.check()
            assertTrue("$code: nothing reported", found.isNotEmpty())
            assertEquals("$code alone, got $found", setOf(code), found.map { it.code }.toSet())
        }
    }

    private val plants: Map<String, (TestDb) -> Unit> = mapOf(
        DataCheck.IC_ROLE_COUNT to { t ->
            // A second ACTION list, in a clean position, with a clean unique name.
            t.exec(
                "INSERT INTO tag_list (id, role, name, name_key, position, removed) VALUES (?, 'ACTION', ?, ?, 1, 0)",
                "planted-list", "Zq planted 7731", "zq planted 7731",
            )
        },
        DataCheck.IC_FINDING_ROLE to { t ->
            val f = t.db.findingDao().all().first()
            t.exec("UPDATE finding SET damage_value_id = ? WHERE id = ?", f.areaValueId, f.id)
        },
        DataCheck.IC_ORPHAN to { t ->
            val vehicleId = t.db.inspectionDao().all().first().vehicleId
            t.exec("PRAGMA foreign_keys = OFF")
            t.exec("DELETE FROM vehicle WHERE id = ?", vehicleId)
            t.exec("PRAGMA foreign_keys = ON")
        },
        DataCheck.IC_NAME_KEY to { t ->
            val v = t.values().first()
            t.exec("UPDATE tag_value SET name_key = ? WHERE id = ?", v.nameKey + " x9planted", v.id)
        },
        DataCheck.IC_NAME_DUP to { t ->
            // The UNIQUE index would stop it, so it goes first; the copy takes the next free position.
            val v = t.values().first { !it.removed }
            val active = t.values().count { it.listId == v.listId && !it.removed }
            t.exec("DROP INDEX index_tag_value_list_id_name_key")
            t.exec(
                "INSERT INTO tag_value (id, list_id, name, name_key, position, removed) VALUES (?, ?, ?, ?, ?, 0)",
                "planted-dup", v.listId, v.name, v.nameKey, active,
            )
        },
        DataCheck.IC_POSITIONS to { t ->
            val v = t.values().first { !it.removed }
            t.exec("UPDATE tag_value SET position = ? WHERE id = ?", v.position + 1000, v.id)
        },
        DataCheck.IC_REG to { t ->
            val v = t.db.vehicleDao().all().first()
            t.exec("UPDATE vehicle SET reg_key = ? WHERE id = ?", v.regKey + "#", v.id)
        },
        DataCheck.IC_INSPECTION to { t ->
            t.exec("UPDATE inspection SET mileage = 1000000 WHERE id = ?", t.db.inspectionDao().all().first().id)
        },
        DataCheck.IC_NOTE to { t ->
            t.exec("UPDATE finding SET note = '' WHERE id = ?", t.db.findingDao().all().first().id)
        },
        DataCheck.IC_AT_LEAST_ONE to { t ->
            val damage = t.lists().single { it.role == Role.DAMAGE }
            t.exec("UPDATE tag_value SET removed = 1 WHERE list_id = ?", damage.id)
        },
        DataCheck.IC_ACTION_STATE to { t ->
            // "No action" set on a finding that still has an action value (4.29).
            val f = t.db.findingDao().all().first { it.actionValueId != null }
            t.exec("UPDATE finding SET no_action = 1 WHERE id = ?", f.id)
        },
        DataCheck.IC_DOT to { t ->
            // Half a dot (4.14): x set, y and the drawing id not.
            setDot(t, 0.5, null, null)
        },
        DataCheck.IC_REGION_LINK to { t ->
            // A region of the drawing with no row.
            t.exec("DELETE FROM region_link WHERE region_id = ?", ConfirmedDrawing.drawing.regions.first().id)
        },
    )

    /** A point of the frame (fractions) that is on no region: the gap between parts, found with `Hit`. */
    private fun pointOnNoRegion(): Pair<Double, Double> {
        val d = ConfirmedDrawing.drawing
        for (i in 0..100) for (j in 0..100) {
            if (com.streamlinemyjob.inspect.core.Hit.resolveDot(d, i / 100.0, j / 100.0) == null) return i / 100.0 to j / 100.0
        }
        error("the drawing has no free point")
    }

    private fun setDot(t: TestDb, x: Double?, y: Double?, drawing: String?) {
        t.exec("UPDATE finding SET dot_x = ?, dot_y = ?, dot_drawing = ? WHERE id = ?", x, y, drawing, t.db.findingDao().all().first().id)
    }

    private fun aRegionPoint(): Pair<Double, Double> {
        val d = ConfirmedDrawing.drawing
        val p = com.streamlinemyjob.inspect.testdata.PointSampler.inside(random, d, d.regions.first().id)
        return p.x / d.width to p.y / d.height
    }

    @Test
    fun thereIsAPlantForEveryCode() = assertEquals(
        // The photo codes compare rows with the photo folder; `PhotoDataTest` plants those (stage 2 design 4.6).
        DataCheck.ALL.toSet() - DataCheck.PHOTO_CODES.toSet(), plants.keys,
    )

    @Test fun roleCount() = plantedAlone(DataCheck.IC_ROLE_COUNT, plants.getValue(DataCheck.IC_ROLE_COUNT))
    @Test fun findingRole() = plantedAlone(DataCheck.IC_FINDING_ROLE, plants.getValue(DataCheck.IC_FINDING_ROLE))
    @Test fun orphan() = plantedAlone(DataCheck.IC_ORPHAN, plants.getValue(DataCheck.IC_ORPHAN))
    @Test fun nameKey() = plantedAlone(DataCheck.IC_NAME_KEY, plants.getValue(DataCheck.IC_NAME_KEY))
    @Test fun nameDup() = plantedAlone(DataCheck.IC_NAME_DUP, plants.getValue(DataCheck.IC_NAME_DUP))
    @Test fun positions() = plantedAlone(DataCheck.IC_POSITIONS, plants.getValue(DataCheck.IC_POSITIONS))
    @Test fun reg() = plantedAlone(DataCheck.IC_REG, plants.getValue(DataCheck.IC_REG))
    @Test fun inspection() = plantedAlone(DataCheck.IC_INSPECTION, plants.getValue(DataCheck.IC_INSPECTION))
    @Test fun note() = plantedAlone(DataCheck.IC_NOTE, plants.getValue(DataCheck.IC_NOTE))
    @Test fun atLeastOne() = plantedAlone(DataCheck.IC_AT_LEAST_ONE, plants.getValue(DataCheck.IC_AT_LEAST_ONE))
    @Test fun actionState() = plantedAlone(DataCheck.IC_ACTION_STATE, plants.getValue(DataCheck.IC_ACTION_STATE))

    @Test fun dot() = plantedAlone(DataCheck.IC_DOT, plants.getValue(DataCheck.IC_DOT))
    @Test fun regionLink() = plantedAlone(DataCheck.IC_REGION_LINK, plants.getValue(DataCheck.IC_REGION_LINK))

    @Test
    fun theOtherFormsOfDotAndRegionLinkAreCaughtToo() {
        val id = ConfirmedDrawing.drawing.id
        val (rx, ry) = aRegionPoint()
        val (gx, gy) = pointOnNoRegion()
        plantedAlone(DataCheck.IC_DOT) { t -> setDot(t, null, 0.5, id) }
        plantedAlone(DataCheck.IC_DOT) { t -> setDot(t, 1.01, ry, id) }
        plantedAlone(DataCheck.IC_DOT) { t -> setDot(t, rx, -0.01, id) }
        plantedAlone(DataCheck.IC_DOT) { t -> setDot(t, rx, ry, "some-other-drawing") }
        plantedAlone(DataCheck.IC_DOT) { t -> setDot(t, gx, gy, id) }
        plantedAlone(DataCheck.IC_REGION_LINK) { t ->
            t.exec("INSERT INTO region_link (drawing_id, region_id, value_id) VALUES (?, 'no-such-region', NULL)", id)
        }
        plantedAlone(DataCheck.IC_REGION_LINK) { t ->
            // A value that is not in an AREA list (a damage value).
            val damage = t.activeValues(Role.DAMAGE).first()
            t.exec("UPDATE region_link SET value_id = ? WHERE region_id = ?", damage.id, ConfirmedDrawing.drawing.regions.first().id)
        }
        plantedAlone(DataCheck.IC_REGION_LINK) { t ->
            // A value that is missing (foreign keys off, as the orphan plants do).
            t.exec("PRAGMA foreign_keys = OFF")
            t.exec("UPDATE region_link SET value_id = 'no-such-value' WHERE region_id = ?", ConfirmedDrawing.drawing.regions.first().id)
            t.exec("PRAGMA foreign_keys = ON")
        }
    }

    @Test
    fun aDotOnARegionRelinkedToAnotherValueIsNotAFault() {
        cleanDb().use { t ->
            val d = ConfirmedDrawing.drawing
            val (rx, ry) = aRegionPoint()
            setDot(t, rx, ry, d.id)
            val other = t.activeValues(Role.AREA).first()
            t.exec("UPDATE region_link SET value_id = ?", other.id)
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    @Test
    fun aNoActionFlagThatIsNotZeroOrOneIsCaught() {
        plantedAlone(DataCheck.IC_ACTION_STATE) { t ->
            t.exec("UPDATE finding SET no_action = 2 WHERE id = ?", t.db.findingDao().all().first().id)
        }
    }

    @Test
    fun theOtherFormsOfEachRuleAreCaughtToo() {
        // Further cases from the 4.14 table, each still reported alone.
        plantedAlone(DataCheck.IC_ROLE_COUNT) { t ->
            t.exec("UPDATE tag_list SET removed = 1 WHERE role = 'DAMAGE'")
        }
        plantedAlone(DataCheck.IC_ORPHAN) { t ->
            t.exec("PRAGMA foreign_keys = OFF")
            t.exec("DELETE FROM inspection WHERE id = ?", t.db.findingDao().all().first().inspectionId)
            t.exec("PRAGMA foreign_keys = ON")
        }
        plantedAlone(DataCheck.IC_NAME_KEY) { t ->
            val l = t.lists().first()
            t.exec("UPDATE tag_list SET name = ? WHERE id = ?", " " + l.name, l.id)
        }
        plantedAlone(DataCheck.IC_NAME_DUP) { t ->
            val l = t.lists().first { it.role == Role.AREA }
            val areas = t.lists().count { it.role == Role.AREA && !it.removed }
            t.exec("DROP INDEX index_tag_list_name_key")
            t.exec(
                "INSERT INTO tag_list (id, role, name, name_key, position, removed) VALUES ('planted-dup', 'AREA', ?, ?, ?, 0)",
                l.name, l.nameKey, areas,
            )
        }
        plantedAlone(DataCheck.IC_POSITIONS) { t ->
            t.exec("UPDATE tag_list SET position = 5 WHERE role = 'DAMAGE'")
        }
        plantedAlone(DataCheck.IC_INSPECTION) { t ->
            t.exec("UPDATE inspection SET date = '2026-02-30' WHERE id = ?", t.db.inspectionDao().all().first().id)
        }
        plantedAlone(DataCheck.IC_NOTE) { t ->
            t.exec("UPDATE finding SET note = ? WHERE id = ?", "a".repeat(501), t.db.findingDao().all().first().id)
        }
        plantedAlone(DataCheck.IC_AT_LEAST_ONE) { t ->
            val activeAreaLists = t.lists().filter { it.role == Role.AREA && !it.removed }.map { it.id }
            activeAreaLists.forEach { t.exec("UPDATE tag_value SET removed = 1 WHERE list_id = ?", it) }
        }
    }
}
