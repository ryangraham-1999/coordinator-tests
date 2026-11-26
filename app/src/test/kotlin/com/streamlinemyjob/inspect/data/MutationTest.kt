package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.GeneratorError
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.PortResult
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * S1.12 `Mutation` (designs/stage-1-foundation.md 4.15): every list renamed to a new name, ten more
 * active values in every list, two more area lists of five values each, checked through the snapshot
 * with `DataCheck` clean and every existing finding still resolving to names. Also the new port calls
 * on their own, and that an unexpected refusal stops the run.
 */
@RunWith(RobolectricTestRunner::class)
class MutationTest {
    private val random = TestRandom.forTest(MutationTest::class.java.name)

    private fun mutate(seedText: String, findings: Int) = TestDb(seedText).use { t ->
        val port = RepositoryAdapter(t.db)
        DataGenerator.generate(random, port, findings, Instant.now().truncatedTo(ChronoUnit.MILLIS))
        val before = port.snapshot()
        Mutation.apply(random, port)
        val after = port.snapshot()
        assertEquals(emptyList<Violation>(), t.check())
        check(before, after)
    }

    private fun check(before: Snapshot, after: Snapshot) {
        val oldLists = before.lists.associateBy { it.id }
        val afterLists = after.lists.associateBy { it.id }
        val oldKeys = before.lists.map { it.nameKey }.toSet()

        // Every list is still there, same role and position and removed flag, under a new name.
        for (old in before.lists) {
            val now = afterLists.getValue(old.id)
            assertEquals(old.role, now.role)
            assertEquals(old.position, now.position)
            assertEquals(old.removed, now.removed)
            assertTrue("list ${old.id} was not renamed", now.nameKey !in oldKeys)
            assertEquals("the new name is stored cleaned", Text.clean(now.name), now.name)
            assertEquals(Text.key(now.name), now.nameKey)
        }
        // Two new area lists, at the end, active, with five values each.
        val added = after.lists.filter { it.id !in oldLists }.sortedBy { it.position }
        assertEquals(2, added.size)
        val oldActiveAreas = before.lists.count { it.role == Role.AREA && !it.removed }
        added.forEachIndexed { i, l ->
            assertEquals(Role.AREA, l.role)
            assertFalse(l.removed)
            assertEquals(oldActiveAreas + i, l.position)
            val values = after.values.filter { it.listId == l.id }.sortedBy { it.position }
            assertEquals(5, values.size)
            assertTrue(values.none { it.removed })
            assertEquals((0 until 5).toList(), values.map { it.position })
        }
        assertEquals(before.lists.size + 2, after.lists.size)
        assertEquals("every list name key is unique", after.lists.size, after.lists.map { it.nameKey }.toSet().size)

        // Every old value is untouched; each old list has exactly ten more, active, after its old active values.
        val afterValues = after.values.associateBy { it.id }
        for (old in before.values) assertEquals(old, afterValues.getValue(old.id))
        val oldIds = before.values.map { it.id }.toSet()
        for (list in before.lists) {
            val oldActive = before.values.count { it.listId == list.id && !it.removed }
            val extra = after.values.filter { it.listId == list.id && it.id !in oldIds }.sortedBy { it.position }
            assertEquals("list ${list.id}", 10, extra.size)
            assertTrue(extra.none { it.removed })
            assertEquals((oldActive until oldActive + 10).toList(), extra.map { it.position })
            for (v in extra) assertEquals(Text.clean(v.name), v.name)
        }
        assertEquals(before.values.size + 10 * before.lists.size + 10, after.values.size)

        // Findings: the same rows, still resolving to names; only the area list's name has followed its rename.
        assertEquals(before.findings.size, after.findings.size)
        val nowByIdValue = after.findings.associateBy { it.id }
        for (f in before.findings) {
            val expectedListName = afterLists.getValue(before.values.first { it.id == f.areaValueId }.listId).name
            assertEquals(f.copy(areaListName = expectedListName), nowByIdValue.getValue(f.id))
            assertTrue(nowByIdValue.getValue(f.id).areaName != null && nowByIdValue.getValue(f.id).damageName != null)
        }
    }

    @Test
    fun mutatedListsOnTheConfirmedSeed() = mutate(ConfirmedSeed.text, findings = 60)

    @Test
    fun mutatedListsOnGeneratedSeeds() {
        repeat(3) { mutate(SeedGenerator.generate(random, ConfirmedSeed.listNames), findings = 60) }
        mutate(SeedGenerator.generate(random, ConfirmedSeed.listNames, SeedGenerator.Extreme.EIGHT_AREA_LISTS), findings = 40)
    }

    @Test
    fun mutatedListsOnAnEmptyDatabase() = mutate(ConfirmedSeed.text, findings = 0)

    @Test
    fun anUnexpectedRefusalStopsTheRun() {
        TestDb(ConfirmedSeed.text).use { t ->
            val refusing = object : PassThroughPort(RepositoryAdapter(t.db)) {
                override fun addValue(listId: String, rawName: String): PortResult = PortResult.Refused("NAME_TAKEN", "x")
            }
            try {
                Mutation.apply(random, refusing)
                fail("expected a GeneratorError")
            } catch (e: GeneratorError) {
                assertTrue(e.message!!, e.message!!.contains("expected Added, got Refused(NAME_TAKEN x)"))
            }
        }
    }

    @Test
    fun theNewPortCallsPassTheRepositorysAnswerStraightThrough() {
        TestDb(ConfirmedSeed.text).use { t ->
            val port = RepositoryAdapter(t.db)
            val snapshot = port.snapshot()
            val area = snapshot.lists.first { it.role == Role.AREA }
            val other = snapshot.lists.first { it.id != area.id }
            // renameList: refused with the repository's code and detail, then made.
            assertEquals(PortResult.Refused(Codes.NAME_TAKEN, other.id), port.renameList(area.id, other.name))
            assertEquals(PortResult.Added(area.id), port.renameList(area.id, "Renamed list"))
            // addValue: made, taken, and a removed one brought back counts as Added with its id.
            val id = (port.addValue(area.id, "Port value") as PortResult.Added).id
            assertEquals(PortResult.Refused(Codes.NAME_TAKEN, id), port.addValue(area.id, "port value"))
            t.made { t.repo.removeValue(id) }
            assertEquals(PortResult.Added(id), port.addValue(area.id, "PORT VALUE"))
            // addAreaList: made, and refused when the name is another kind of list's.
            val newList = (port.addAreaList("Port list") as PortResult.Added).id
            assertEquals(Role.AREA, port.snapshot().lists.single { it.id == newList }.role)
            val damage = snapshot.lists.first { it.role == Role.DAMAGE }
            assertEquals(PortResult.Refused(Codes.NAME_TAKEN, damage.id), port.addAreaList(damage.name))
            assertEquals(emptyList<Violation>(), t.check())
        }
    }
}
