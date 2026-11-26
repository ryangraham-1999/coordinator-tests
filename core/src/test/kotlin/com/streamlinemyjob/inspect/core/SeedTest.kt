package com.streamlinemyjob.inspect.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Seed parser and validator (designs/stage-1-foundation.md 4.10, 6): the confirmed file, the
 * worked examples, and one broken seed per `SEED_*` code. Breakers are applied to the confirmed
 * file and to seeds of random shape; expected names, counts and positions are read from the file
 * being broken, never written here.
 */
class SeedTest {
    private fun random(test: String): Random = TestRandom.forTest("${SeedTest::class.qualifiedName}.$test")

    private val confirmedText: String by lazy {
        File(System.getProperty("smj.seedFile") ?: error("smj.seedFile not set")).readText()
    }
    private val confirmed: JsonObject get() = Json.parseToJsonElement(confirmedText).jsonObject

    /** A valid seed of random shape: 1-8 area lists, one damage list, one action list, in random order, random ids and names. */
    private fun generated(r: Random): JsonObject {
        val used = HashSet<String>()
        fun id(): String {
            while (true) {
                val id = "t" + r.nextLong(0, Long.MAX_VALUE).toString(36)
                if (used.add(id)) return id
            }
        }
        val words = listOf("Front", "rear", "Café", "O'Brien", "wing", "A&B", "🚗", "1/2", "(left)", "Łódź", "x")
        fun name(n: Int) = "  ${words[r.nextInt(words.size)]}   $n "
        fun list(role: String, size: Int, n: Int) = buildJsonObject {
            put("id", id())
            put("role", role)
            put("name", name(n))
            put("values", buildJsonArray { repeat(size) { add(buildJsonObject { put("id", id()); put("name", name(it)) }) } })
        }
        val areaSizes = MutableList(r.nextInt(1, 9)) { r.nextInt(0, 61) }
        if (areaSizes.all { it == 0 }) areaSizes[0] = r.nextInt(1, 61)
        val lists = areaSizes.map { "AREA" to it } + ("DAMAGE" to r.nextInt(1, 61)) + ("ACTION" to r.nextInt(0, 61))
        return buildJsonObject {
            put("format", "smj-seed")
            put("version", 1)
            put("lists", JsonArray(lists.shuffled(r).mapIndexed { n, (role, size) -> list(role, size, n) }))
        }
    }

    // --- small JSON editing helpers ---------------------------------------------------------------

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })
    private fun JsonObject.without(key: String) = JsonObject(toMutableMap().apply { remove(key) })
    private val JsonObject.lists: List<JsonObject> get() = this["lists"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.withLists(lists: List<JsonElement>) = with("lists", JsonArray(lists))
    private fun JsonObject.values(i: Int): List<JsonObject> = lists[i]["values"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.role(i: Int) = lists[i]["role"]!!.jsonPrimitive.content
    private fun JsonObject.listsWithRole(role: String) = lists.indices.filter { role(it) == role }

    private fun JsonObject.editList(i: Int, edit: (JsonObject) -> JsonObject) =
        withLists(lists.mapIndexed { k, l -> if (k == i) edit(l) else l })

    private fun JsonObject.editValue(i: Int, j: Int, edit: (JsonObject) -> JsonObject) =
        editList(i) { l -> l.with("values", JsonArray(values(i).mapIndexed { k, v -> if (k == j) edit(v) else v })) }

    /** Every (list, value) position with a value, as pairs. */
    private fun JsonObject.valuePlaces() = lists.indices.flatMap { i -> values(i).indices.map { j -> i to j } }

    private fun codes(seed: JsonObject) = Seed.validate(seed.toString()).map { it.code }

    // --- the confirmed seed and the worked examples (4.10) ----------------------------------------

    @Test
    fun theConfirmedFileIsValidAndLoadsEveryListAndValueInFileOrderWithCleanedNames() {
        val parsed = Seed.parse(confirmedText)
        assertTrue(parsed.toString(), parsed is Seed.Valid)
        val seed = (parsed as Seed.Valid).seed
        val raw = confirmed
        assertEquals(raw.lists.size, seed.lists.size)
        raw.lists.forEachIndexed { i, l ->
            val loaded = seed.lists[i]
            assertEquals(l["id"]!!.jsonPrimitive.content, loaded.id)
            assertEquals(l["role"]!!.jsonPrimitive.content, loaded.role.name)
            assertEquals(Text.clean(l["name"]!!.jsonPrimitive.content), loaded.name)
            assertEquals(raw.values(i).map { it["id"]!!.jsonPrimitive.content to Text.clean(it["name"]!!.jsonPrimitive.content) },
                loaded.values.map { it.id to it.name })
        }
        assertEquals(raw.lists.sumOf { it["values"]!!.jsonArray.size }, seed.lists.sumOf { it.values.size })
    }

    @Test
    fun theDamageListChangedToAreaIsRoleCountNoDamageList() {
        val r = random("theDamageListChangedToAreaIsRoleCountNoDamageList")
        for (seed in listOf(confirmed) + List(10) { generated(r) }) {
            val damage = seed.listsWithRole("DAMAGE").single()
            val broken = seed.editList(damage) { it.with("role", JsonPrimitive("AREA")) }
            assertEquals(listOf("SEED_ROLE_COUNT (no DAMAGE list)"), Seed.validate(broken.toString()).map { it.toString() })
        }
    }

    @Test
    fun aMistypedFieldIsOneUnknownAndOneMissing() {
        val r = random("aMistypedFieldIsOneUnknownAndOneMissing")
        for (seed in listOf(confirmed) + List(10) { generated(r) }) {
            val (i, j) = if (seed === confirmed) seed.valuePlaces().first() else seed.valuePlaces().random(r)
            val broken = seed.editValue(i, j) { v -> v.without("name").with("nmae", v["name"]!!) }
            assertEquals(
                listOf("SEED_FIELD lists[$i].values[$j].nmae (unknown)", "SEED_FIELD lists[$i].values[$j].name (missing)"),
                Seed.validate(broken.toString()).map { it.toString() },
            )
        }
    }

    @Test
    fun theSameValueNameInTwoListsIsValid() {
        val r = random("theSameValueNameInTwoListsIsValid")
        for (seed in listOf(confirmed) + List(20) { generated(r) }) {
            val withValues = seed.lists.indices.filter { seed.values(it).isNotEmpty() }
            if (withValues.size < 2) continue
            val (a, b) = withValues.shuffled(r).take(2)
            val name = seed.values(a).random(r)["name"]!!
            val broken = seed.editValue(b, r.nextInt(seed.values(b).size)) { it.with("name", name) }
            val dupInB = seed.values(b).count { Text.key(it["name"]!!.jsonPrimitive.content) == Text.key(name.jsonPrimitive.content) }
            if (dupInB > 0) continue // the name was already in list b: that is a SEED_NAME_DUP case, tested below
            assertEquals(emptyList<SeedError>(), Seed.validate(broken.toString()))
        }
    }

    @Test
    fun generatedSeedsAreValid() {
        val r = random("generatedSeedsAreValid")
        repeat(50) { assertEquals(emptyList<SeedError>(), Seed.validate(generated(r).toString())) }
    }

    @Test
    fun theActionListAndSomeAreaListsMayBeEmpty() {
        val r = random("theActionListAndSomeAreaListsMayBeEmpty")
        for (seed in listOf(confirmed) + List(10) { generated(r) }) {
            val action = seed.listsWithRole("ACTION").single()
            val areas = seed.listsWithRole("AREA").filter { seed.values(it).isNotEmpty() }
            var broken = seed.editList(action) { it.with("values", JsonArray(emptyList())) }
            if (areas.size > 1) broken = broken.editList(areas.random(r)) { it.with("values", JsonArray(emptyList())) }
            assertEquals(emptyList<SeedError>(), Seed.validate(broken.toString()))
        }
    }

    // --- one broken seed per code -------------------------------------------------------------------

    /** Breaks a valid seed so that it shows exactly one code; returns the broken text. */
    private val breakers: Map<String, (JsonObject, Random) -> String> = mapOf(
        Codes.SEED_JSON to { seed, r ->
            val text = seed.toString()
            when (r.nextInt(3)) {
                0 -> text.substring(0, r.nextInt(1, text.length - 1))
                1 -> text.replaceFirst("\"lists\":", "\"lists\"")
                else -> "$text,"
            }
        },
        Codes.SEED_FORMAT to { seed, r ->
            when (r.nextInt(4)) {
                0 -> seed.with("format", JsonPrimitive("smj-seeds"))
                1 -> seed.with("version", JsonPrimitive(2))
                2 -> seed.with("version", JsonPrimitive("1"))
                else -> seed.without("format")
            }.toString()
        },
        Codes.SEED_FIELD to { seed, r ->
            val (i, j) = seed.valuePlaces().random(r)
            when (r.nextInt(5)) {
                0 -> seed.editValue(i, j) { it.with("colour", JsonPrimitive("red")) }
                1 -> seed.editValue(i, j) { it.without("name") }
                2 -> seed.editList(i) { it.without("values") }
                3 -> seed.with("comment", JsonPrimitive("x"))
                else -> seed.editValue(i, j) { it.with("name", JsonPrimitive(7)) }
            }.toString()
        },
        Codes.SEED_ROLE to { seed, _ ->
            // An extra list with a role other than the three; its id and name are new.
            val extra = buildJsonObject {
                put("id", "zz-extra-list"); put("role", "WHEELS"); put("name", "Zz extra list")
                put("values", buildJsonArray { add(buildJsonObject { put("id", "zz-extra-value"); put("name", "Zz") }) })
            }
            seed.withLists(seed.lists + extra).toString()
        },
        Codes.SEED_ROLE_COUNT to { seed, r ->
            val extra = { role: String -> buildJsonObject {
                put("id", "zz-extra-list"); put("role", role); put("name", "Zz extra list")
                put("values", buildJsonArray { add(buildJsonObject { put("id", "zz-extra-value"); put("name", "Zz") }) })
            } }
            when (r.nextInt(3)) {
                0 -> seed.withLists(seed.lists + extra("DAMAGE"))
                1 -> seed.withLists(seed.lists + extra("ACTION"))
                else -> seed.withLists(seed.lists.filterIndexed { i, _ -> seed.role(i) != "ACTION" })
            }.toString()
        },
        Codes.SEED_ID_BAD to { seed, r ->
            val bad = listOf("Upper-case", "-starts-with-hyphen", "under_score", "a".repeat(65), "", "spa ce", "dot.ted", "é")[r.nextInt(8)]
            if (r.nextBoolean()) seed.editList(r.nextInt(seed.lists.size)) { it.with("id", JsonPrimitive(bad)) }.toString()
            else { val (i, j) = seed.valuePlaces().random(r); seed.editValue(i, j) { it.with("id", JsonPrimitive(bad)) }.toString() }
        },
        Codes.SEED_ID_DUP to { seed, r ->
            val places = seed.lists.indices.map { it to -1 } + seed.valuePlaces()
            val (a, b) = places.shuffled(r).take(2)
            fun idAt(p: Pair<Int, Int>) = (if (p.second < 0) seed.lists[p.first] else seed.values(p.first)[p.second])["id"]!!
            val id = idAt(a)
            (if (b.second < 0) seed.editList(b.first) { it.with("id", id) } else seed.editValue(b.first, b.second) { it.with("id", id) }).toString()
        },
        Codes.SEED_NAME_EMPTY to { seed, r ->
            val empty = listOf("", "   ", " \t ", "\n")[r.nextInt(4)]
            if (r.nextBoolean()) seed.editList(r.nextInt(seed.lists.size)) { it.with("name", JsonPrimitive(empty)) }.toString()
            else { val (i, j) = seed.valuePlaces().random(r); seed.editValue(i, j) { it.with("name", JsonPrimitive(empty)) }.toString() }
        },
        Codes.SEED_NAME_TOO_LONG to { seed, r ->
            val long = listOf("é".repeat(41), "🚗".repeat(41), "  " + "ab ".repeat(14) + "  ")[r.nextInt(3)]
            val (i, j) = seed.valuePlaces().random(r)
            seed.editValue(i, j) { it.with("name", JsonPrimitive(long)) }.toString()
        },
        Codes.SEED_NAME_BAD_CHAR to { seed, r ->
            val c = listOf("\u0000", "\u0007", "\u001B", "\u007F", "\u0090")[r.nextInt(5)]
            if (r.nextBoolean()) seed.editList(r.nextInt(seed.lists.size)) { it.with("name", JsonPrimitive("Bad${c}list")) }.toString()
            else { val (i, j) = seed.valuePlaces().random(r); seed.editValue(i, j) { it.with("name", JsonPrimitive("Bad${c}value")) }.toString() }
        },
        Codes.SEED_NAME_DUP to { seed, r ->
            val i = seed.lists.indices.filter { seed.values(it).size >= 2 }.random(r)
            val (a, b) = seed.values(i).indices.shuffled(r).take(2)
            val name = seed.values(i)[a]["name"]!!.jsonPrimitive.content
            seed.editValue(i, b) { it.with("name", JsonPrimitive("  " + name.uppercase() + " ")) }.toString()
        },
        Codes.SEED_LIST_NAME_DUP to { seed, r ->
            val (a, b) = seed.lists.indices.shuffled(r).take(2)
            val name = seed.lists[a]["name"]!!.jsonPrimitive.content
            seed.editList(b) { it.with("name", JsonPrimitive(name.lowercase() + " ")) }.toString()
        },
        Codes.SEED_EMPTY_REQUIRED to { seed, r ->
            if (r.nextBoolean()) seed.editList(seed.listsWithRole("DAMAGE").single()) { it.with("values", JsonArray(emptyList())) }.toString()
            else seed.listsWithRole("AREA").fold(seed) { s, i -> s.editList(i) { it.with("values", JsonArray(emptyList())) } }.toString()
        },
    )

    @Test
    fun thereIsABreakerForEverySeedCode() = assertEquals(Codes.SEED_ALL.toSet(), breakers.keys)

    @Test
    fun oneBrokenSeedPerCodeIsRejectedWithThatCodeAlone() {
        val r = random("oneBrokenSeedPerCodeIsRejectedWithThatCodeAlone")
        for ((code, breaker) in breakers) {
            for (seed in listOf(confirmed) + List(10) { generated(r) }) {
                if (code == Codes.SEED_NAME_DUP && seed.lists.none { it["values"]!!.jsonArray.size >= 2 }) continue
                val text = breaker(seed, r)
                val errors = Seed.validate(text)
                assertTrue("$code: no errors", errors.isNotEmpty())
                assertEquals("$code: $errors", setOf(code), errors.map { it.code }.toSet())
                assertTrue(Seed.parse(text) is Seed.Invalid)
            }
        }
    }

    @Test
    fun errorsCarryTheirJsonPath() {
        val seed = confirmed
        val (i, j) = seed.valuePlaces().last()
        val broken = seed.editValue(i, j) { it.with("name", JsonPrimitive(" ")).with("id", JsonPrimitive("Bad")) }
        assertEquals(
            listOf(SeedError(Codes.SEED_ID_BAD, "lists[$i].values[$j].id", "\"Bad\""), SeedError(Codes.SEED_NAME_EMPTY, "lists[$i].values[$j].name", "")),
            Seed.validate(broken.toString()),
        )
        val dupList = seed.editList(1) { it.with("name", seed.lists[0]["name"]!!) }
        assertEquals(listOf("SEED_LIST_NAME_DUP lists[1].name (${Text.clean(seed.lists[0]["name"]!!.jsonPrimitive.content)}, same name as lists[0].name)"),
            Seed.validate(dupList.toString()).map { it.toString() })
    }

    @Test
    fun notJsonIsSeedJsonAlone() {
        assertEquals(listOf(Codes.SEED_JSON), Seed.validate("").map { it.code })
        assertEquals(listOf(Codes.SEED_JSON), Seed.validate("{ \"format\": \"smj-seed\", }x").map { it.code })
    }

    @Test
    fun aFileThatIsNotAnObjectIsFormatAndMissingLists() {
        assertEquals(listOf(Codes.SEED_FORMAT, Codes.SEED_FORMAT, Codes.SEED_FIELD), Seed.validate("[]").map { it.code })
        assertEquals("SEED_FIELD lists (missing)", Seed.validate("[]").last().toString())
    }

    @Test
    fun allErrorsAreCollectedNotJustTheFirst() {
        val r = random("allErrorsAreCollectedNotJustTheFirst")
        repeat(20) {
            val seed = generated(r)
            // Breakers that touch different places and never mask each other.
            val picks = listOf(Codes.SEED_FORMAT, Codes.SEED_ID_BAD, Codes.SEED_NAME_TOO_LONG, Codes.SEED_ROLE)
            var text = seed.toString()
            for (code in picks) text = breakers[code]!!(Json.parseToJsonElement(text).jsonObject, r)
            val got = Seed.validate(text).map { it.code }.toSet()
            assertTrue("$got", got.containsAll(picks))
        }
    }
}
