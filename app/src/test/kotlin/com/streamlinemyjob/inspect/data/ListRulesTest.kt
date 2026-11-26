package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * S1.12 list editing (designs/stage-1-foundation.md 4.13): the rules and the worked examples one by
 * one, each on the confirmed seed and on a generated one. The examples name the confirmed seed's
 * values; here each picks the same kind of value by role and position, so it holds for any lists.
 * `DataCheck` is clean after every write, and a refused write is checked to leave the snapshot unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class ListRulesTest {
    private val random = TestRandom.forTest(ListRulesTest::class.java.name)

    private fun seeds() = listOf(
        ConfirmedSeed.text,
        SeedGenerator.generate(random, ConfirmedSeed.listNames),
        SeedGenerator.generate(random, ConfirmedSeed.listNames, SeedGenerator.Extreme.ONE_AREA_LIST),
    )

    private fun each(body: (TestDb) -> Unit) {
        for (seed in seeds()) TestDb(seed).use { body(it) }
    }

    private fun TestDb.finding(id: String) = snapshot().findings.single { it.id == id }

    // ---- 4.13 worked example 1: rename a damage value, the finding follows ----

    @Test
    fun example1RenameFollowsOnTheFindingAndInNewOffers() = each { t ->
        val inspection = t.newInspection(random)
        val area = t.firstFilledAreaList().let { t.activeValuesOf(it).first() }
        val damage = t.activeValues(Role.DAMAGE).first()
        val action = t.activeValues(Role.ACTION).firstOrNull()
        val f1 = t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(action?.id), null, false) }.value

        val newName = t.freshValueName(damage.listId, random, "Renamed")
        t.made { t.repo.renameValue(damage.id, newName) }
        assertEquals(newName, t.finding(f1).damageName)
        assertFalse(t.finding(f1).damageRemoved)
        assertTrue(t.repo.offeredValues(Role.DAMAGE).any { it.valueId == damage.id && it.name == newName })
    }

    // ---- Example 2: remove, still shown, not offered, restore by adding the name again ----

    @Test
    fun example2RemoveKeepsTheFindingMarksItAndAddingTheNameRestoresIt() = each { t ->
        t.ensureValues(Role.DAMAGE, 2, random)
        val inspection = t.newInspection(random)
        val area = t.firstFilledAreaList().let { t.activeValuesOf(it).first() }
        val (keep, dent) = t.activeValues(Role.DAMAGE).let { it[0] to it[1] }
        val f2 = t.made { t.repo.addFinding(inspection, area.id, dent.id, actionOf(null), null, false) }.value

        t.made { t.repo.removeValue(dent.id) }
        assertTrue(t.finding(f2).damageRemoved)
        assertEquals(dent.name, t.finding(f2).damageName)
        assertFalse(t.repo.offeredValues(Role.DAMAGE).any { it.valueId == dent.id })
        // Editing it offers the removed value first, marked removed.
        val offered = t.repo.offeredValues(Role.DAMAGE, dent.id)
        assertEquals(dent.id, offered.first().valueId)
        assertTrue(offered.first().valueRemoved)
        assertEquals(listOf(dent.id) + t.activeValues(Role.DAMAGE).map { it.id }, offered.map { it.valueId })
        // Saving with the same removed value is allowed; changing to another and back is not.
        t.made { t.repo.editFinding(f2, area.id, dent.id, actionOf(null), "kept", true) }
        t.made { t.repo.editFinding(f2, area.id, keep.id, actionOf(null), "changed", true) }
        t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.editFinding(f2, area.id, dent.id, actionOf(null), "again", true) }
        // A new finding cannot use it either, asked of the repository directly.
        t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.addFinding(inspection, area.id, dent.id, actionOf(null), null, true) }

        // Adding the same name in another case and with padding restores the one value.
        val before = t.values().size
        val back = t.made { t.repo.addValue(dent.listId, "  ${dent.name.lowercase(Locale.ROOT)}  ") }
        assertTrue(back.restored)
        assertEquals(dent.id, back.value)
        assertEquals(before, t.values().size)
        assertEquals(dent.name, t.values().single { it.id == dent.id }.name)
        assertEquals(dent.id, t.activeValues(Role.DAMAGE).last().id)
        // F2 now uses the other value, so it does not show the restored one; new findings are offered it again.
        assertEquals(keep.name, t.finding(f2).damageName)
        assertFalse(t.finding(f2).damageRemoved)
        assertTrue(t.repo.offeredValues(Role.DAMAGE).any { it.valueId == dent.id && !it.removed })
    }

    // ---- Example 3: remove an area list; last-value refusal; restore goes to the end ----

    @Test
    fun example3RemoveAnAreaListThenTheLastOneIsRefusedAndRestoreGoesToTheEnd() = each { t ->
        val inspection = t.newInspection(random)
        val filled = t.activeLists(Role.AREA).filter { t.activeValuesOf(it).isNotEmpty() }
        val target = filled.last()
        val area = t.activeValuesOf(target).first()
        val damage = t.activeValues(Role.DAMAGE).first()
        val f = t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, false) }.value

        // Removing every other area list works while the target still has values.
        for (other in t.activeLists(Role.AREA).filter { it.id != target.id }) t.made { t.repo.removeAreaList(other.id) }
        assertTrue(t.repo.offeredValues(Role.AREA).all { v -> t.activeValuesOf(target).any { it.id == v.valueId } })
        // The last list with values cannot go.
        t.refusedUnchanged(RepoCodes.LAST_AREA_VALUE) { t.repo.removeAreaList(target.id) }
        // And no value may be removed if it is the only one in the only active area list.
        for (v in t.activeValuesOf(target).drop(1)) t.made { t.repo.removeValue(v.id) }
        t.refusedUnchanged(RepoCodes.LAST_AREA_VALUE) { t.repo.removeValue(area.id) }

        // Bring another back, then remove the target: its finding shows "(removed)" and nothing of it is offered.
        val back = t.lists().firstOrNull { it.role == Role.AREA && it.removed } ?: return@each // one area list only
        t.made { t.repo.restoreAreaList(back.id) }
        assertEquals(back.id, t.activeLists(Role.AREA).last().id)
        assertEquals((0 until t.activeLists(Role.AREA).size).toList(), t.activeLists(Role.AREA).map { it.position })
        if (t.activeValuesOf(back).isNotEmpty()) {
            t.made { t.repo.removeAreaList(target.id) }
            assertTrue(t.finding(f).areaListRemoved)
            assertEquals(area.name, t.finding(f).areaName)
            assertTrue(t.repo.offeredValues(Role.AREA).none { it.valueId == area.id })
            assertTrue(t.repo.offeredValues(Role.AREA, area.id).first().let { it.valueId == area.id && it.listRemoved })
            t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, true) }
            t.made { t.repo.restoreAreaList(target.id) }
            assertEquals(target.id, t.activeLists(Role.AREA).last().id)
            assertFalse(t.finding(f).areaListRemoved)
        }
    }

    // ---- Example 4: a taken name, and a case change of the value itself ----

    @Test
    fun example4TakenNameIsRefusedAndACaseChangeOfItsOwnNameIsAllowed() = each { t ->
        val list = t.firstFilledAreaList()
        val value = t.activeValuesOf(list).first()
        val padded = "  ${value.name.lowercase(Locale.ROOT)} "
        val r = t.refusedUnchanged(Codes.NAME_TAKEN) { t.repo.addValue(list.id, padded) }
        assertEquals(value.id, r.detail)

        // Its own key again, written in lower case: allowed, and the stored name is the cleaned new text.
        val lower = value.name.lowercase(Locale.ROOT)
        val ok = t.made { t.repo.renameValue(value.id, lower) }
        assertEquals(value.id, ok.value)
        assertEquals(Text.clean(lower), t.values().single { it.id == value.id }.name)
    }

    // ---- Example 5: every action may go; the last damage value may not ----

    @Test
    fun example5EveryActionCanBeRemovedButNotTheLastDamageValue() = each { t ->
        for (v in t.activeValues(Role.ACTION)) t.made { t.repo.removeValue(v.id) }
        assertEquals(emptyList<TagValue>(), t.activeValues(Role.ACTION))
        assertEquals(emptyList<OfferedValue>(), t.repo.offeredValues(Role.ACTION))
        // A finding can still be made with no action.
        val inspection = t.newInspection(random)
        val area = t.firstFilledAreaList().let { t.activeValuesOf(it).first() }
        val damage = t.activeValues(Role.DAMAGE).first()
        t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, false) }

        for (v in t.activeValues(Role.DAMAGE).drop(1)) t.made { t.repo.removeValue(v.id) }
        val last = t.activeValues(Role.DAMAGE).single()
        t.refusedUnchanged(RepoCodes.LAST_DAMAGE_VALUE) { t.repo.removeValue(last.id) }
        assertEquals(listOf(0), t.activeValues(Role.DAMAGE).map { it.position })
    }

    // ---- Rules the examples do not reach ----

    @Test
    fun valueNamesFollowTheTextRulesAndNewValuesGoAtTheEnd() = each { t ->
        val list = t.firstFilledAreaList()
        t.refusedUnchanged(Codes.NAME_EMPTY) { t.repo.addValue(list.id, "   ") }
        t.refusedUnchanged(Codes.NAME_TOO_LONG) { t.repo.addValue(list.id, tooLongName) }
        t.refusedUnchanged(Codes.NAME_BAD_CHAR) { t.repo.addValue(list.id, badCharName) }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.addValue("no-such-list", "x") }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.renameValue("no-such-value", "x") }
        t.refusedUnchanged(Codes.NAME_EMPTY) { t.repo.renameValue(t.activeValuesOf(list).first().id, "") }

        val before = t.activeValuesOf(list).size
        val name = t.freshValueName(list.id, random)
        val added = t.made { t.repo.addValue(list.id, "  $name  ") }
        assertFalse(added.restored)
        val row = t.values().single { it.id == added.value }
        assertEquals(name, row.name)
        assertEquals(Text.key(name), row.nameKey)
        assertEquals(before, row.position)
        assertEquals(added.value, t.activeValuesOf(list).last().id)
    }

    @Test
    fun renamingToARemovedValuesNameIsRefusedAndRestoreOfAnActiveValueIsRefused() = each { t ->
        val list = t.ensureValues(Role.DAMAGE, 3, random)
        val (a, b) = t.activeValuesOf(list).let { it[0] to it[1] }
        t.made { t.repo.removeValue(b.id) }
        val r = t.refusedUnchanged(RepoCodes.NAME_TAKEN_BY_REMOVED) { t.repo.renameValue(a.id, b.name.lowercase(Locale.ROOT)) }
        assertEquals(b.id, r.detail)
        t.refusedUnchanged(RepoCodes.NOT_REMOVED) { t.repo.restoreValue(a.id) }
        t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.removeValue(b.id) }
        t.refusedUnchanged(RepoCodes.VALUE_REMOVED) { t.repo.moveValue(b.id, Direction.UP) }

        t.made { t.repo.restoreValue(b.id) }
        assertEquals(b.id, t.activeValuesOf(list).last().id)
        assertEquals(t.activeValuesOf(list).indices.toList(), t.activeValuesOf(list).map { it.position })
    }

    @Test
    fun removingAValueRenumbersTheRestAndMovesSwapNeighbours() = each { t ->
        val list = t.ensureValues(Role.DAMAGE, 4, random)
        val ids = t.activeValuesOf(list).map { it.id }
        t.refusedUnchanged(RepoCodes.MOVE_AT_END) { t.repo.moveValue(ids.first(), Direction.UP) }
        t.refusedUnchanged(RepoCodes.MOVE_AT_END) { t.repo.moveValue(ids.last(), Direction.DOWN) }
        t.made { t.repo.moveValue(ids[1], Direction.UP) }
        assertEquals(listOf(ids[1], ids[0]) + ids.drop(2), t.activeValuesOf(list).map { it.id })
        t.made { t.repo.moveValue(ids[1], Direction.DOWN) }
        assertEquals(ids, t.activeValuesOf(list).map { it.id })

        t.made { t.repo.removeValue(ids[1]) }
        assertEquals(ids - ids[1], t.activeValuesOf(list).map { it.id })
        assertEquals(t.activeValuesOf(list).indices.toList(), t.activeValuesOf(list).map { it.position })
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.moveValue("no-such-value", Direction.UP) }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.removeValue("no-such-value") }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.restoreValue("no-such-value") }
    }

    @Test
    fun listsAreRenamedAddedMovedRemovedAndRestored() = each { t ->
        val existing = t.lists()
        val any = existing.first()
        // Names are unique across all lists, case and padding ignored; its own key is fine.
        val other = existing.first { it.id != any.id }
        val r = t.refusedUnchanged(Codes.NAME_TAKEN) { t.repo.renameList(any.id, "  ${other.name.lowercase(Locale.ROOT)} ") }
        assertEquals(other.id, r.detail)
        t.refusedUnchanged(Codes.NAME_EMPTY) { t.repo.renameList(any.id, "") }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.renameList("no-such-list", "x") }
        t.made { t.repo.renameList(any.id, any.name.lowercase(Locale.ROOT)) }
        val fresh = t.freshListName(random)
        t.made { t.repo.renameList(any.id, fresh) }
        assertEquals(fresh, t.lists().single { it.id == any.id }.name)

        // Add: new AREA list at the end with no values; a taken name (a damage list's too) is refused.
        val areaCount = t.activeLists(Role.AREA).size
        val name = t.freshListName(random)
        val added = t.made { t.repo.addAreaList(name) }
        assertFalse(added.restored)
        assertEquals(areaCount, t.lists().single { it.id == added.value }.position)
        assertEquals(emptyList<TagValue>(), t.values().filter { it.listId == added.value })
        t.refusedUnchanged(Codes.NAME_TAKEN) { t.repo.addAreaList(name.lowercase(Locale.ROOT)) }
        t.refusedUnchanged(Codes.NAME_TAKEN) { t.repo.addAreaList(t.activeLists(Role.DAMAGE).first().name) }
        t.refusedUnchanged(RepoCodes.ROLE_FIXED) { t.repo.addList(Role.DAMAGE, t.freshListName(random)) }
        t.refusedUnchanged(RepoCodes.ROLE_FIXED) { t.repo.addList(Role.ACTION, t.freshListName(random)) }

        // Only area lists can be removed or restored.
        t.refusedUnchanged(RepoCodes.LIST_NOT_REMOVABLE) { t.repo.removeAreaList(t.activeLists(Role.DAMAGE).first().id) }
        t.refusedUnchanged(RepoCodes.LIST_NOT_REMOVABLE) { t.repo.removeAreaList(t.activeLists(Role.ACTION).first().id) }

        // Remove the new empty list: allowed (another area list has values), renumbered, and its name is
        // still taken by `renameList`; adding the same name restores it at the end instead of a second list.
        t.made { t.repo.removeAreaList(added.value) }
        assertTrue(t.lists().single { it.id == added.value }.removed)
        t.refusedUnchanged(Codes.NAME_TAKEN) { t.repo.renameList(t.activeLists(Role.AREA).first().id, name) }
        val listsBefore = t.lists().size
        val again = t.made { t.repo.addAreaList("  ${name.lowercase(Locale.ROOT)}") }
        assertTrue(again.restored)
        assertEquals(added.value, again.value)
        assertEquals(listsBefore, t.lists().size)
        assertEquals(added.value, t.activeLists(Role.AREA).last().id)
        assertEquals(name, t.lists().single { it.id == added.value }.name)

        // Move among active lists of one role.
        val areas = t.activeLists(Role.AREA).map { it.id }
        t.refusedUnchanged(RepoCodes.MOVE_AT_END) { t.repo.moveList(areas.first(), Direction.UP) }
        t.refusedUnchanged(RepoCodes.MOVE_AT_END) { t.repo.moveList(areas.last(), Direction.DOWN) }
        if (areas.size >= 2) {
            t.made { t.repo.moveList(areas.last(), Direction.UP) }
            assertEquals(areas.dropLast(2) + listOf(areas.last(), areas[areas.size - 2]), t.activeLists(Role.AREA).map { it.id })
        }
        t.refusedUnchanged(RepoCodes.NOT_REMOVED) { t.repo.restoreAreaList(areas.first()) }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.removeAreaList("no-such-list") }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.restoreAreaList("no-such-list") }
        t.refusedUnchanged(RepoCodes.NOT_FOUND) { t.repo.moveList("no-such-list", Direction.UP) }
    }

    @Test
    fun aRemovedListKeepsItsValuesFlagsAndNoneAreOffered() = each { t ->
        // Make a second filled area list so the first can go.
        val extra = t.made { t.repo.addAreaList(t.freshListName(random)) }.value
        t.made { t.repo.addValue(extra, "Extra one") }
        val list = t.firstFilledAreaList().let { if (it.id == extra) t.activeLists(Role.AREA).first() else it }
        val values = t.activeValuesOf(list)
        if (values.size >= 2) t.made { t.repo.removeValue(values[1].id) }
        t.made { t.repo.removeAreaList(list.id) }
        t.refusedUnchanged(RepoCodes.LIST_REMOVED) { t.repo.removeAreaList(list.id) }
        t.refusedUnchanged(RepoCodes.LIST_REMOVED) { t.repo.moveList(list.id, Direction.UP) }
        assertTrue(t.repo.offeredValues(Role.AREA).none { o -> o.listId == list.id })
        // The values' own removed flags are untouched.
        if (values.size >= 2) assertTrue(t.values().single { it.id == values[1].id }.removed)
        assertFalse(t.values().single { it.id == values[0].id }.removed)
        t.made { t.repo.restoreAreaList(list.id) }
        assertTrue(t.repo.offeredValues(Role.AREA).any { it.valueId == values[0].id })
        if (values.size >= 2) assertTrue(t.repo.offeredValues(Role.AREA).none { it.valueId == values[1].id })
    }

    @Test
    fun offeredValuesAreTheActiveValuesOfActiveListsInOrderAndAWrongRoleCurrentIsIgnored() = each { t ->
        for (role in Role.values()) {
            val expected = t.activeLists(role).flatMap { t.activeValuesOf(it) }.map { it.id }
            assertEquals(expected, t.repo.offeredValues(role).map { it.valueId })
            assertTrue(t.repo.offeredValues(role).none { it.removed })
        }
        val damage = t.activeValues(Role.DAMAGE).first()
        // An active current value is not duplicated; one of another role or unknown is ignored.
        assertEquals(t.activeValues(Role.DAMAGE).map { it.id }, t.repo.offeredValues(Role.DAMAGE, damage.id).map { it.valueId })
        assertEquals(t.activeValues(Role.AREA).map { it.id }, t.repo.offeredValues(Role.AREA, damage.id).map { it.valueId })
        assertEquals(t.activeValues(Role.AREA).map { it.id }, t.repo.offeredValues(Role.AREA, "no-such-value").map { it.valueId })
    }
}
