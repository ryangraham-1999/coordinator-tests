package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.testdata.RegGenerator
import com.streamlinemyjob.inspect.testdata.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.util.Random

// Helpers shared by the S1.12 list-rule tests (designs/stage-1-foundation.md 4.13, 4.15).

/** Runs [write], which must be refused with [expectedCode], and checks that nothing changed. */
internal fun TestDb.refusedUnchanged(expectedCode: String, write: () -> Result<*>): Refused {
    val before = snapshot()
    val r = write()
    assertTrue("expected $expectedCode, got $r", r is Refused && r.code == expectedCode)
    Snapshot.assertSame(before, snapshot(), "refused $expectedCode changed the database")
    return r as Refused
}

/** Runs [write], which must be made, then checks that the integrity check is clean. Returns its id. */
internal fun TestDb.made(write: () -> Result<String>): Ok<String> {
    val r = write()
    assertTrue("expected Ok, got $r", r is Ok)
    assertEquals(emptyList<Violation>(), check())
    @Suppress("UNCHECKED_CAST")
    return r as Ok<String>
}

/** A new inspection (on a new vehicle) to hang findings on. */
internal fun TestDb.newInspection(random: Random): String {
    val v = (repo.addVehicle(RegGenerator(random).next().typed) as Ok).value
    return (repo.addInspection(v, Dates.format(LocalDate.now(clock)), "1000") as Ok).value
}

/** A name that is valid and whose key no value of [listId] (removed ones included) has. */
internal fun TestDb.freshValueName(listId: String, random: Random, prefix: String = "New"): String {
    val keys = values().filter { it.listId == listId }.map { it.nameKey }.toSet()
    while (true) {
        val name = "$prefix ${random.nextInt(100_000)}"
        if (Text.key(name) !in keys) return name
    }
}

/** A name that is valid and whose key no list has. */
internal fun TestDb.freshListName(random: Random, prefix: String = "List"): String {
    val keys = lists().map { it.nameKey }.toSet()
    while (true) {
        val name = "$prefix ${random.nextInt(100_000)}"
        if (Text.key(name) !in keys) return name
    }
}

/** The active lists of [role], in order. */
internal fun TestDb.activeLists(role: Role): List<TagList> =
    lists().filter { it.role == role && !it.removed }.sortedBy { it.position }

/** The active values of [list], in order. */
internal fun TestDb.activeValuesOf(list: TagList): List<TagValue> =
    values().filter { it.listId == list.id && !it.removed }.sortedBy { it.position }

/** Makes sure the first active list of [role] has at least [count] active values, adding through the repository. */
internal fun TestDb.ensureValues(role: Role, count: Int, random: Random): TagList {
    val list = activeLists(role).first()
    while (activeValuesOf(list).size < count) made { repo.addValue(list.id, freshValueName(list.id, random)) }
    return list
}

/** An area list with at least one active value (every seed has one). */
internal fun TestDb.firstFilledAreaList(): TagList = activeLists(Role.AREA).first { activeValuesOf(it).isNotEmpty() }

internal val badCharName = "a\u0001b"
internal val tooLongName = "x".repeat(41)
