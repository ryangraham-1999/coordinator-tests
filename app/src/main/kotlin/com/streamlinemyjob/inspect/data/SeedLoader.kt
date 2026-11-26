package com.streamlinemyjob.inspect.data

import android.content.Context
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed
import com.streamlinemyjob.inspect.core.Text

/**
 * Loads a seed into an empty database (designs/stage-1-foundation.md 4.10, 4.11). Runs once, in one
 * transaction, only when `tag_list` is empty; after that the database is the only truth. The app
 * loads the bundled seed; tests call the same loader with any seed string.
 */
object SeedLoader {
    /** Where the confirmed seed is bundled. */
    const val ASSET_PATH = "seed/lists.json"

    /** The bundled confirmed seed's text. */
    fun bundledSeed(context: Context): String =
        context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Parses and validates [seedText] and, if `tag_list` is empty, inserts its lists and values with
     * positions from file order: lists of each role in file order, values in file order. Returns
     * true when it loaded, false when the database already had lists. An invalid seed is a bug: it
     * throws [IllegalStateException] with the whole error list and loads nothing.
     */
    fun loadIfEmpty(db: AppDatabase, seedText: String): Boolean {
        val seed = when (val parsed = Seed.parse(seedText)) {
            is Seed.Valid -> parsed.seed
            is Seed.Invalid -> throw IllegalStateException(
                "The seed is not valid (${parsed.errors.size} errors):\n" + parsed.errors.joinToString("\n"),
            )
        }
        return db.runInTransaction<Boolean> {
            val dao = db.tagDao()
            if (dao.listCount() > 0) return@runInTransaction false
            val nextPosition = HashMap<Role, Int>()
            for (list in seed.lists) {
                val listPosition = nextPosition.getOrDefault(list.role, 0)
                nextPosition[list.role] = listPosition + 1
                val listName = Text.clean(list.name)
                dao.insertList(TagList(list.id, list.role, listName, Text.key(listName), listPosition))
                list.values.forEachIndexed { i, value ->
                    val valueName = Text.clean(value.name)
                    dao.insertValue(TagValue(value.id, list.id, valueName, Text.key(valueName), i))
                }
            }
            true
        }
    }
}
