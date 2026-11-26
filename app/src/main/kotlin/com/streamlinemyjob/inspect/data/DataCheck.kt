package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Rules
import com.streamlinemyjob.inspect.core.Text
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** One broken rule found by [DataCheck]: its code and the ids involved. */
data class Violation(val code: String, val ids: List<String>) {
    override fun toString(): String = "$code ${ids.joinToString(",")}"
}

/**
 * The integrity check (designs/stage-1-foundation.md 4.14): reads the whole database and returns
 * every violation of the rules the schema cannot hold. Empty means clean. Not shown to the owner
 * in stage 1; tests call it after every write sequence.
 */
object DataCheck {
    const val IC_ROLE_COUNT = "IC_ROLE_COUNT"
    const val IC_FINDING_ROLE = "IC_FINDING_ROLE"
    const val IC_ORPHAN = "IC_ORPHAN"
    const val IC_NAME_KEY = "IC_NAME_KEY"
    const val IC_NAME_DUP = "IC_NAME_DUP"
    const val IC_POSITIONS = "IC_POSITIONS"
    const val IC_REG = "IC_REG"
    const val IC_INSPECTION = "IC_INSPECTION"
    const val IC_NOTE = "IC_NOTE"
    const val IC_AT_LEAST_ONE = "IC_AT_LEAST_ONE"
    const val IC_ACTION_STATE = "IC_ACTION_STATE"
    const val IC_DOT = "IC_DOT"
    const val IC_REGION_LINK = "IC_REGION_LINK"

    /** Every code, in 4.14's table order. */
    val ALL = listOf(
        IC_ROLE_COUNT, IC_FINDING_ROLE, IC_ORPHAN, IC_NAME_KEY, IC_NAME_DUP,
        IC_POSITIONS, IC_REG, IC_INSPECTION, IC_NOTE, IC_AT_LEAST_ONE, IC_ACTION_STATE, IC_DOT, IC_REGION_LINK,
    )

    private val STORED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** [drawing] is the bundled drawing, which `IC_DOT` and `IC_REGION_LINK` check against (4.14). */
    fun run(db: AppDatabase, drawing: Drawing): List<Violation> = db.runInTransaction<List<Violation>> {
        check(
            db.vehicleDao().all(), db.inspectionDao().all(), db.tagDao().allLists(),
            db.tagDao().allValues(), db.findingDao().all(), db.findingDao().badActionState(),
            drawing, db.regionLinkDao().all(), db.regionLinkDao().halfDots(),
        )
    }

    fun check(
        vehicles: List<Vehicle>, inspections: List<Inspection>, lists: List<TagList>,
        values: List<TagValue>, findings: List<Finding>, badActionState: List<String> = emptyList(),
        drawing: Drawing? = null, regionLinks: List<RegionLink> = emptyList(), halfDots: List<String> = emptyList(),
    ): List<Violation> {
        val out = mutableListOf<Violation>()
        fun add(code: String, vararg ids: String) { out += Violation(code, ids.toList()) }

        val listById = lists.associateBy { it.id }
        val valueById = values.associateBy { it.id }
        val vehicleIds = vehicles.map { it.id }.toSet()
        val inspectionIds = inspections.map { it.id }.toSet()

        // IC_ROLE_COUNT
        val byRole = lists.groupBy { it.role }
        for (role in listOf(Role.DAMAGE, Role.ACTION)) {
            val n = byRole[role].orEmpty()
            if (n.size != 1) add(IC_ROLE_COUNT, role.name, *n.map { it.id }.toTypedArray())
            n.filter { it.removed }.forEach { add(IC_ROLE_COUNT, role.name, it.id) }
        }
        if (byRole[Role.AREA].isNullOrEmpty()) add(IC_ROLE_COUNT, Role.AREA.name)

        // IC_FINDING_ROLE (a missing value is IC_ORPHAN's business, not this one's)
        for (f in findings) {
            val slots = listOfNotNull(Role.AREA to f.areaValueId, Role.DAMAGE to f.damageValueId, f.actionValueId?.let { Role.ACTION to it })
            for ((role, valueId) in slots) {
                val list = valueById[valueId]?.let { listById[it.listId] } ?: continue
                if (list.role != role) add(IC_FINDING_ROLE, f.id, valueId)
            }
        }

        // IC_ORPHAN
        inspections.filter { it.vehicleId !in vehicleIds }.forEach { add(IC_ORPHAN, it.id, it.vehicleId) }
        findings.filter { it.inspectionId !in inspectionIds }.forEach { add(IC_ORPHAN, it.id, it.inspectionId) }
        values.filter { it.listId !in listById }.forEach { add(IC_ORPHAN, it.id, it.listId) }
        for (f in findings) {
            listOfNotNull(f.areaValueId, f.damageValueId, f.actionValueId)
                .filter { it !in valueById }.forEach { add(IC_ORPHAN, f.id, it) }
        }

        // IC_NAME_KEY
        fun names(id: String, name: String, nameKey: String) {
            if (nameKey != Text.key(name) || name != Text.clean(name) || Text.codePoints(name) > Rules.NAME_MAX) add(IC_NAME_KEY, id)
        }
        lists.forEach { names(it.id, it.name, it.nameKey) }
        values.forEach { names(it.id, it.name, it.nameKey) }

        // IC_NAME_DUP
        lists.groupBy { it.nameKey }.values.filter { it.size > 1 }.forEach { dup -> add(IC_NAME_DUP, *dup.map { it.id }.sorted().toTypedArray()) }
        values.groupBy { it.listId to it.nameKey }.values.filter { it.size > 1 }
            .forEach { dup -> add(IC_NAME_DUP, *dup.map { it.id }.sorted().toTypedArray()) }

        // IC_POSITIONS
        values.filter { !it.removed }.groupBy { it.listId }.forEach { (listId, active) ->
            if (active.map { it.position }.sorted() != active.indices.toList()) add(IC_POSITIONS, listId)
        }
        lists.filter { !it.removed }.groupBy { it.role }.forEach { (role, active) ->
            if (active.map { it.position }.sorted() != active.indices.toList()) add(IC_POSITIONS, role.name)
        }

        // IC_REG
        for (v in vehicles) {
            if (v.regKey != Reg.key(v.regDisplay) || v.regKey.length !in Rules.REG_KEY_MIN..Rules.REG_KEY_MAX) add(IC_REG, v.id)
        }

        // IC_INSPECTION
        for (i in inspections) {
            if (i.mileage !in 0..Rules.MILEAGE_MAX || !isStoredDate(i.date)) add(IC_INSPECTION, i.id)
        }

        // IC_NOTE
        for (f in findings) {
            val note = f.note ?: continue
            if (note.isEmpty() || Text.codePoints(note) > Rules.NOTE_MAX) add(IC_NOTE, f.id)
        }

        // IC_AT_LEAST_ONE
        val activeValueLists = values.filter { !it.removed }.map { it.listId }.toSet()
        val damageLists = byRole[Role.DAMAGE].orEmpty()
        if (damageLists.none { it.id in activeValueLists }) add(IC_AT_LEAST_ONE, Role.DAMAGE.name)
        val activeAreaLists = byRole[Role.AREA].orEmpty().filter { !it.removed }
        if (activeAreaLists.none { it.id in activeValueLists }) add(IC_AT_LEAST_ONE, Role.AREA.name)

        // IC_ACTION_STATE (4.29): "No action" with an action value, or a no_action that is not 0 or 1
        for (f in findings) if (f.noAction && f.actionValueId != null) add(IC_ACTION_STATE, f.id)
        for (id in badActionState) add(IC_ACTION_STATE, id)

        // IC_DOT (4.14, 4.21): half a dot, a coordinate outside 0..1, another drawing's id, or a dot on no region.
        // A dot whose region now links to another value is not a fault (4.24).
        if (drawing != null) {
            for (id in halfDots) add(IC_DOT, id)
            for (f in findings) {
                val x = f.dotX
                val y = f.dotY
                val d = f.dotDrawing
                if (x == null || y == null || d == null) continue
                val bad = !x.isFinite() || !y.isFinite() || x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0 ||
                    d != drawing.id || Hit.resolveDot(drawing, x, y) == null
                if (bad) add(IC_DOT, f.id)
            }

            // IC_REGION_LINK: a region of the drawing with no row, a row for a region the drawing lacks, a row whose
            // value is missing or not in an AREA list.
            val mine = regionLinks.filter { it.drawingId == drawing.id }
            val rowsOf = mine.map { it.regionId }.toSet()
            drawing.regions.filter { it.id !in rowsOf }.forEach { add(IC_REGION_LINK, it.id) }
            val regionIds = drawing.regions.map { it.id }.toSet()
            mine.filter { it.regionId !in regionIds }.forEach { add(IC_REGION_LINK, it.regionId) }
            for (row in mine) {
                val valueId = row.valueId ?: continue
                val list = valueById[valueId]?.let { listById[it.listId] }
                if (list == null || list.role != Role.AREA) add(IC_REGION_LINK, row.regionId, valueId)
            }
        }

        return out
    }

    private fun isStoredDate(text: String): Boolean = try {
        LocalDate.parse(text, STORED_DATE)
        true
    } catch (e: Exception) {
        false
    }
}
