package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.testdata.DataGenerator
import com.streamlinemyjob.inspect.testdata.Mutation
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.Random

/**
 * S1.12 random operations (designs/stage-1-foundation.md 4.13, S1.12 in section 7): for each of 5
 * starting points, 100 findings are generated and then 300 random operations are run, drawn from
 * every list-editing operation plus `addFinding` and `editFinding`, with arguments that are
 * sometimes invalid on purpose. What each call must answer is worked out *before* the call, from the
 * snapshot alone ([Model]), never from the Repository. After every operation:
 *  - `DataCheck` is clean;
 *  - the answer is the expected one (`Ok`, `Ok` restored, or `Refused` with the expected code), and a
 *    refused write left the snapshot unchanged;
 *  - every finding resolves to names for all its values;
 *  - `offeredValues` for each role equals exactly the active values of active lists, computed
 *    independently from the snapshot.
 */
@RunWith(RobolectricTestRunner::class)
class ListRulesRandomTest {
    private val random = TestRandom.forTest(ListRulesRandomTest::class.java.name)

    /** What a call must answer: no code means made; [restored] only for `addValue` and `addAreaList`. */
    private data class Expect(val code: String? = null, val restored: Boolean = false)

    private class Planned(val label: String, val expect: Expect, val run: () -> Result<String>)

    /** The rules of 4.13 and 4.12 worked out on a snapshot alone. */
    private class Model(val s: Snapshot) {
        val listById = s.lists.associateBy { it.id }
        val valueById = s.values.associateBy { it.id }
        fun valuesIn(listId: String) = s.values.filter { it.listId == listId }
        fun activeIn(listId: String) = valuesIn(listId).filter { !it.removed }.sortedBy { it.position }
        fun activeLists(role: Role) = s.lists.filter { it.role == role && !it.removed }.sortedBy { it.position }
        fun activeAreaValueCount(exceptValue: String? = null, exceptList: String? = null): Int =
            activeLists(Role.AREA).filter { it.id != exceptList }.sumOf { l -> activeIn(l.id).count { it.id != exceptValue } }

        fun nameProblem(raw: String): String? = Text.checkName(raw).problems.firstOrNull()?.code

        fun offered(role: Role): List<Snapshot.ValueRow> = activeLists(role).flatMap { activeIn(it.id) }

        fun addValue(listId: String, raw: String): Expect {
            if (listId !in listById) return Expect(RepoCodes.NOT_FOUND)
            nameProblem(raw)?.let { return Expect(it) }
            val same = valuesIn(listId).firstOrNull { it.nameKey == Text.key(raw) }
            return when {
                same == null -> Expect()
                !same.removed -> Expect(Codes.NAME_TAKEN)
                else -> Expect(restored = true)
            }
        }

        fun renameValue(id: String, raw: String): Expect {
            val v = valueById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            nameProblem(raw)?.let { return Expect(it) }
            val clash = valuesIn(v.listId).firstOrNull { it.id != id && it.nameKey == Text.key(raw) } ?: return Expect()
            return Expect(if (clash.removed) RepoCodes.NAME_TAKEN_BY_REMOVED else Codes.NAME_TAKEN)
        }

        fun moveValue(id: String, up: Boolean): Expect {
            val v = valueById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            if (v.removed) return Expect(RepoCodes.VALUE_REMOVED)
            val active = activeIn(v.listId)
            val i = active.indexOfFirst { it.id == id }
            return if ((up && i == 0) || (!up && i == active.size - 1)) Expect(RepoCodes.MOVE_AT_END) else Expect()
        }

        fun removeValue(id: String): Expect {
            val v = valueById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            if (v.removed) return Expect(RepoCodes.VALUE_REMOVED)
            val l = listById.getValue(v.listId)
            if (l.role == Role.DAMAGE && activeIn(l.id).size == 1) return Expect(RepoCodes.LAST_DAMAGE_VALUE)
            if (l.role == Role.AREA && !l.removed && activeAreaValueCount(exceptValue = id) == 0) return Expect(RepoCodes.LAST_AREA_VALUE)
            return Expect()
        }

        fun restoreValue(id: String): Expect {
            val v = valueById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            if (!v.removed) return Expect(RepoCodes.NOT_REMOVED)
            if (activeIn(v.listId).any { it.nameKey == v.nameKey }) return Expect(Codes.NAME_TAKEN)
            return Expect()
        }

        fun renameList(id: String, raw: String): Expect {
            if (id !in listById) return Expect(RepoCodes.NOT_FOUND)
            nameProblem(raw)?.let { return Expect(it) }
            return if (s.lists.any { it.id != id && it.nameKey == Text.key(raw) }) Expect(Codes.NAME_TAKEN) else Expect()
        }

        fun addList(role: Role, raw: String): Expect {
            if (role != Role.AREA) return Expect(RepoCodes.ROLE_FIXED)
            nameProblem(raw)?.let { return Expect(it) }
            val same = s.lists.firstOrNull { it.nameKey == Text.key(raw) }
            return when {
                same == null -> Expect()
                same.role == Role.AREA && same.removed -> Expect(restored = true)
                else -> Expect(Codes.NAME_TAKEN)
            }
        }

        fun moveList(id: String, up: Boolean): Expect {
            val l = listById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            if (l.removed) return Expect(RepoCodes.LIST_REMOVED)
            val active = activeLists(l.role)
            val i = active.indexOfFirst { it.id == id }
            return if ((up && i == 0) || (!up && i == active.size - 1)) Expect(RepoCodes.MOVE_AT_END) else Expect()
        }

        fun removeAreaList(id: String): Expect {
            val l = listById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            if (l.role != Role.AREA) return Expect(RepoCodes.LIST_NOT_REMOVABLE)
            if (l.removed) return Expect(RepoCodes.LIST_REMOVED)
            return if (activeAreaValueCount(exceptList = id) == 0) Expect(RepoCodes.LAST_AREA_VALUE) else Expect()
        }

        fun restoreAreaList(id: String): Expect {
            val l = listById[id] ?: return Expect(RepoCodes.NOT_FOUND)
            if (l.role != Role.AREA) return Expect(RepoCodes.LIST_NOT_REMOVABLE)
            return if (!l.removed) Expect(RepoCodes.NOT_REMOVED) else Expect()
        }

        /** [old] is the finding being edited, or null for an add. Checks run in 4.12's order. */
        fun finding(
            inspectionId: String, area: String, damage: String, action: String?, note: String?,
            confirmed: Boolean, old: Snapshot.FindingRow?,
        ): Expect {
            if (s.inspections.none { it.id == inspectionId }) return Expect(RepoCodes.NOT_FOUND)
            val slots = listOfNotNull(
                Triple(Role.AREA, area, old?.areaValueId), Triple(Role.DAMAGE, damage, old?.damageValueId),
                action?.let { Triple(Role.ACTION, it, old?.actionValueId) },
            )
            for ((role, id, _) in slots) {
                val v = valueById[id]
                if (v == null || listById.getValue(v.listId).role != role) return Expect(RepoCodes.ROLE_MISMATCH)
            }
            for ((_, id, current) in slots) {
                val v = valueById.getValue(id)
                if ((v.removed || listById.getValue(v.listId).removed) && id != current) return Expect(RepoCodes.VALUE_REMOVED)
            }
            if (note != null && Text.checkNote(note).problem != null) return Expect(Text.checkNote(note).problem!!.code)
            if (!confirmed && s.findings.any { it.inspectionId == inspectionId && it.id != old?.id && it.areaValueId == area }) {
                return Expect(RepoCodes.SAME_AREA_UNCONFIRMED)
            }
            return Expect()
        }
    }

    // ---- Choosing an operation and its arguments ----

    private val badNames = listOf("", "   ", "x".repeat(Rules.NAME_MAX + 1), "a\u0001b")

    private fun <T> Random.pick(list: List<T>): T = list[nextInt(list.size)]

    /** The same name written differently: padded, lower case; the same key. */
    private fun Random.messy(name: String): String = when (nextInt(3)) {
        0 -> "  $name "
        1 -> name.lowercase(Locale.ROOT)
        else -> " ${name.lowercase(Locale.ROOT)}  "
    }

    private fun Random.freshName(): String = "Op ${nextInt(10_000_000)}"

    private fun plan(m: Model, repo: Repository, r: Random, drain: Boolean): Planned {
        val values = m.s.values
        val lists = m.s.lists
        fun valueId(): String = if (r.nextInt(25) == 0) "no-such-value" else r.pick(values).id
        fun listId(): String = if (r.nextInt(25) == 0) "no-such-list" else r.pick(lists).id
        fun areaListId(): String = r.pick(lists.filter { it.role == Role.AREA }).id

        val roll = r.nextInt(100)
        val removeWeight = if (drain) 26 else 12
        val addWeight = if (drain) 6 else 14
        var edge = 0
        fun hit(weight: Int): Boolean { edge += weight; return roll < edge }

        return when {
            hit(addWeight) -> {
                val id = listId()
                val own = m.valuesIn(id)
                val raw = when (r.nextInt(6)) {
                    0, 1, 2 -> r.freshName()
                    3 -> if (own.isNotEmpty()) r.messy(r.pick(own).name) else r.freshName()
                    4 -> own.filter { it.removed }.takeIf { it.isNotEmpty() }?.let { r.messy(r.pick(it).name) } ?: r.freshName()
                    else -> r.pick(badNames)
                }
                Planned("addValue", m.addValue(id, raw)) { repo.addValue(id, raw) }
            }
            hit(12) -> {
                val id = valueId()
                val v = m.valueById[id]
                val siblings = v?.let { m.valuesIn(it.listId) }.orEmpty()
                val raw = when (r.nextInt(6)) {
                    0, 1 -> r.freshName()
                    2 -> if (v != null) r.messy(v.name) else r.freshName()
                    3 -> if (siblings.isNotEmpty()) r.messy(r.pick(siblings).name) else r.freshName()
                    4 -> siblings.filter { it.removed }.takeIf { it.isNotEmpty() }?.let { r.messy(r.pick(it).name) } ?: r.freshName()
                    else -> r.pick(badNames)
                }
                Planned("renameValue", m.renameValue(id, raw)) { repo.renameValue(id, raw) }
            }
            hit(10) -> {
                val id = valueId()
                val up = r.nextBoolean()
                Planned("moveValue", m.moveValue(id, up)) { repo.moveValue(id, if (up) Direction.UP else Direction.DOWN) }
            }
            hit(removeWeight) -> {
                // Often aim at the smallest list, so the last-value refusals are met.
                val aim = r.nextInt(3)
                val damage = m.activeLists(Role.DAMAGE).flatMap { m.activeIn(it.id) }
                val id = when {
                    aim == 0 -> (m.activeLists(Role.DAMAGE) + m.activeLists(Role.AREA))
                        .map { l -> m.activeIn(l.id) }.filter { it.isNotEmpty() }.minByOrNull { it.size }
                        ?.let { r.pick(it).id } ?: valueId()
                    aim == 1 && damage.isNotEmpty() -> r.pick(damage).id
                    else -> valueId()
                }
                Planned("removeValue", m.removeValue(id)) { repo.removeValue(id) }
            }
            hit(8) -> {
                val removed = values.filter { it.removed }
                val id = if (removed.isNotEmpty() && r.nextInt(5) > 0) r.pick(removed).id else valueId()
                Planned("restoreValue", m.restoreValue(id)) { repo.restoreValue(id) }
            }
            hit(6) -> {
                val id = listId()
                val l = m.listById[id]
                val raw = when (r.nextInt(5)) {
                    0, 1 -> r.freshName()
                    2 -> if (l != null) r.messy(l.name) else r.freshName()
                    3 -> r.messy(r.pick(lists).name)
                    else -> r.pick(badNames)
                }
                Planned("renameList", m.renameList(id, raw)) { repo.renameList(id, raw) }
            }
            hit(6) -> {
                val role = if (r.nextInt(8) == 0) r.pick(listOf(Role.DAMAGE, Role.ACTION)) else Role.AREA
                val removedAreas = lists.filter { it.role == Role.AREA && it.removed }
                val raw = when (r.nextInt(5)) {
                    0, 1 -> r.freshName()
                    2 -> removedAreas.takeIf { it.isNotEmpty() }?.let { r.messy(r.pick(it).name) } ?: r.freshName()
                    3 -> r.messy(r.pick(lists).name)
                    else -> r.pick(badNames)
                }
                Planned("addList", m.addList(role, raw)) { repo.addList(role, raw) }
            }
            hit(5) -> {
                val id = listId()
                val up = r.nextBoolean()
                Planned("moveList", m.moveList(id, up)) { repo.moveList(id, if (up) Direction.UP else Direction.DOWN) }
            }
            hit(if (drain) 8 else 5) -> {
                val id = if (r.nextInt(6) == 0) r.pick(lists).id else if (r.nextInt(25) == 0) "no-such-list" else areaListId()
                Planned("removeAreaList", m.removeAreaList(id)) { repo.removeAreaList(id) }
            }
            hit(6) -> {
                val removed = lists.filter { it.removed }
                val id = if (removed.isNotEmpty() && r.nextInt(5) > 0) r.pick(removed).id else listId()
                Planned("restoreAreaList", m.restoreAreaList(id)) { repo.restoreAreaList(id) }
            }
            else -> findingPlan(m, repo, r, edit = r.nextInt(5) < 2)
        }
    }

    private fun findingPlan(m: Model, repo: Repository, r: Random, edit: Boolean): Planned {
        val old = if (edit && m.s.findings.isNotEmpty()) r.pick(m.s.findings) else null
        val inspectionId = old?.inspectionId ?: if (r.nextInt(30) == 0) "no-such-inspection" else r.pick(m.s.inspections).id

        fun slot(role: Role, current: String?): String {
            if (current != null && r.nextInt(3) == 0) return current
            val ofRole = m.s.values.filter { m.listById.getValue(it.listId).role == role }
            return when (r.nextInt(20)) {
                in 0..13 -> m.offered(role).takeIf { it.isNotEmpty() }?.let { r.pick(it).id } ?: r.pick(m.s.values).id
                in 14..16 -> r.pick(ofRole.ifEmpty { m.s.values }).id
                in 17..18 -> r.pick(m.s.values.filter { m.listById.getValue(it.listId).role != role }.ifEmpty { m.s.values }).id
                else -> "no-such-value"
            }
        }
        val area = slot(Role.AREA, old?.areaValueId)
        val damage = slot(Role.DAMAGE, old?.damageValueId)
        val action = if (r.nextInt(4) == 0) null else slot(Role.ACTION, old?.actionValueId)
        val note = when (r.nextInt(20)) { in 0..13 -> null; in 14..18 -> "note ${r.nextInt(1000)}\nline two"; else -> "x".repeat(Rules.NOTE_MAX + 1) }
        val confirmed = r.nextBoolean()
        return if (old == null) {
            Planned("addFinding", m.finding(inspectionId, area, damage, action, note, confirmed, null)) {
                repo.addFinding(inspectionId, area, damage, actionOf(action), note, confirmed)
            }
        } else {
            Planned("editFinding", m.finding(inspectionId, area, damage, action, note, confirmed, old)) {
                repo.editFinding(old.id, area, damage, actionOf(action), note, confirmed)
            }
        }
    }

    // ---- After every operation ----

    private fun assertInvariants(t: TestDb, after: Snapshot, label: String) {
        assertEquals("$label: DataCheck", emptyList<Violation>(), t.check())
        val values = after.values.associateBy { it.id }
        val lists = after.lists.associateBy { it.id }
        for (f in after.findings) {
            val area = values[f.areaValueId]
            val damage = values[f.damageValueId]
            val action = f.actionValueId?.let { values[it] }
            assertNotNull("$label: finding ${f.id} area has no name", f.areaName)
            assertNotNull("$label: finding ${f.id} damage has no name", f.damageName)
            assertEquals("$label: finding ${f.id} area", area?.name, f.areaName)
            assertEquals("$label: finding ${f.id} area list", area?.let { lists[it.listId]?.name }, f.areaListName)
            assertEquals("$label: finding ${f.id} damage", damage?.name, f.damageName)
            assertEquals("$label: finding ${f.id} action", action?.name, f.actionName)
            if (f.actionValueId != null) assertNotNull("$label: finding ${f.id} action has no name", f.actionName)
        }
        for (role in Role.values()) {
            val expected = after.lists.filter { it.role == role && !it.removed }.sortedBy { it.position }
                .flatMap { l -> after.values.filter { it.listId == l.id && !it.removed }.sortedBy { it.position } }
            val offered = t.repo.offeredValues(role)
            assertEquals("$label: offeredValues($role) ids", expected.map { it.id }, offered.map { it.valueId })
            assertEquals("$label: offeredValues($role) names", expected.map { it.name }, offered.map { it.name })
            assertTrue("$label: offeredValues($role) marks one removed", offered.none { it.removed })
        }
    }

    private fun runOperations(t: TestDb, start: String, ops: Int, counts: MutableMap<String, Int>) {
        val r = Random(random.nextLong())
        var before = t.snapshot()
        assertInvariants(t, before, "$start, before the first operation")
        for (n in 1..ops) {
            val planned = plan(Model(before), t.repo, r, drain = n > ops / 2)
            val label = "$start, operation $n (${planned.label})"
            val result = planned.run()
            val after = t.snapshot()
            val expect = planned.expect
            when (result) {
                is Ok -> {
                    assertEquals("$label: expected ${expect.code}, got $result", null, expect.code)
                    assertEquals("$label: restored", expect.restored, result.restored)
                    counts.merge("${planned.label}:ok" + if (result.restored) ":restored" else "", 1, Int::plus)
                }
                is Refused -> {
                    assertEquals("$label: refused with the wrong code ($result)", expect.code, result.code)
                    Snapshot.assertSame(before, after, "$label: refused ${result.code} changed the database")
                    counts.merge(result.code, 1, Int::plus)
                }
            }
            assertInvariants(t, after, label)
            before = after
        }
    }

    private fun shrink(t: TestDb, role: Role, keep: Int) {
        while (true) {
            val active = t.activeValues(role)
            if (active.size <= keep) return
            check(t.repo.removeValue(active.last().id) is Ok)
        }
    }

    @Test
    fun threeHundredRandomOperationsFromEachOfFiveStartingPoints() {
        val seeds = listOf(
            "confirmed seed" to ConfirmedSeed.text,
            "generated seed 1" to SeedGenerator.generate(random, ConfirmedSeed.listNames),
            "generated seed 2" to SeedGenerator.generate(random, ConfirmedSeed.listNames),
            "generated seed 3" to SeedGenerator.generate(random, ConfirmedSeed.listNames),
            "confirmed seed after the mutation" to ConfirmedSeed.text,
        )
        val counts = sortedMapOf<String, Int>()
        val t0 = System.nanoTime()
        var total = 0
        for ((i, start) in seeds.withIndex()) {
            val (name, seedText) = start
            TestDb(seedText).use { t ->
                val port = RepositoryAdapter(t.db)
                if (i == seeds.lastIndex) Mutation.apply(random, port)
                val startTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                DataGenerator.generate(random, port, findings = FINDINGS, start = startTime)
                assertEquals(name, FINDINGS, t.snapshot().findings.size)
                // Starting points 2 and 4 begin with a small damage list, point 3 with a small area pool, so
                // that the last-value refusals are reached by chance in 300 operations (removals go through the repository).
                if (i == 1 || i == 3) shrink(t, Role.DAMAGE, keep = 3)
                if (i == 2) shrink(t, Role.AREA, keep = 4)
                runOperations(t, name, OPERATIONS, counts)
                total += OPERATIONS
            }
        }
        val seconds = (System.nanoTime() - t0) / 1_000_000_000.0
        println("listrules-random operations=$total time=%.1fs outcomes=%s".format(Locale.ROOT, seconds, counts))
        assertEquals(5 * OPERATIONS, total)
    }

    private companion object {
        const val FINDINGS = 100
        const val OPERATIONS = 300
    }
}
