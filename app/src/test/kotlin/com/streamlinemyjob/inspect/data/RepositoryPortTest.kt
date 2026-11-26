package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.ExpectedCodes
import com.streamlinemyjob.inspect.testdata.GeneratorError
import com.streamlinemyjob.inspect.testdata.PortDot
import com.streamlinemyjob.inspect.testdata.PortResult
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The repository port through [RepositoryAdapter] (designs/stage-1-foundation.md 4.15, section 6
 * "Repository port"): the adapter passes calls straight through with the time set, and the snapshot
 * comparison and the generator can both fail.
 */
@RunWith(RobolectricTestRunner::class)
class RepositoryPortTest {
    private val random = TestRandom.forTest(RepositoryPortTest::class.java.name)

    private fun seeds() = listOf(ConfirmedSeed.text, SeedGenerator.generate(random, ConfirmedSeed.listNames))

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    /** 4.15's worked examples 1-3, written by hand through the adapter, with the values read from the database. */
    @Test
    fun workedExamplesThroughTheAdapter() {
        val start = 1_795_514_400_000L // 2026-11-24 10:00 UTC
        TestDb(ConfirmedSeed.text).use { t ->
            val port = RepositoryAdapter(t.db)
            port.setTime(start)
            assertEquals(LocalDate.of(2026, 11, 24), port.today())
            val u1 = (port.addVehicle(" ab12-cde ") as PortResult.Added).id
            val reg = Reg.check(" ab12-cde ") as Reg.Ok
            assertEquals(Snapshot.VehicleRow(u1, reg.display, reg.key, start), port.snapshot().vehicles.single())

            port.setTime(start + 1)
            val i1 = (port.addInspection(u1, "12/10/2026", "45,210", false) as PortResult.Added).id
            port.setTime(start + 2)
            assertEquals(
                PortResult.Refused(ExpectedCodes.MILEAGE_LOWER_UNCONFIRMED, "45210 2026-10-12"),
                port.addInspection(u1, "18/11/2026", "41 000", false),
            )
            port.setTime(start + 3)
            val i2 = (port.addInspection(u1, "18/11/2026", "41 000", true) as PortResult.Added).id
            assertEquals(
                listOf(Snapshot.InspectionRow(i1, u1, "2026-10-12", 45210, start + 1), Snapshot.InspectionRow(i2, u1, "2026-11-18", 41000, start + 3))
                    .sortedBy { it.id },
                port.snapshot().inspections,
            )

            val area = t.activeValues(Role.AREA).first()
            val damage = t.activeValues(Role.DAMAGE).first()
            port.setTime(start + 4)
            val f1 = (port.addFinding(i2, area.id, damage.id, actionOf(null), "  Small dent,\n  near   handle ", false) as PortResult.Added).id
            val row = port.snapshot().findings.single()
            assertEquals(f1, row.id)
            // 4.7 keeps one space at the start of the second line. 4.15's example 3 shows "Small dent,\nnear handle";
            // the owner chose 4.7 (S1.9 thread, 2026-11-24), and the example is for the designer to correct.
            assertEquals("Small dent,\n near handle", row.note)
            assertEquals(null, row.actionValueId)
            assertEquals(null, row.actionName)
            assertEquals(start + 4, row.createdAt)
            assertEquals(start + 4, row.updatedAt)
            assertEquals(area.name, row.areaName)
            assertEquals(damage.name, row.damageName)
            assertEquals(t.lists().single { it.id == area.listId }.name, row.areaListName)
        }
    }

    /** The generator's expected snapshot equals the one read back, for a smaller N on both seeds. */
    @Test
    fun generatedMatchesReadBack() {
        for (seed in seeds()) {
            TestDb(seed).use { t ->
                val port = RepositoryAdapter(t.db)
                val expected = DataGenerator.generate(random, port, findings = 60, start = now())
                Snapshot.assertSame(expected, port.snapshot())
                assertEquals(60, expected.findings.size)
                assertEquals(emptyList<Violation>(), t.check())
            }
        }
    }

    /** Proof (1): a port that drops one finding's note makes the comparison fail, naming that finding. */
    @Test
    fun comparisonFailsWhenTheNoteOfOneFindingIsDropped() {
        TestDb(ConfirmedSeed.text).use { t ->
            var target: String? = null
            var droppedId: String? = null
            val port = object : PassThroughPort(RepositoryAdapter(t.db)) {
                override fun addFinding(
                    inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
                    rawNote: String?, confirmedSameArea: Boolean, dot: PortDot?,
                ): PortResult {
                    if (droppedId == null && target == null && Text.checkNote(rawNote).cleaned != null) target = rawNote
                    val drop = droppedId == null && target != null && rawNote == target
                    val r = super.addFinding(inspectionId, areaValueId, damageValueId, action, if (drop) null else rawNote, confirmedSameArea, dot)
                    if (drop && r is PortResult.Added) droppedId = r.id
                    return r
                }
            }
            val expected = DataGenerator.generate(random, port, findings = 60, start = now())
            assertNotNull("no note was dropped", droppedId)
            val id = droppedId!!
            val e = assertThrows(AssertionError::class.java) { Snapshot.assertSame(expected, port.snapshot()) }
            assertTrue(e.message!!, e.message!!.lines().any { it.startsWith("finding $id:") })
        }
    }

    /** Proof (2a): a port that never refuses stops the generator at the first lower mileage. */
    @Test
    fun generatorStopsWhenThePortNeverRefuses() {
        TestDb(ConfirmedSeed.text).use { t ->
            var n = 0
            val port = object : PassThroughPort(RepositoryAdapter(t.db)) {
                private fun never(r: PortResult) = if (r is PortResult.Refused) PortResult.Added("never-refused-${n++}") else r

                override fun addVehicle(rawReg: String) = never(super.addVehicle(rawReg))

                override fun addInspection(vehicleId: String, rawDate: String, rawMileage: String, confirmedLower: Boolean) =
                    never(super.addInspection(vehicleId, rawDate, rawMileage, confirmedLower))

                override fun addFinding(
                    inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
                    rawNote: String?, confirmedSameArea: Boolean, dot: PortDot?,
                ) = never(super.addFinding(inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot))
            }
            val e = assertThrows(GeneratorError::class.java) { DataGenerator.generate(random, port, findings = 100, start = now()) }
            assertTrue(e.message!!, e.message!!.contains(ExpectedCodes.MILEAGE_LOWER_UNCONFIRMED))
            assertTrue(e.message!!, e.message!!.contains("got Added(never-refused-0)"))
        }
    }

    /** Proof (2b): a port that refuses one ordinary finding stops the generator, naming that finding. */
    @Test
    fun generatorStopsWhenOneOrdinaryFindingIsRefused() {
        TestDb(ConfirmedSeed.text).use { t ->
            var countdown = random.nextInt(30)
            var refusedInspection: String? = null
            val port = object : PassThroughPort(RepositoryAdapter(t.db)) {
                override fun addFinding(
                    inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
                    rawNote: String?, confirmedSameArea: Boolean, dot: PortDot?,
                ): PortResult {
                    val r = super.addFinding(inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot)
                    // Only an ordinary finding: sent unconfirmed and added by the real Repository.
                    if (refusedInspection == null && !confirmedSameArea && r is PortResult.Added && countdown-- == 0) {
                        refusedInspection = inspectionId
                        return PortResult.Refused("TEST_REFUSED", "planted by the test")
                    }
                    return r
                }
            }
            val e = assertThrows(GeneratorError::class.java) { DataGenerator.generate(random, port, findings = 60, start = now()) }
            val msg = e.message!!
            assertTrue(msg, msg.startsWith("finding "))
            assertTrue(msg, msg.contains("inspection id $refusedInspection"))
            assertTrue(msg, msg.contains("expected Added, got Refused(TEST_REFUSED planted by the test)"))
        }
    }

    /** Proof (3): each `ExpectedCodes` constant equals its `RepoCodes` constant. */
    @Test
    fun expectedCodesEqualRepoCodes() {
        assertEquals(RepoCodes.MILEAGE_LOWER_UNCONFIRMED, ExpectedCodes.MILEAGE_LOWER_UNCONFIRMED)
        assertEquals(RepoCodes.SAME_AREA_UNCONFIRMED, ExpectedCodes.SAME_AREA_UNCONFIRMED)
    }

    /** The adapter's clock gives exactly the time last set, on every row, and does not tick. */
    @Test
    fun adapterUsesTheTimeSet() {
        TestDb(ConfirmedSeed.text).use { t ->
            val port = RepositoryAdapter(t.db)
            val at = now().toEpochMilli()
            port.setTime(at)
            val v1 = (port.addVehicle("AB12 CDE") as PortResult.Added).id
            val v2 = (port.addVehicle("XYZ 12") as PortResult.Added).id
            assertEquals(listOf(at, at), port.snapshot().vehicles.filter { it.id in setOf(v1, v2) }.map { it.createdAt })
        }
    }
}
