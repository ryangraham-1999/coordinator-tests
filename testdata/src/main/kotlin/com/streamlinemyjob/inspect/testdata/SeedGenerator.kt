package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.Text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Random

/**
 * Generated seeds (designs/stage-1-foundation.md 4.15): valid seed JSON that differs in shape from
 * the confirmed seed. 1 to 8 area lists of 0 to 60 values each (at least one non-empty), a damage
 * list of 1 to 60 values and an action list of 0 to 60 values; counts are picked uniformly unless
 * an [Extreme] is forced. Names come from an awkward pool (4.15); ids are `g-` + 12 hex characters.
 */
object SeedGenerator {
    const val AREA_LISTS_MIN = 1
    const val AREA_LISTS_MAX = 8
    const val VALUES_MAX = 60

    /** The extremes a test class forces at least once each (4.15). */
    enum class Extreme { ONE_AREA_LIST, EIGHT_AREA_LISTS, LIST_OF_60, EMPTY_ACTION_LIST }

    /**
     * A generated seed as JSON text. [avoidListNames] are the confirmed seed's list names, read by the
     * caller from the confirmed seed file: no generated list name has the same name key as one of them.
     * [force] makes the seed hit one [Extreme].
     */
    fun generate(random: Random, avoidListNames: Collection<String>, force: Extreme? = null): String {
        val areaLists = when (force) {
            Extreme.ONE_AREA_LIST -> AREA_LISTS_MIN
            Extreme.EIGHT_AREA_LISTS -> AREA_LISTS_MAX
            else -> between(random, AREA_LISTS_MIN, AREA_LISTS_MAX)
        }
        val sizes = IntArray(areaLists) { between(random, 0, VALUES_MAX) }
        if (sizes.all { it == 0 }) sizes[random.nextInt(areaLists)] = between(random, 1, VALUES_MAX)
        var damageSize = between(random, 1, VALUES_MAX)
        var actionSize = if (force == Extreme.EMPTY_ACTION_LIST) 0 else between(random, 0, VALUES_MAX)
        if (force == Extreme.LIST_OF_60) {
            when (val pick = random.nextInt(areaLists + 2)) {
                areaLists -> damageSize = VALUES_MAX
                areaLists + 1 -> actionSize = VALUES_MAX
                else -> sizes[pick] = VALUES_MAX
            }
        }
        val roles = List(areaLists) { Role.AREA } + Role.DAMAGE + Role.ACTION
        val counts = sizes.toList() + damageSize + actionSize
        val order = roles.indices.toMutableList().also { shuffle(it, random) }

        val ids = HashSet<String>()
        fun id(): String {
            while (true) {
                val id = "g-" + (1..12).map { HEX[random.nextInt(HEX.length)] }.joinToString("")
                if (ids.add(id)) return id
            }
        }
        val listKeys = avoidListNames.map { Text.key(it) }.toHashSet()
        val lists = order.map { i ->
            val listId = id()
            val listName = NamePool.draw(random, listKeys)
            val valueKeys = HashSet<String>()
            val values = List(counts[i]) {
                JsonObject(mapOf("id" to JsonPrimitive(id()), "name" to JsonPrimitive(NamePool.draw(random, valueKeys))))
            }
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(listId),
                    "role" to JsonPrimitive(roles[i].name),
                    "name" to JsonPrimitive(listName),
                    "values" to JsonArray(values),
                ),
            )
        }
        return JsonObject(
            mapOf(
                "format" to JsonPrimitive(Seed.FORMAT),
                "version" to JsonPrimitive(Seed.VERSION.toInt()),
                "lists" to JsonArray(lists),
            ),
        ).toString()
    }

    private const val HEX = "0123456789abcdef"

    internal fun between(random: Random, min: Int, max: Int): Int = min + random.nextInt(max - min + 1)

    private fun <T> shuffle(list: MutableList<T>, random: Random) = java.util.Collections.shuffle(list, random)

    /**
     * Raw names (4.15): 1-code-point names, exactly 40-code-point names, accented letters, apostrophes
     * and quotes, `&`, `/`, `(`, digits, non-BMP characters, and padding and space runs that cleaning
     * removes. Each drawn name is valid (4.7) and its key is new to the given set, which it joins.
     */
    internal object NamePool {
        /** Single code points: ASCII, accented, digits, symbols, non-BMP. */
        val SINGLE: List<String> = (
            ('A'..'Z').map { "$it" } + ('0'..'9').map { "$it" } +
                listOf("é", "ü", "ñ", "ł", "ø", "ß", "&", "/", "(", "'", "\"", "#", "+", "🚗", "𝔸", "𝔹", "🛞", "🔧")
            )

        val WORDS: List<String> = listOf(
            "Trim", "kit", "O'Brien", "\"Mint\"", "Café", "Café", "über", "Señor", "Łódź", "A&B", "1/2",
            "(left)", "(rear", "🚗", "𝔸lpha", "Front", "rear", "panel", "wing", "door", "Ñandú", "x", "12",
            "No. 7", "it's", "Zoë", "Smørrebrød", "ﬁne", "naïve", "R&R", "3/4", "100%", "Ω", "Spare", "kerb",
        )

        /** Separators between words: runs of spaces, a non-breaking space, a tab; all clean to one space. */
        private val SEPARATORS = listOf(" ", " ", " ", "  ", "   ", " ", " \t ", " ")
        private val PADDING = listOf("", "", "", " ", "  ", " ", "\t", "   ")

        /** Characters for a 40-code-point name (no whitespace, all NFC already). */
        private val LONG_CHARS: List<String> = SINGLE + listOf("a", "b", "c", "x", "y", "z", "ł", "é")

        fun draw(random: Random, keys: MutableSet<String>): String {
            while (true) {
                val raw = when (random.nextInt(4)) {
                    0 -> SINGLE[random.nextInt(SINGLE.size)]
                    1 -> exactly(random, Rules.NAME_MAX)
                    else -> phrase(random)
                }
                val checked = Text.checkName(raw)
                if (checked.ok && keys.add(checked.key)) return raw
            }
        }

        /** A name of exactly [codePoints] code points after cleaning: no edge spaces, no space runs. */
        fun exactly(random: Random, codePoints: Int): String {
            val out = StringBuilder()
            var lastSpace = true
            for (i in 0 until codePoints) {
                val space = !lastSpace && i < codePoints - 1 && random.nextInt(6) == 0
                out.append(if (space) " " else LONG_CHARS[random.nextInt(LONG_CHARS.size)])
                lastSpace = space
            }
            return out.toString()
        }

        private fun phrase(random: Random): String {
            val words = List(between(random, 1, 4)) { WORDS[random.nextInt(WORDS.size)] }.toMutableList()
            if (random.nextInt(3) == 0) words += (random.nextInt(99) + 1).toString()
            val body = words.reduce { a, b -> a + SEPARATORS[random.nextInt(SEPARATORS.size)] + b }
            return PADDING[random.nextInt(PADDING.size)] + body + PADDING[random.nextInt(PADDING.size)]
        }
    }
}
