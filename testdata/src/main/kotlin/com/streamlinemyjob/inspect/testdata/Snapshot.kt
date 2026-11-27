package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.Role

/**
 * Every table of the database as plain data, sorted by id, with each finding resolved to the
 * names it shows (designs/stage-1-foundation.md 4.15, "Snapshot"). Two snapshots compare with
 * `assertEquals`; [assertSame] prints only the first five differences. The reader that fills one
 * from the database lives beside the database (`Snapshot.read(db)` in the app's tests), so this
 * module stays free of Android.
 */
data class Snapshot(
    val vehicles: List<VehicleRow>,
    val inspections: List<InspectionRow>,
    val lists: List<ListRow>,
    val values: List<ValueRow>,
    val findings: List<FindingRow>,
    /** Every `region_link` row (4.26, from S1.27). */
    val regionLinks: List<RegionLinkRow> = emptyList(),
    /** Every `photo` row (stage 2 design 4.10, from S2.4). */
    val photos: List<PhotoRow> = emptyList(),
    /** The photo folder's files, path to length, read from the folder (empty when no folder is read). */
    val photoFiles: Map<String, Long> = emptyMap(),
    /** Every `app_setting` row, key to value. */
    val settings: Map<String, String> = emptyMap(),
) {
    data class VehicleRow(val id: String, val regDisplay: String, val regKey: String, val createdAt: Long, val vehicleType: String = "car")

    data class InspectionRow(val id: String, val vehicleId: String, val date: String, val mileage: Int, val createdAt: Long)

    data class ListRow(val id: String, val role: Role, val name: String, val nameKey: String, val position: Int, val removed: Boolean, val vehicleType: String = "car")

    data class ValueRow(val id: String, val listId: String, val name: String, val nameKey: String, val position: Int, val removed: Boolean)

    data class FindingRow(
        val id: String,
        val inspectionId: String,
        val areaValueId: String,
        val damageValueId: String,
        val actionValueId: String?,
        /** "No action" chosen on purpose (4.29): `actionValueId` null and this true; both null and false is not chosen yet. */
        val noAction: Boolean,
        val note: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val areaListName: String?,
        val areaName: String?,
        val damageName: String?,
        val actionName: String?,
        val areaListRemoved: Boolean,
        val areaRemoved: Boolean,
        val damageRemoved: Boolean,
        val actionRemoved: Boolean,
        /** The dot (4.23): all three null (no dot) or all three set. */
        val dotX: Double? = null,
        val dotY: Double? = null,
        val dotDrawing: String? = null,
    )

    /** A `photo` row (stage 2 design 4.4, 4.10): [position] is the one after any moves. */
    data class PhotoRow(
        val id: String,
        val findingId: String,
        val position: Int,
        val relPath: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
        val quality: Int,
        val reduced: Boolean,
        val takenAt: Long,
    )

    /** A `region_link` row (4.23, 4.27): [valueId] null means not linked. */
    data class RegionLinkRow(val drawingId: String, val regionId: String, val valueId: String?)

    companion object {
        /** At most [max] differences between [expected] and [actual], one line each. */
        fun diff(expected: Snapshot, actual: Snapshot, max: Int = 5): List<String> {
            val out = mutableListOf<String>()
            fun <T : Any> table(name: String, e: List<T>, a: List<T>, id: (T) -> String) {
                val eById = e.associateBy(id)
                val aById = a.associateBy(id)
                for (key in (eById.keys + aById.keys).sorted()) {
                    if (out.size >= max) return
                    val x = eById[key]
                    val y = aById[key]
                    when {
                        x == y -> Unit
                        x == null -> out += "$name $key: not expected, found $y"
                        y == null -> out += "$name $key: expected $x, missing"
                        else -> out += "$name $key: expected $x, found $y"
                    }
                }
            }
            table("vehicle", expected.vehicles, actual.vehicles) { it.id }
            table("inspection", expected.inspections, actual.inspections) { it.id }
            table("tag_list", expected.lists, actual.lists) { it.id }
            table("tag_value", expected.values, actual.values) { it.id }
            table("finding", expected.findings, actual.findings) { it.id }
            table("region_link", expected.regionLinks, actual.regionLinks) { "${it.drawingId}/${it.regionId}" }
            table("photo", expected.photos, actual.photos) { it.id }
            for (key in (expected.photoFiles.keys + actual.photoFiles.keys).sorted()) {
                if (out.size >= max) break
                val x = expected.photoFiles[key]
                val y = actual.photoFiles[key]
                when {
                    x == y -> Unit
                    x == null -> out += "photo file $key: not expected, found $y bytes"
                    y == null -> out += "photo file $key: expected $x bytes, missing"
                    else -> out += "photo file $key: expected $x bytes, found $y"
                }
            }
            for (key in (expected.settings.keys + actual.settings.keys).sorted()) {
                if (out.size >= max) break
                if (expected.settings[key] != actual.settings[key]) {
                    out += "app_setting $key: expected ${expected.settings[key]}, found ${actual.settings[key]}"
                }
            }
            return out.take(max)
        }

        /** Fails with the first five differences when the snapshots differ. */
        fun assertSame(expected: Snapshot, actual: Snapshot, message: String = "snapshots differ") {
            if (expected == actual) return
            throw AssertionError("$message; first differences:\n" + diff(expected, actual).joinToString("\n"))
        }
    }
}
