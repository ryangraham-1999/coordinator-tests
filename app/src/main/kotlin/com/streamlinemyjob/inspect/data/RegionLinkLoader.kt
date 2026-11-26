package com.streamlinemyjob.inspect.data

import android.content.Context
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.LinkData
import com.streamlinemyjob.inspect.core.Links
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Seed

/** The drawing and the starting links the app ships (designs/stage-1-foundation.md 4.20, 4.22). */
object BundledDrawing {
    const val DRAWING_PATH = "drawing/car-top.json"
    const val LINKS_PATH = "drawing/links.json"

    fun drawingText(context: Context): String = read(context, DRAWING_PATH)
    fun linksText(context: Context): String = read(context, LINKS_PATH)

    private fun read(context: Context, path: String): String =
        context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Parses the drawing; an invalid shipped drawing is a bug (the check validates it), so this throws. */
    fun parseDrawing(text: String): Drawing = when (val p = Drawing.parse(text)) {
        is Drawing.Companion.Valid -> p.drawing
        is Drawing.Companion.Invalid -> throw IllegalStateException("The drawing is not valid:\n" + p.errors.joinToString("\n"))
    }

    /**
     * Parses the starting link file [text] against the bundled seed [bundledSeedText] and [drawing]; the check
     * proves they agree, so an invalid pair is a bug and this throws. A database made from another seed
     * is still loaded with this file: [RegionLinkLoader] leaves a region not linked when its value is
     * not in the database.
     */
    fun parseLinks(text: String, bundledSeedText: String, drawing: Drawing): LinkData {
        val seed = (Seed.parse(bundledSeedText) as? Seed.Valid)?.seed ?: throw IllegalStateException("The bundled seed is not valid")
        return when (val p = Links.parse(text, seed, drawing)) {
            is Links.Valid -> p.links
            is Links.Invalid -> throw IllegalStateException("The link file is not valid:\n" + p.errors.joinToString("\n"))
        }
    }
}

/**
 * The region-link loader (designs/stage-1-foundation.md 4.23): runs every time the database opens,
 * after seeding, in one transaction. For each region of [drawing], in file order, that has no
 * `region_link` row, it inserts one whose value is the link file's value for that region if that value
 * exists in `tag_value` and its list has role AREA (removed or not), else NULL. Rows that exist are
 * never changed.
 */
object RegionLinkLoader {
    /** What one run did, for tests: rows made and linked, rows made not linked. */
    data class Report(val linked: Int, val notLinked: Int)

    fun run(db: AppDatabase, drawing: Drawing, links: LinkData): Report = db.runInTransaction<Report> {
        val dao = db.regionLinkDao()
        val tags = db.tagDao()
        val have = dao.all().filter { it.drawingId == drawing.id }.map { it.regionId }.toHashSet()
        val startValue = links.links.associate { it.region to it.value }
        var linked = 0
        var notLinked = 0
        for (region in drawing.regions) {
            if (region.id in have) continue
            val wanted = startValue[region.id]
            val value = wanted?.let { tags.getValue(it) }
            val list = value?.let { tags.getList(it.listId) }
            val usable = value != null && list != null && list.role == Role.AREA
            dao.insert(RegionLink(drawing.id, region.id, if (usable) value!!.id else null))
            if (usable) linked++ else notLinked++
        }
        Report(linked, notLinked)
    }
}
