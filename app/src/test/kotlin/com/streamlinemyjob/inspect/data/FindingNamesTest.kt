package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S1.11: what the screens read to show a finding and to offer the area tabs (designs/stage-1-foundation.md
 * 4.12, 4.16, 4.17): `resolvedFinding`, `resolvedFindingsForInspection` and `offeredLists`, on the
 * confirmed seed and on generated ones. Names are read from the database, never written here.
 */
@RunWith(RobolectricTestRunner::class)
class FindingNamesTest {
    private val random = TestRandom.forTest(FindingNamesTest::class.java.name)

    private fun seeds() = listOf(
        ConfirmedSeed.text,
        SeedGenerator.generate(random, ConfirmedSeed.listNames),
        SeedGenerator.generate(random, ConfirmedSeed.listNames, SeedGenerator.Extreme.ONE_AREA_LIST),
        SeedGenerator.generate(random, ConfirmedSeed.listNames, SeedGenerator.Extreme.EMPTY_ACTION_LIST),
    )

    private fun each(body: (TestDb) -> Unit) {
        for (seed in seeds()) TestDb(seed).use { body(it) }
    }

    @Test
    fun aFindingIsResolvedToTheNamesItShowsAndRenamesFollow() = each { t ->
        val inspection = t.newInspection(random)
        val areaList = t.firstFilledAreaList()
        val area = t.activeValuesOf(areaList).first()
        val damage = t.activeValues(Role.DAMAGE).first()
        val action = t.activeValues(Role.ACTION).firstOrNull()
        val id = t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(action?.id), "  a note ", false) }.value

        val before = t.repo.resolvedFinding(id)!!.view
        assertEquals(areaList.name, before.areaListName)
        assertEquals(area.name, before.areaName)
        assertEquals(damage.name, before.damageName)
        assertEquals(action?.name, before.actionName)
        assertEquals("a note", before.note)
        assertFalse(before.areaListRemoved || before.areaRemoved || before.damageRemoved || before.actionRemoved)

        val newArea = t.freshValueName(area.listId, random, "Renamed")
        t.made { t.repo.renameValue(area.id, newArea) }
        val newList = t.freshListName(random, "Group")
        t.made { t.repo.renameList(areaList.id, newList) }
        val after = t.repo.resolvedFinding(id)!!.view
        assertEquals(newArea, after.areaName)
        assertEquals(newList, after.areaListName)
        assertEquals("$newList › $newArea: ${damage.name} → ${action?.name ?: "No action"}\na note", FindingText.of(after))
        assertNull(t.repo.resolvedFinding("no-such-finding"))
    }

    @Test
    fun aFindingWithNoActionHasNoActionName() = each { t ->
        val inspection = t.newInspection(random)
        val area = t.activeValuesOf(t.firstFilledAreaList()).first()
        val damage = t.activeValues(Role.DAMAGE).first()
        val id = t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, false) }.value
        val view = t.repo.resolvedFinding(id)!!.view
        assertNull(view.actionName)
        assertFalse(view.actionRemoved)
        assertNull(view.note)
        assertTrue(FindingText.of(view).endsWith("→ No action"))
    }

    @Test
    fun removedValuesAndListsAreMarkedOnTheFindingsThatUseThem() = each { t ->
        t.ensureValues(Role.DAMAGE, 2, random)
        // A second area list with a value, so the first can be removed.
        val second = t.made { t.repo.addAreaList(t.freshListName(random)) }.value
        t.made { t.repo.addValue(second, t.freshValueName(second, random)) }
        val inspection = t.newInspection(random)
        val areaList = t.activeLists(Role.AREA).first { it.id != second && t.activeValuesOf(it).isNotEmpty() }
        val (area, otherArea) = t.activeValuesOf(areaList).let { it.first() to it.getOrNull(1) }
        val damage = t.activeValues(Role.DAMAGE).first()
        val action = t.activeValues(Role.ACTION).firstOrNull()
        val id = t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(action?.id), null, false) }.value

        t.made { t.repo.removeValue(damage.id) }
        assertTrue(t.repo.resolvedFinding(id)!!.view.damageRemoved)
        if (action != null && t.activeValuesOf(t.lists().first { it.id == action.listId }).size > 1) {
            t.made { t.repo.removeValue(action.id) }
            assertTrue(t.repo.resolvedFinding(id)!!.view.actionRemoved)
        }
        if (otherArea != null) {
            t.made { t.repo.removeValue(area.id) }
            assertTrue(t.repo.resolvedFinding(id)!!.view.areaRemoved)
        }
        t.made { t.repo.removeAreaList(areaList.id) }
        val view = t.repo.resolvedFinding(id)!!.view
        assertTrue(view.areaListRemoved)
        assertTrue(FindingText.of(view).startsWith("${areaList.name} (removed) › "))
        assertEquals(areaList.name, view.areaListName)
    }

    @Test
    fun findingsOfAnInspectionComeInTheOrderTheyWereMade() = each { t ->
        val inspection = t.newInspection(random)
        val other = t.newInspection(random)
        val areas = t.activeValuesOf(t.firstFilledAreaList())
        val damage = t.activeValues(Role.DAMAGE).first()
        val made = (0 until 3).map { i ->
            // Different areas, so no same-area confirmation is needed.
            t.ensureValues(Role.AREA, 3, random)
            val area = t.activeValuesOf(t.activeLists(Role.AREA).first())[i]
            t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), "n$i", false) }.value
        }
        t.made { t.repo.addFinding(other, areas.first().id, damage.id, actionOf(null), null, false) }
        val resolved = t.repo.resolvedFindingsForInspection(inspection)
        assertEquals(made, resolved.map { it.finding.id })
        assertEquals(listOf("n0", "n1", "n2"), resolved.map { it.view.note })
    }

    @Test
    fun offeredListsAreTheActiveListsInOrderAndTheCurrentRemovedOneFirst() = each { t ->
        val activeBefore = t.activeLists(Role.AREA)
        assertEquals(activeBefore.map { it.id }, t.repo.offeredLists(Role.AREA).map { it.listId })
        assertEquals(activeBefore.map { it.name }, t.repo.offeredLists(Role.AREA).map { it.name })
        assertTrue(t.repo.offeredLists(Role.AREA).none { it.removed })
        assertEquals(t.activeLists(Role.DAMAGE).map { it.id }, t.repo.offeredLists(Role.DAMAGE).map { it.listId })

        // Remove a list that holds a value a finding uses: it leaves the offer but stays as the current list.
        val keep = t.made { t.repo.addAreaList(t.freshListName(random)) }.value
        t.made { t.repo.addValue(keep, t.freshValueName(keep, random)) }
        val inspection = t.newInspection(random)
        val list = t.activeLists(Role.AREA).first { it.id != keep && t.activeValuesOf(it).isNotEmpty() }
        val area = t.activeValuesOf(list).first()
        val damage = t.activeValues(Role.DAMAGE).first()
        t.made { t.repo.addFinding(inspection, area.id, damage.id, actionOf(null), null, false) }
        t.made { t.repo.removeAreaList(list.id) }

        val withoutCurrent = t.repo.offeredLists(Role.AREA)
        assertTrue(withoutCurrent.none { it.listId == list.id })
        val withCurrent = t.repo.offeredLists(Role.AREA, area.id)
        assertEquals(OfferedList(list.id, list.name, removed = true), withCurrent.first())
        assertEquals(withoutCurrent, withCurrent.drop(1))
        // The current value of an active list adds nothing.
        val activeValue = t.activeValuesOf(t.activeLists(Role.AREA).first { it.id == keep }).first()
        assertEquals(withoutCurrent, t.repo.offeredLists(Role.AREA, activeValue.id))
        // A value of another role adds nothing.
        assertEquals(withoutCurrent, t.repo.offeredLists(Role.AREA, damage.id))
    }
}
