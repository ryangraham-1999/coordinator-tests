package com.streamlinemyjob.inspect.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamlinemyjob.inspect.core.Role

// Database schema, version 1 (designs/stage-1-foundation.md 4.11). All ids are TEXT: UUID strings
// for anything made in the app, seed ids for seeded lists and values. Times are epoch milliseconds UTC.

@Entity(tableName = "vehicle", indices = [Index(value = ["reg_key"], unique = true)])
data class Vehicle(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "reg_display") val regDisplay: String,
    @ColumnInfo(name = "reg_key") val regKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The vehicle's type (4.27, version 3): a lowercase word; only `car` has a drawing and lists in stage 1. */
    @ColumnInfo(name = "vehicle_type", defaultValue = "'car'") val vehicleType: String = DEFAULT_VEHICLE_TYPE,
)

/** The type every vehicle and list has until the owner changes it (4.27). */
const val DEFAULT_VEHICLE_TYPE = "car"

@Entity(
    tableName = "inspection",
    foreignKeys = [
        ForeignKey(entity = Vehicle::class, parentColumns = ["id"], childColumns = ["vehicle_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("vehicle_id")],
)
data class Inspection(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String,
    /** `yyyy-mm-dd` (4.9). */
    val date: String,
    /** 0..999,999 (4.9). */
    val mileage: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "tag_list", indices = [Index(value = ["name_key"], unique = true)])
data class TagList(
    @PrimaryKey val id: String,
    /** AREA, DAMAGE or ACTION, stored as its name. */
    val role: Role,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    /** Order among lists of the same role. */
    val position: Int,
    /** Only AREA lists may be removed. */
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false,
    /** The vehicle type this list belongs to (4.27, version 3). */
    @ColumnInfo(name = "vehicle_type", defaultValue = "'car'") val vehicleType: String = DEFAULT_VEHICLE_TYPE,
)

@Entity(
    tableName = "tag_value",
    foreignKeys = [
        ForeignKey(entity = TagList::class, parentColumns = ["id"], childColumns = ["list_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("list_id"), Index(value = ["list_id", "name_key"], unique = true)],
)
data class TagValue(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "list_id") val listId: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    /** Order of active values in the list; a removed value's position means nothing. */
    val position: Int,
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false,
)

@Entity(
    tableName = "finding",
    foreignKeys = [
        ForeignKey(entity = Inspection::class, parentColumns = ["id"], childColumns = ["inspection_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagValue::class, parentColumns = ["id"], childColumns = ["area_value_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TagValue::class, parentColumns = ["id"], childColumns = ["damage_value_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = TagValue::class, parentColumns = ["id"], childColumns = ["action_value_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("inspection_id"), Index("area_value_id"), Index("damage_value_id"), Index("action_value_id")],
)
data class Finding(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "inspection_id") val inspectionId: String,
    @ColumnInfo(name = "area_value_id") val areaValueId: String,
    @ColumnInfo(name = "damage_value_id") val damageValueId: String,
    @ColumnInfo(name = "action_value_id") val actionValueId: String?,
    /** 1 = "No action" chosen on purpose; NULL action and 0 = not chosen yet (4.29). Never 1 with an action. */
    @ColumnInfo(name = "no_action", defaultValue = "0") val noAction: Boolean,
    /** 1..500 code points, or NULL for no note (4.7). */
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** The dot (4.23, version 3): fractions 0..1 of the drawing's width and height; all three NULL or all three set. */
    @ColumnInfo(name = "dot_x") val dotX: Double? = null,
    @ColumnInfo(name = "dot_y") val dotY: Double? = null,
    /** The id of the drawing the dot is on (C20). */
    @ColumnInfo(name = "dot_drawing") val dotDrawing: String? = null,
)

/**
 * What a region of a drawing picks (4.23, 4.27): keyed by the drawing's id and the region's id. A NULL
 * [valueId] means not linked. Rows are never deleted: unlinking sets [valueId] to NULL.
 */
@Entity(
    tableName = "region_link",
    primaryKeys = ["drawing_id", "region_id"],
    foreignKeys = [
        ForeignKey(entity = TagValue::class, parentColumns = ["id"], childColumns = ["value_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("value_id")],
)
data class RegionLink(
    @ColumnInfo(name = "drawing_id") val drawingId: String,
    @ColumnInfo(name = "region_id") val regionId: String,
    @ColumnInfo(name = "value_id") val valueId: String?,
)

/**
 * One photo saved on one finding (designs/stage-2-camera.md 4.4, version 4): one row here plus one file in the
 * photo folder. [relPath] is the path inside the folder (`<reg key>/<file name>`, 4.12), stored when the photo is
 * saved and never worked out again, because the reg, date and area it is made from can change later (C16).
 * [position] is 0..n-1 within the finding; 0 is shown first. [quality] is 60..85; [reduced] is 4.3 step 5.
 */
@Entity(
    tableName = "photo",
    foreignKeys = [
        ForeignKey(entity = Finding::class, parentColumns = ["id"], childColumns = ["finding_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("finding_id"), Index(value = ["rel_path"], unique = true)],
)
data class Photo(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "finding_id") val findingId: String,
    val position: Int,
    @ColumnInfo(name = "rel_path") val relPath: String,
    /** Pixels, as stored. */
    val width: Int,
    val height: Int,
    /** The file's length. */
    val bytes: Int,
    val quality: Int,
    /** 0 or 1 (4.3 step 5). */
    val reduced: Boolean,
    /** Epoch ms UTC, from the Repository's clock. */
    @ColumnInfo(name = "taken_at") val takenAt: Long,
)

/** A small key/value setting (4.4, version 4). Stage 2 has one key, [PHOTO_FOLDER]. */
@Entity(tableName = "app_setting")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String,
) {
    companion object {
        /** The picked photo folder's tree URI string (4.12): the only URI the database stores. */
        const val PHOTO_FOLDER = "photo_folder"
    }
}
