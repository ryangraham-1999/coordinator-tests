package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.testdata.SeedGenerator.NamePool
import java.util.Random

/**
 * The standard **mutated lists** (designs/stage-1-foundation.md 4.15): rename every list to a
 * generated name, add ten generated values to every list, add two area lists with five values each.
 * Used by the 1.4 checks and by the UI tests' second run. It writes through the [RepositoryPort] like
 * the generator, so every Repository rule runs; there is no other way in.
 *
 * Names come from the same pool as generated seeds (4.15): awkward characters, padding that cleaning
 * removes, up to 40 code points. A list's new name never has the key of any list that exists when
 * the mutation starts or of a name drawn earlier in the run, and a new value's name never has the
 * key of any value already in its list (removed ones included).
 */
object Mutation {
    /** Values added to every list that exists when the mutation starts. */
    const val VALUES_PER_LIST = 10

    /** Area lists added at the end, and the values each starts with. */
    const val NEW_AREA_LISTS = 2
    const val VALUES_PER_NEW_AREA_LIST = 5

    /**
     * Mutates the database behind [port]. Anything unexpected (a call refused) stops the run with a
     * [GeneratorError]; nothing is retried with other input.
     */
    fun apply(random: Random, port: RepositoryPort) {
        val start = port.snapshot()
        val listKeys = start.lists.mapTo(HashSet()) { it.nameKey }

        // 1. Rename every list.
        for (list in start.lists.sortedBy { it.id }) {
            val raw = NamePool.draw(random, listKeys)
            expectAdded("rename list ${list.id} to \"$raw\"", port.renameList(list.id, raw))
        }

        // 2. Ten more values in every list that existed at the start.
        for (list in start.lists.sortedBy { it.id }) {
            val keys = start.values.filter { it.listId == list.id }.mapTo(HashSet()) { it.nameKey }
            repeat(VALUES_PER_LIST) {
                val raw = NamePool.draw(random, keys)
                expectAdded("add value \"$raw\" to list ${list.id}", port.addValue(list.id, raw))
            }
        }

        // 3. Two more area lists, five values each.
        repeat(NEW_AREA_LISTS) {
            val raw = NamePool.draw(random, listKeys)
            val id = expectAdded("add area list \"$raw\"", port.addAreaList(raw))
            val keys = HashSet<String>()
            repeat(VALUES_PER_NEW_AREA_LIST) {
                val value = NamePool.draw(random, keys)
                expectAdded("add value \"$value\" to new list $id", port.addValue(id, value))
            }
        }
    }

    /**
     * Re-links the regions (4.26): for every region, with probability 1/3 unlink it, 1/3 link it to a random
     * offered area value (active, in an active area list), 1/3 leave it. Used after [apply]. Written through
     * the port like [apply]; a call refused stops the run with a [GeneratorError]. With no offered area value
     * nothing is linked (a region meant to be linked is left as it was).
     */
    fun relink(random: Random, port: RepositoryPort) {
        val snap = port.snapshot()
        val activeAreaLists = snap.lists.filter { it.role == Role.AREA && !it.removed }.mapTo(HashSet()) { it.id }
        val offered = snap.values.filter { !it.removed && it.listId in activeAreaLists }.sortedBy { it.id }
        for (region in port.regionLinks().keys.sorted()) {
            when (random.nextInt(3)) {
                0 -> expectAdded("unlink region $region", port.unlinkRegion(region))
                1 -> if (offered.isNotEmpty()) {
                    val value = offered[random.nextInt(offered.size)]
                    expectAdded("link region $region to ${value.id}", port.linkRegion(region, value.id))
                }
                else -> Unit
            }
        }
    }

    private fun expectAdded(what: String, r: PortResult): String = when (r) {
        is PortResult.Added -> r.id
        is PortResult.Refused -> throw GeneratorError("$what: expected Added, got Refused(${r.code} ${r.detail})")
    }
}
