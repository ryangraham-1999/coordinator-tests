package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.Links
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Random

/**
 * Generated link files (designs/stage-1-foundation.md 4.26): a link-file JSON string that validates
 * against a given seed and drawing. Each region, in random order, is linked with probability 0.6 to a
 * value drawn uniformly from all AREA values of the seed (so values repeat and some regions are not
 * linked). Only non-empty lists contribute values, so a seed with an empty area list is fine.
 */
object LinkGenerator {
    /** Chance that a region is linked (4.26). */
    const val LINKED_PROBABILITY = 0.6

    /** The extremes a test class forces at least once each (4.26). */
    enum class Extreme { NONE_LINKED, ALL_LINKED, TWO_ON_ONE_VALUE }

    /** Every AREA value id of the seed, in seed order. */
    fun areaValueIds(seedJson: String): List<String> {
        val parsed = Seed.parse(seedJson) as? Seed.Valid ?: error("not a valid seed")
        return parsed.seed.lists.filter { it.role == Role.AREA }.flatMap { list -> list.values.map { it.id } }
    }

    /** A link file for [seedJson] and [drawing]; [force] makes it hit one [Extreme]. */
    fun generate(random: Random, seedJson: String, drawing: Drawing, force: Extreme? = null): String {
        val values = areaValueIds(seedJson)
        check(values.isNotEmpty()) { "a valid seed has at least one AREA value" }
        val regions = drawing.regions.map { it.id }.toMutableList().also { java.util.Collections.shuffle(it, random) }
        val doubled = if (force == Extreme.TWO_ON_ONE_VALUE && regions.size >= 2) regions.take(2) else emptyList()
        val sharedValue = values[random.nextInt(values.size)]
        val entries = regions.mapNotNull { region ->
            val value = when {
                region in doubled -> sharedValue
                force == Extreme.NONE_LINKED -> null
                force == Extreme.ALL_LINKED -> values[random.nextInt(values.size)]
                random.nextDouble() < LINKED_PROBABILITY -> values[random.nextInt(values.size)]
                else -> null
            }
            value?.let { JsonObject(mapOf("region" to JsonPrimitive(region), "value" to JsonPrimitive(it))) }
        }
        return JsonObject(
            mapOf(
                "format" to JsonPrimitive(Links.FORMAT),
                "version" to JsonPrimitive(Links.VERSION.toInt()),
                "drawing" to JsonPrimitive(drawing.id),
                "links" to JsonArray(entries),
            ),
        ).toString()
    }
}
