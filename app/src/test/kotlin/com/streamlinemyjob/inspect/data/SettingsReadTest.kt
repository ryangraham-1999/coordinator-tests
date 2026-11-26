package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S1.13: what the Settings and List editor screens read (`settingsLists`, `listDetail`, designs/stage-1-foundation.md
 * 4.16), on the confirmed seed and on generated ones. Expected values come from the snapshot, never from names.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsReadTest {
    private val random = TestRandom.forTest(SettingsReadTest::class.java.name)

    private fun each(body: (TestDb) -> Unit) {
        val seeds = listOf(
            ConfirmedSeed.text,
            SeedGenerator.generate(random, ConfirmedSeed.listNames),
            SeedGenerator.generate(random, ConfirmedSeed.listNames, SeedGenerator.Extreme.ONE_AREA_LIST),
        )
        for (seed in seeds) TestDb(seed).use { body(it) }
    }

    @Test
    fun settingsListsHasEveryListWithItsActiveValueCountInTheScreenOrder() = each { t ->
        // Remove one area list when another still has values, and one value from a random list, so the counts and the order are tested.
        val s0 = t.snapshot()
        val areaLists = t.activeLists(Role.AREA)
        if (areaLists.size > 1) t.made { t.repo.removeAreaList(areaLists.first().id) }
        val rows = t.repo.settingsLists()
        val s = t.snapshot()
        assertEquals(s.lists.map { it.id }.sorted(), rows.map { it.id }.sorted())
        for (row in rows) {
            val list = s.lists.single { it.id == row.id }
            assertEquals(list.name, row.name)
            assertEquals(list.removed, row.removed)
            assertEquals(s.values.count { it.listId == list.id && !it.removed }, row.activeValues)
        }
        // Active area lists in order, removed area lists, then the damage list, then the action list.
        val roles = rows.map { it.role to it.removed }
        val expected = rows.sortedWith(
            compareBy({ when (it.role) { Role.AREA -> 0; Role.DAMAGE -> 1; Role.ACTION -> 2 } }, { it.removed }),
        ).map { it.role to it.removed }
        assertEquals(expected, roles)
        assertEquals(t.activeLists(Role.AREA).map { it.id }, rows.filter { it.role == Role.AREA && !it.removed }.map { it.id })
        assertTrue(s0.lists.size == rows.size)
    }

    @Test
    fun listDetailGivesActiveValuesInOrderAndRemovedValuesByName() = each { t ->
        val s = t.snapshot()
        val list = s.lists.filter { l -> s.values.count { it.listId == l.id && !it.removed } >= 2 }.let { it[random.nextInt(it.size)] }
        val values = s.values.filter { it.listId == list.id && !it.removed }.sortedBy { it.position }
        val gone = values[random.nextInt(values.size)]
        if (list.role == Role.AREA || list.role == Role.ACTION || values.size > 1) t.made { t.repo.removeValue(gone.id) }
        val detail = t.repo.listDetail(list.id)!!
        val after = t.snapshot().values.filter { it.listId == list.id }
        assertEquals(after.filter { !it.removed }.sortedBy { it.position }.map { it.id }, detail.active.map { it.id })
        assertEquals(after.filter { it.removed }.sortedBy { it.nameKey }.map { it.id }, detail.removed.map { it.id })
        assertEquals(detail.active.size, detail.list.activeValues)
        assertNull(t.repo.listDetail("no-such-list"))
    }
}
