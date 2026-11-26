package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.SeedData
import com.streamlinemyjob.inspect.core.SeedError
import com.streamlinemyjob.inspect.core.Text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generated seeds (designs/stage-1-foundation.md 4.15, 6): 50 validate, each forced extreme is hit,
 * the counts stay in range and vary, the awkward names are drawn, and no list name has a confirmed
 * list name's key. The confirmed names are read from the seed file itself.
 */
class SeedGeneratorTest {
    private fun random(test: String) = TestRandom.forTest("${SeedGeneratorTest::class.qualifiedName}.$test")

    private val confirmed: SeedData by lazy {
        val text = File(System.getProperty("smj.seedFile") ?: error("smj.seedFile not set")).readText()
        (Seed.parse(text) as Seed.Valid).seed
    }
    private val confirmedListNames get() = confirmed.lists.map { it.name }

    /** The 50 generated seeds of one test: the four extremes forced first, then 46 drawn uniformly. */
    private fun fifty(test: String): List<Pair<SeedGenerator.Extreme?, String>> {
        val r = random(test)
        val forced = SeedGenerator.Extreme.entries.map { it to SeedGenerator.generate(r, confirmedListNames, force = it) }
        return forced + List(50 - forced.size) { null to SeedGenerator.generate(r, confirmedListNames) }
    }

    private fun parse(text: String): SeedData {
        val p = Seed.parse(text)
        assertTrue(p.toString(), p is Seed.Valid)
        return (p as Seed.Valid).seed
    }

    @Test
    fun fiftyGeneratedSeedsValidate() {
        val seeds = fifty("fiftyGeneratedSeedsValidate")
        assertEquals(50, seeds.size)
        seeds.forEach { (_, text) -> assertEquals(emptyList<SeedError>(), Seed.validate(text)) }
    }

    private fun hits(extreme: SeedGenerator.Extreme, seed: SeedData): Boolean {
        val areas = seed.lists.filter { it.role == Role.AREA }
        return when (extreme) {
            SeedGenerator.Extreme.ONE_AREA_LIST -> areas.size == SeedGenerator.AREA_LISTS_MIN
            SeedGenerator.Extreme.EIGHT_AREA_LISTS -> areas.size == SeedGenerator.AREA_LISTS_MAX
            SeedGenerator.Extreme.LIST_OF_60 -> seed.lists.any { it.values.size == SeedGenerator.VALUES_MAX }
            SeedGenerator.Extreme.EMPTY_ACTION_LIST -> seed.lists.single { it.role == Role.ACTION }.values.isEmpty()
        }
    }

    @Test
    fun eachForcedExtremeIsHit() {
        val seeds = fifty("eachForcedExtremeIsHit").map { (forced, text) -> forced to parse(text) }
        for ((forced, seed) in seeds) if (forced != null) assertTrue("$forced not hit", hits(forced, seed))
        for (extreme in SeedGenerator.Extreme.entries) assertTrue("$extreme", seeds.any { hits(extreme, it.second) })
    }

    @Test
    fun countsStayInRangeAndTheShapeVaries() {
        val seeds = fifty("countsStayInRangeAndTheShapeVaries").map { parse(it.second) }
        for (seed in seeds) {
            val areas = seed.lists.filter { it.role == Role.AREA }
            assertTrue(areas.size in SeedGenerator.AREA_LISTS_MIN..SeedGenerator.AREA_LISTS_MAX)
            assertTrue(areas.all { it.values.size in 0..SeedGenerator.VALUES_MAX })
            assertTrue(areas.any { it.values.isNotEmpty() })
            assertTrue(seed.lists.single { it.role == Role.DAMAGE }.values.size in 1..SeedGenerator.VALUES_MAX)
            assertTrue(seed.lists.single { it.role == Role.ACTION }.values.size in 0..SeedGenerator.VALUES_MAX)
        }
        // Not one fixed shape: the area-list counts and the list sizes differ between seeds.
        val shapes = seeds.map { s -> s.lists.map { it.role to it.values.size } }
        assertTrue(shapes.toSet().size > 40)
        assertTrue(seeds.map { s -> s.lists.count { it.role == Role.AREA } }.toSet().size >= 5)
        val confirmedShape = confirmed.lists.map { it.role to it.values.size }
        assertTrue(shapes.none { it == confirmedShape })
    }

    @Test
    fun idsAreGPlusTwelveHexAndUnique() {
        val hex = Regex("g-[0-9a-f]{12}")
        for ((_, text) in fifty("idsAreGPlusTwelveHexAndUnique")) {
            val seed = parse(text)
            val ids = seed.lists.map { it.id } + seed.lists.flatMap { l -> l.values.map { it.id } }
            assertTrue(ids.all { hex.matches(it) })
            assertEquals(ids.size, ids.toSet().size)
            val confirmedIds = confirmed.lists.map { it.id } + confirmed.lists.flatMap { l -> l.values.map { it.id } }
            assertTrue(ids.none { it in confirmedIds })
        }
    }

    @Test
    fun namesAreUniqueByKeyInEachListAndListNamesNeverMatchAConfirmedListName() {
        val confirmedKeys = confirmedListNames.map { Text.key(it) }.toSet()
        for ((_, text) in fifty("namesAreUniqueByKeyInEachListAndListNamesNeverMatchAConfirmedListName")) {
            val seed = parse(text)
            val listKeys = seed.lists.map { Text.key(it.name) }
            assertEquals(listKeys.size, listKeys.toSet().size)
            assertTrue(listKeys.none { it in confirmedKeys })
            for (l in seed.lists) assertEquals(l.values.size, l.values.map { Text.key(it.name) }.toSet().size)
        }
    }

    @Test
    fun listNamesToAvoidAreAvoidedWhateverTheyAre() {
        // Any names given to avoid, not only the confirmed ones: here, names drawn from the generator's own pool.
        val r = random("listNamesToAvoidAreAvoidedWhateverTheyAre")
        val avoid = List(200) { SeedGenerator.NamePool.draw(r, HashSet()) }
        val keys = avoid.map { Text.key(it) }.toSet()
        repeat(50) {
            val seed = parse(SeedGenerator.generate(r, avoid))
            assertTrue(seed.lists.none { Text.key(it.name) in keys })
        }
    }

    @Test
    fun theAwkwardNamesOf415AreDrawn() {
        val texts = fifty("theAwkwardNamesOf415AreDrawn").map { it.second }
        val raw = texts.flatMap { t ->
            Json.parseToJsonElement(t).jsonObject["lists"]!!.jsonArray.flatMap { l ->
                listOf(l.jsonObject["name"]!!.jsonPrimitive.content) +
                    l.jsonObject["values"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            }
        }
        val cleaned = raw.map { Text.clean(it) }
        val cp = { s: String -> Text.codePoints(s) }
        val wanted = mapOf<String, (String) -> Boolean>(
            "1 code point" to { cp(it) == 1 },
            "exactly ${Rules.NAME_MAX} code points" to { cp(it) == Rules.NAME_MAX },
            "é" to { 'é' in it }, "ü" to { 'ü' in it }, "ñ" to { 'ñ' in it }, "ł" to { 'ł' in it },
            "apostrophe" to { '\'' in it }, "double quote" to { '"' in it },
            "&" to { '&' in it }, "/" to { '/' in it }, "(" to { '(' in it },
            "digit" to { s -> s.any { it in '0'..'9' } },
            "non-BMP" to { s -> s.codePoints().anyMatch { it > 0xFFFF } },
        )
        for ((what, test) in wanted) assertTrue(what, cleaned.any(test))
        assertTrue("raw names that cleaning changes", raw.zip(cleaned).count { (a, b) -> a != b } > 50)
        assertTrue("non-breaking spaces in raw names", raw.any { ' ' in it })
        assertTrue("padding at the start", raw.any { it.startsWith(" ") })
        assertTrue("space runs inside", raw.any { Text.clean(it).isNotEmpty() && it.trim().contains("  ") })
    }

    @Test
    fun theSameRandomGivesTheSameSeed() {
        val a = SeedGenerator.generate(java.util.Random(42), confirmedListNames)
        val b = SeedGenerator.generate(java.util.Random(42), confirmedListNames)
        assertEquals(a, b)
    }
}
