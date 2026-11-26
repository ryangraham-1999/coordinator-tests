package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.random.asKotlinRandom

/**
 * S1.8 Repository writes (designs/stage-1-foundation.md 4.12, without list editing). Every refused
 * write is checked to leave the snapshot unchanged; `DataCheck` is clean after each sequence.
 * Runs on the confirmed seed and on a generated one.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    private val random = TestRandom.forTest(RepositoryTest::class.java.name)

    private fun seeds() = listOf(ConfirmedSeed.text, SeedGenerator.generate(random, ConfirmedSeed.listNames))

    private fun TestDb.refusedUnchanged(expectedCode: String, write: () -> Result<*>): Refused {
        val before = snapshot()
        val r = write()
        assertTrue("expected $expectedCode, got $r", r is Refused && r.code == expectedCode)
        Snapshot.assertSame(before, snapshot(), "refused $expectedCode changed the database")
        return r as Refused
    }

    private fun TestDb.today(): LocalDate = LocalDate.now(clock)

    private fun TestDb.newInspection(): String {
        val v = (repo.addVehicle(RegGenerator(random).next().typed) as Ok).value
        return (repo.addInspection(v, Dates.format(today()), "1000") as Ok).value
    }

    // ---- Findings ----

    @Test
    fun eachRefusedFindingWriteLeavesTheSnapshotUnchanged() {
        for (seed in seeds()) TestDb(seed).use { t ->
            populate(t, random, vehicles = 2)
            val inspection = t.newInspection()
            val area = t.activeValues(Role.AREA).random(random.asKotlinRandom())
            val damage = t.activeValues(Role.DAMAGE).random(random.asKotlinRandom())
            val action = t.activeValues(Role.ACTION).randomOrNull(random.asKotlinRandom())

            // ROLE_MISMATCH: each place given a value of another role, or an id that is no value.
            assertEquals("area", t.refusedUnchanged(RepoCodes.ROLE_MISMATCH) { t.repo.addFinding(inspection, damage.id, damage.id, actionOf(null), null, true) }.detail)
            assertEquals("damage", t.refusedUnchanged(RepoCodes.ROLE_MISMATCH) { t.repo.addFinding(inspection, area.id, area.id, actionOf(null), null, true) }.detail)
            assertEquals("action", t.refusedUnchanged(RepoCodes.ROLE_MISMATCH) { t.repo.addFinding(inspection, area.id, damage.id, actionOf(damage.id), null, true) }.detail)
            assertEquals("area", t.refusedUnchanged(RepoCodes.ROLE_MISMATCH) { t.repo.addFinding(inspection, "no-such-value", damage.id, actionOf(null), null, true) }.detail)

            // NOTE_TOO_LONG: 501 code points (non-BMP, so 1,002 UTF-16 units); 500 is fine.
            t.refusedUnchanged(Codes.NOTE_TOO_LONG) { t.repo.addFinding(inspection, area.id, damage.id, actionOf(action?.id), "🚗".repeat(Rules.NOTE_MAX + 1), true) }
            val first = (t.repo.addFinding(inspection, area.id, damage.id, actionOf(action?.id), "🚗".repeat(Rules.NOTE_MAX), false) as Ok).value

            // SAME_AREA_UNCONFIRMED names the existing finding; confirmed, it saves.
            val same = t.refusedUnchanged(RepoCodes.SAME_AREA_UNCONFIRMED) { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, false) }
            assertEquals(first, same.detail)
            val second = (t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), "  ", true) as Ok).value
            assertNull(t.repo.finding(second)!!.note)

            // VALUE_REMOVED via a raw-SQL-removed value, for each place; and via a raw-SQL-removed area list.
            val removed = listOfNotNull(area, damage, action)
            removed.forEach { t.exec("UPDATE tag_value SET removed = 1 WHERE id = ?", it.id) }
            val area2 = t.activeValues(Role.AREA).firstOrNull()
            val damage2 = t.activeValues(Role.DAMAGE).firstOrNull()
            if (damage2 != null) {
                assertEquals(area.id, t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.addFinding(inspection, area.id, damage2.id, actionOf(null), null, true) }.detail)
            }
            if (area2 != null) {
                assertEquals(damage.id, t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.addFinding(inspection, area2.id, damage.id, actionOf(null), null, true) }.detail)
                if (action != null && damage2 != null) {
                    assertEquals(action.id, t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.addFinding(inspection, area2.id, damage2.id, actionOf(action.id), null, true) }.detail)
                }
            }
            removed.forEach { t.exec("UPDATE tag_value SET removed = 0 WHERE id = ?", it.id) }
            val areaList = t.db.tagDao().getValue(area.id)!!.listId
            t.exec("UPDATE tag_list SET removed = 1 WHERE id = ?", areaList)
            assertEquals(area.id, t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, true) }.detail)
            t.exec("UPDATE tag_list SET removed = 0 WHERE id = ?", areaList)

            // Edits: the same rules; a finding may keep its own removed value, not change to another removed one.
            t.refusedUnchanged(Codes.NOTE_TOO_LONG) { t.repo.editFinding(first, area.id, damage.id, actionOf(action?.id), "x".repeat(Rules.NOTE_MAX + 1), true) }
            t.refusedUnchanged(RepoCodes.ROLE_MISMATCH) { t.repo.editFinding(first, area.id, area.id, actionOf(null), null, true) }
            t.refusedUnchanged(RepoCodes.SAME_AREA_UNCONFIRMED) { t.repo.editFinding(first, area.id, damage.id, actionOf(null), "edited", false) }
            t.exec("UPDATE tag_value SET removed = 1 WHERE id = ?", damage.id)
            assertTrue(t.repo.editFinding(first, area.id, damage.id, actionOf(null), "keeps its removed damage", true) is Ok)
            val otherDamage = t.activeValues(Role.DAMAGE).firstOrNull()
            if (otherDamage != null) {
                t.exec("UPDATE tag_value SET removed = 1 WHERE id = ?", otherDamage.id)
                t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.editFinding(second, area.id, otherDamage.id, actionOf(null), null, true) }
                t.exec("UPDATE tag_value SET removed = 0 WHERE id = ?", otherDamage.id)
            }
            t.exec("UPDATE tag_value SET removed = 0 WHERE id = ?", damage.id)
            val edited = t.repo.finding(first)!!
            assertEquals("keeps its removed damage", edited.note)
            assertNull(edited.actionValueId)
            assertTrue(edited.updatedAt > edited.createdAt)

            assertEquals(Ok(second), t.repo.deleteFinding(second))
            assertNull(t.repo.finding(second))
            t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.deleteFinding(second) }
            assertEquals(listOf(first), t.repo.findingsForInspection(inspection).map { it.id })
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    /** 4.29 worked examples 1, 2 and 4: the three action states are stored, shown and moved between. */
    @Test
    fun theThreeActionStates() {
        var generated: String
        do {
            generated = SeedGenerator.generate(random, ConfirmedSeed.listNames)
            val parsed = (com.streamlinemyjob.inspect.core.Seed.parse(generated) as com.streamlinemyjob.inspect.core.Seed.Valid).seed
        } while (parsed.lists.none { it.role == Role.ACTION && it.values.isNotEmpty() })
        for (seed in listOf(ConfirmedSeed.text, generated)) TestDb(seed).use { t ->
            val inspection = t.newInspection()
            val area = t.activeValues(Role.AREA).random(random.asKotlinRandom())
            val damage = t.activeValues(Role.DAMAGE).random(random.asKotlinRandom())
            val action = t.activeValues(Role.ACTION).random(random.asKotlinRandom())

            fun stored(id: String): Pair<String?, Boolean> = t.db.findingDao().all().single { it.id == id }.let { it.actionValueId to it.noAction }
            fun shown(id: String): String = FindingText.damageAndAction(t.repo.resolvedFinding(id)!!.view)

            // Example 1: nothing chosen.
            val id = (t.repo.addFinding(inspection, area.id, damage.id, ActionChoice.NotChosen, null, false) as Ok).value
            assertEquals(null to false, stored(id))
            assertEquals("${damage.name} → ${FindingText.NOT_CHOSEN}", shown(id))

            // Example 2: to "No action", to an action, and back to not chosen.
            assertTrue(t.repo.editFinding(id, area.id, damage.id, ActionChoice.NoAction, null, true) is Ok)
            assertEquals(null to true, stored(id))
            assertEquals("${damage.name} → ${FindingText.NO_ACTION}", shown(id))
            assertTrue(t.repo.editFinding(id, area.id, damage.id, ActionChoice.Value(action.id), null, true) is Ok)
            assertEquals(action.id to false, stored(id))
            assertEquals("${damage.name} → ${action.name}", shown(id))
            assertTrue(t.repo.editFinding(id, area.id, damage.id, ActionChoice.NotChosen, null, true) is Ok)
            assertEquals(null to false, stored(id))
            assertEquals("${damage.name} → ${FindingText.NOT_CHOSEN}", shown(id))

            assertEquals(emptyList<Violation>(), t.check())

            // Example 4: an action the owner removes still shows (removed); it can be kept or moved away from.
            // (The removal is a raw update, which leaves a gap in the positions, so the check is not run after it.)
            assertTrue(t.repo.editFinding(id, area.id, damage.id, ActionChoice.Value(action.id), null, true) is Ok)
            t.exec("UPDATE tag_value SET removed = 1 WHERE id = ?", action.id)
            assertEquals("${damage.name} → ${action.name}${FindingText.REMOVED}", shown(id))
            assertTrue(t.repo.editFinding(id, area.id, damage.id, ActionChoice.Value(action.id), "kept", true) is Ok)
            assertEquals(action.id to false, stored(id))
            assertTrue(t.repo.editFinding(id, area.id, damage.id, ActionChoice.NotChosen, "kept", true) is Ok)
            assertEquals(null to false, stored(id))
        }
    }

    @Test
    fun aNewFindingStoredAsNoActionIsNeverTheNotChosenState() {
        TestDb(ConfirmedSeed.text).use { t ->
            val inspection = t.newInspection()
            val area = t.activeValues(Role.AREA).first()
            val damage = t.activeValues(Role.DAMAGE).first()
            val id = (t.repo.addFinding(inspection, area.id, damage.id, ActionChoice.NoAction, null, false) as Ok).value
            val row = t.db.findingDao().all().single { it.id == id }
            assertNull(row.actionValueId)
            assertTrue(row.noAction)
        }
    }

    @Test
    fun aNoteIsCleanedAndKeepsItsNewlines() {
        TestDb(ConfirmedSeed.text).use { t ->
            val inspection = t.newInspection()
            val id = (t.repo.addFinding(inspection, t.activeValues(Role.AREA)[0].id, t.activeValues(Role.DAMAGE)[0].id, actionOf(null), "  Two\t\tspaces  \nsecond  line ", false) as Ok).value
            // 4.7: runs of spaces and tabs within a line become one space; only the whole note is trimmed.
            assertEquals("Two spaces \nsecond line", t.repo.finding(id)!!.note)
        }
    }

    // ---- Vehicles ----

    @Test
    fun vehicleRegRules() {
        TestDb(ConfirmedSeed.text).use { t ->
            val a = (t.repo.addVehicle("ab12 cde") as Ok).value
            assertEquals("AB12 CDE", t.repo.vehicle(a)!!.regDisplay)
            assertEquals("AB12CDE", t.repo.vehicle(a)!!.regKey)
            assertEquals(a, t.refusedUnchanged(Codes.REG_TAKEN) { t.repo.addVehicle("AB12CDE") }.detail)
            t.refusedUnchanged(Codes.REG_TOO_SHORT) { t.repo.addVehicle("X") }
            t.refusedUnchanged(Codes.REG_BAD_CHAR) { t.repo.addVehicle("AB12 CDÉ") }
            val b = (t.repo.addVehicle("  xyz   1234 ") as Ok).value
            // Renaming to its own key with other spacing is fine; to another vehicle's key is REG_TAKEN.
            assertEquals(Ok(a), t.repo.renameVehicle(a, "AB-12 CDE"))
            assertEquals("AB-12 CDE", t.repo.vehicle(a)!!.regDisplay)
            assertEquals(b, t.refusedUnchanged(Codes.REG_TAKEN) { t.repo.renameVehicle(a, "xyz1234") }.detail)
            t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.renameVehicle("no-such-vehicle", "A 1") }
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    @Test
    fun searchFindsByPartOfTheKeyAndSortsByLatestInspection() {
        TestDb(ConfirmedSeed.text).use { t ->
            val today = t.today()
            val regs = RegGenerator(random)
            val made = List(8) { regs.next() }.map { g -> (t.repo.addVehicle(g.typed) as Ok).value to g }
            // Half get inspections on random past dates (some on the same date).
            val latest = HashMap<String, LocalDate>()
            made.take(4).forEach { (id, _) ->
                repeat(1 + random.nextInt(3)) {
                    val d = today.minusDays(random.nextInt(4).toLong() * 100)
                    t.repo.addInspection(id, Dates.format(d), "${random.nextInt(1000)}", confirmedLower = true) as Ok
                    latest.merge(id, d) { x, y -> maxOf(x, y) }
                }
            }
            val all = t.db.vehicleDao().all()
            val expected = all.filter { it.id in latest }.sortedWith(compareByDescending<Vehicle> { latest[it.id] }.thenBy { it.regKey }) +
                all.filter { it.id !in latest }.sortedWith(compareByDescending<Vehicle> { it.createdAt }.thenBy { it.regKey })
            assertEquals(expected.map { it.id }, t.repo.searchVehicles("").map { it.id })
            for ((id, g) in made) {
                val key = g.key
                val part = key.substring(random.nextInt(key.length - 1)).let { it.substring(0, 1 + random.nextInt(it.length)) }
                assertTrue("$part finds ${g.key}", t.repo.searchVehicles(part.lowercase()).any { it.id == id })
                t.repo.searchVehicles(part).forEach { assertTrue(it.regKey.contains(part)) }
            }
            assertEquals(emptyList<Vehicle>(), t.repo.searchVehicles("ab!"))
        }
    }

    // ---- Inspections ----

    @Test
    fun theLowerMileageExamplesOf49() {
        TestDb(ConfirmedSeed.text).use { t ->
            val today = t.today()
            val v = (t.repo.addVehicle("AB12 CDE") as Ok).value
            val prevDate = today.minusDays(37)
            assertEquals(null, (t.repo.addInspection(v, Dates.format(prevDate), "45,210") as Ok).warning)
            // Same mileage later: no warning. Lower without the flag: refused, nothing saved. With it: saved, warning as data.
            val equal = t.repo.addInspection(v, Dates.format(today), "45210") as Ok
            assertNull(equal.warning)
            assertEquals(Ok(equal.value), t.repo.deleteInspection(equal.value))
            val refused = t.refusedUnchanged(RepoCodes.MILEAGE_LOWER_UNCONFIRMED) { t.repo.addInspection(v, Dates.format(today), "41,000") }
            assertEquals("45210 ${Dates.toStored(prevDate)}", refused.detail)
            val lower = t.repo.addInspection(v, Dates.format(today), "41,000", confirmedLower = true) as Ok
            assertEquals(PreviousMileage(45210, prevDate), lower.warning)
            // A back-dated older inspection at 30,000: its previous is whatever came before it (nothing here).
            val older = t.repo.addInspection(v, Dates.format(prevDate.minusDays(41)), "30,000") as Ok
            assertNull(older.warning)
            // Editing the latest to a lower mileage again needs the flag; its own row is left out.
            t.refusedUnchanged(RepoCodes.MILEAGE_LOWER_UNCONFIRMED) { t.repo.editInspection(lower.value, Dates.format(today), "100") }
            assertTrue(t.repo.editInspection(lower.value, Dates.format(today), "50,000") is Ok)
            // Same date: the earlier-created one is the previous.
            val sameDay = t.repo.addInspection(v, Dates.format(today), "49,999")
            assertEquals(Refused(RepoCodes.MILEAGE_LOWER_UNCONFIRMED, "50000 ${Dates.toStored(today)}"), sameDay)
            assertEquals(
                listOf(today, prevDate, prevDate.minusDays(41)).map { Dates.toStored(it) },
                t.repo.inspectionsForVehicle(v).map { it.date },
            )
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    @Test
    fun refusedInspectionInputsChangeNothing() {
        TestDb(ConfirmedSeed.text).use { t ->
            val today = t.today()
            val v = (t.repo.addVehicle("A 1") as Ok).value
            t.refusedUnchanged(Codes.DATE_BAD) { t.repo.addInspection(v, "31/02/2026", "1") }
            t.refusedUnchanged(Codes.DATE_OUT_OF_RANGE) { t.repo.addInspection(v, Dates.format(today.plusDays(1)), "1") }
            t.refusedUnchanged(Codes.MILEAGE_EMPTY) { t.repo.addInspection(v, Dates.format(today), " ") }
            t.refusedUnchanged(Codes.MILEAGE_BAD) { t.repo.addInspection(v, Dates.format(today), "12a") }
            t.refusedUnchanged(Codes.MILEAGE_TOO_BIG) { t.repo.addInspection(v, Dates.format(today), "1,000,000") }
            t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.addInspection("no-such-vehicle", Dates.format(today), "1") }
            val zero = (t.repo.addInspection(v, Dates.format(Rules.DATE_MIN), "0") as Ok).value
            val max = (t.repo.addInspection(v, Dates.format(today), "999,999") as Ok).value
            assertEquals(0, t.repo.inspection(zero)!!.mileage)
            assertEquals(999_999, t.repo.inspection(max)!!.mileage)
            t.refusedUnchanged(Codes.DATE_BAD) { t.repo.editInspection(max, "2026-01-01", "1") }
            assertEquals(emptyList<Violation>(), t.check())
        }
    }
}
