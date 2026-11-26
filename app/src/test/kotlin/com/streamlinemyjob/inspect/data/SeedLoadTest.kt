package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.SeedData
import com.streamlinemyjob.inspect.core.Text
import com.streamlinemyjob.inspect.testdata.SeedGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * S1.8 (designs/stage-1-foundation.md 4.10, 4.11): the confirmed seed and 20 generated seeds each
 * load; every value of each is stored on a finding and read back with its cleaned name unchanged;
 * `DataCheck` is clean after each. Expected names, counts and ids are read from each seed itself.
 */
@RunWith(RobolectricTestRunner::class)
class SeedLoadTest {
    private val random = TestRandom.forTest(SeedLoadTest::class.java.name)

    /** 20 generated seeds: each forced extreme once (4.15), then uniform draws. */
    private fun generatedSeeds(): List<String> {
        val forced = SeedGenerator.Extreme.entries.map { SeedGenerator.generate(random, ConfirmedSeed.listNames, it) }
        return forced + List(20 - forced.size) { SeedGenerator.generate(random, ConfirmedSeed.listNames) }
    }

    @Test
    fun theConfirmedSeedLoadsEveryValueAndReadsBack() = loadsEveryValueAndReadsBack(ConfirmedSeed.text)

    @Test
    fun twentyGeneratedSeedsLoadEveryValueAndReadBack() {
        val seeds = generatedSeeds()
        assertEquals(20, seeds.size)
        seeds.forEachIndexed { i, seed ->
            try {
                loadsEveryValueAndReadsBack(seed)
            } catch (e: AssertionError) {
                throw AssertionError("generated seed $i: ${e.message}", e)
            }
        }
    }

    private fun loadsEveryValueAndReadsBack(seedText: String) {
        val seed = (Seed.parse(seedText) as Seed.Valid).seed
        val rawNames = rawNamesById(seedText)
        TestDb(seedText).use { t ->
            assertRowsMatchTheSeed(seed, t)
            assertEquals(emptyList<Violation>(), t.check())

            // One finding per value, each value in its own role's place; the others are the first offered.
            val vehicle = (t.repo.addVehicle("AB12 CDE") as Ok).value
            val inspection = (t.repo.addInspection(vehicle, Dates.format(LocalDate.now(t.clock)), "1000") as Ok).value
            val area0 = t.activeValues(Role.AREA).first().id
            val damage0 = t.activeValues(Role.DAMAGE).first().id
            val expectedName = HashMap<String, String>()
            for (list in seed.lists) for (value in list.values) {
                val r = when (list.role) {
                    Role.AREA -> t.repo.addFinding(inspection, value.id, damage0, actionOf(null), null, confirmedSameArea = true)
                    Role.DAMAGE -> t.repo.addFinding(inspection, area0, value.id, actionOf(null), null, confirmedSameArea = true)
                    Role.ACTION -> t.repo.addFinding(inspection, area0, damage0, actionOf(value.id), null, confirmedSameArea = true)
                }
                val findingId = (r as? Ok)?.value ?: fail("value ${value.id}: $r") as Nothing
                expectedName[findingId] = Text.clean(rawNames.getValue(value.id))
                expectedName["${findingId}:list"] = Text.clean(rawNames.getValue(list.id))
                expectedName["${findingId}:role"] = list.role.name
            }
            val total = seed.lists.sumOf { it.values.size }
            val snapshot = t.snapshot()
            assertEquals(total, snapshot.findings.size)
            for (f in snapshot.findings) {
                val want = expectedName.getValue(f.id)
                val got = when (expectedName.getValue("${f.id}:role")) {
                    "AREA" -> f.areaName.also { assertEquals(expectedName.getValue("${f.id}:list"), f.areaListName) }
                    "DAMAGE" -> f.damageName
                    else -> f.actionName
                }
                assertEquals("finding ${f.id}", want, got)
            }
            assertEquals(emptyList<Violation>(), t.check())
        }
    }

    /** Lists and values as 4.11 says: ids, roles, cleaned names and keys, positions from file order. */
    private fun assertRowsMatchTheSeed(seed: SeedData, t: TestDb) {
        val lists = t.lists().associateBy { it.id }
        val values = t.values().associateBy { it.id }
        assertEquals(seed.lists.size, lists.size)
        assertEquals(seed.lists.sumOf { it.values.size }, values.size)
        for (role in Role.entries) {
            seed.lists.filter { it.role == role }.forEachIndexed { position, list ->
                val row = lists.getValue(list.id)
                assertEquals(TagList(list.id, role, list.name, Text.key(list.name), position, false), row)
                list.values.forEachIndexed { i, v ->
                    assertEquals(TagValue(v.id, list.id, v.name, Text.key(v.name), i, false), values.getValue(v.id))
                }
            }
        }
    }

    /** Every id in the seed file mapped to its name exactly as written (before cleaning). */
    private fun rawNamesById(seedText: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (l in Json.parseToJsonElement(seedText).jsonObject.getValue("lists").jsonArray) {
            val list = l as JsonObject
            out[list.getValue("id").jsonPrimitive.content] = list.getValue("name").jsonPrimitive.content
            for (v in list.getValue("values").jsonArray) {
                out[v.jsonObject.getValue("id").jsonPrimitive.content] = v.jsonObject.getValue("name").jsonPrimitive.content
            }
        }
        return out
    }

    @Test
    fun aSecondLoadLoadsNothing() {
        TestDb(ConfirmedSeed.text).use { t ->
            val before = t.snapshot()
            val other = SeedGenerator.generate(random, ConfirmedSeed.listNames)
            assertFalse(SeedLoader.loadIfEmpty(t.db, other))
            assertFalse(SeedLoader.loadIfEmpty(t.db, ConfirmedSeed.text))
            assertEquals(before, t.snapshot())
        }
    }

    @Test
    fun anInvalidSeedFailsLoudlyAndLoadsNothing() {
        val seed = SeedGenerator.generate(random, ConfirmedSeed.listNames)
        val broken = Regex("\"version\"\\s*:\\s*1").replaceFirst(seed, "\"version\": 2").also { assertTrue(it != seed) }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AppDatabase.build(context, "invalid-seed.db", allowMainThread = true)
        try {
            try {
                SeedLoader.loadIfEmpty(db, broken)
                fail("an invalid seed loaded")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!, e.message!!.contains("SEED_FORMAT"))
            }
            assertEquals(0, db.tagDao().listCount())
        } finally {
            db.close()
            context.deleteDatabase("invalid-seed.db")
        }
    }
}
