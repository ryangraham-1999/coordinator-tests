package com.streamlinemyjob.inspect.data

import java.time.LocalDate

/**
 * What every Repository write returns (designs/stage-1-foundation.md 4.12): [Ok] with its value, or
 * [Refused] with a stable code and a detail. A refused write changes nothing.
 */
sealed interface Result<out T>

/**
 * The write was made. [warning] is set when it was made over a warning the caller confirmed.
 * [restored] is set when `addValue` or `addAreaList` brought back a removed value or list with the
 * same name key instead of making a new one (4.13, "`Restored(id)`"); [value] is then its id.
 */
data class Ok<out T>(val value: T, val warning: Warning? = null, val restored: Boolean = false) : Result<T>

/** The write was refused and nothing changed. */
data class Refused(val code: String, val detail: String = "") : Result<Nothing> {
    override fun toString(): String = if (detail.isEmpty()) code else "$code $detail"
}

/** A warning returned as data, for the UI to ask about (4.9, 4.12). */
sealed interface Warning

/** The vehicle's previous inspection (4.9) had a higher mileage than the one saved. */
data class PreviousMileage(val mileage: Int, val date: LocalDate) : Warning

/** How many inspections and findings a vehicle delete removes (4.12). */
data class VehicleCounts(val inspections: Int, val findings: Int)

/** Which way `moveValue` and `moveList` move one step (4.13). */
enum class Direction { UP, DOWN }

/**
 * One value `offeredValues` gives (4.12, 4.13): [valueRemoved] and [listRemoved] are set only for the
 * current value of a finding that is no longer offered, which is placed first so the editor can show
 * it marked "(removed)".
 */
data class OfferedValue(
    val valueId: String, val listId: String, val name: String,
    val valueRemoved: Boolean = false, val listRemoved: Boolean = false,
) {
    val removed: Boolean get() = valueRemoved || listRemoved
}

/**
 * One list `offeredLists` gives: [removed] is set only for the list of a finding's current value
 * when that list is removed, which is placed first so the editor can show its tab marked "(removed)".
 */
data class OfferedList(val listId: String, val name: String, val removed: Boolean = false)

/** A finding with the names it shows (core's `FindingView`), for the screens (4.12, 4.17). */
data class ResolvedFinding(val finding: Finding, val view: com.streamlinemyjob.inspect.core.FindingView)

/** A dot on the drawing (4.24): fractions of its width and height, 0..1. The drawing id is the bundled drawing's. */
data class Dot(val x: Double, val y: Double)

/** What `resolveTap` says (4.24). */
sealed interface TapResult {
    /** The tap is on a region linked to an offered value. */
    data class Picked(val regionId: String, val valueId: String) : TapResult
    /** The tap is on a region that is not linked. */
    data class NotLinked(val regionId: String) : TapResult
    /** The tap is on a region linked to a removed value, or to a value in a removed list. */
    data class Removed(val regionId: String, val valueId: String) : TapResult
    /** The tap is on no region. */
    data object Outside : TapResult
}

/** The Repository's own refusal codes (4.12); the input codes are in core's `Codes`. */
object RepoCodes {
    const val ROLE_MISMATCH = "ROLE_MISMATCH"
    const val VALUE_REMOVED = "VALUE_REMOVED"
    const val SAME_AREA_UNCONFIRMED = "SAME_AREA_UNCONFIRMED"
    const val MILEAGE_LOWER_UNCONFIRMED = "MILEAGE_LOWER_UNCONFIRMED"

    /** 4.13: a value or list name clashes with a removed one (the message says to restore that one). */
    const val NAME_TAKEN_BY_REMOVED = "NAME_TAKEN_BY_REMOVED"
    const val MOVE_AT_END = "MOVE_AT_END"
    const val LAST_DAMAGE_VALUE = "LAST_DAMAGE_VALUE"
    const val LAST_AREA_VALUE = "LAST_AREA_VALUE"

    /** Only AREA lists can be added. */
    const val ROLE_FIXED = "ROLE_FIXED"

    /** Only AREA lists can be removed (or restored). */
    const val LIST_NOT_REMOVABLE = "LIST_NOT_REMOVABLE"

    /** A restore of a value or list that is not removed (the design gives no code; see the S1.12 note). */
    const val NOT_REMOVED = "NOT_REMOVED"

    /** A move or remove of a list that is already removed (the design gives no code; see the S1.12 note). */
    const val LIST_REMOVED = "LIST_REMOVED"

    /** 4.24: `linkRegion` or `unlinkRegion` with a region id that is not in the drawing. */
    const val REGION_UNKNOWN = "REGION_UNKNOWN"

    /** 4.24: a dot coordinate outside 0..1 or not finite. */
    const val DOT_BAD = "DOT_BAD"

    /** 4.24: a dot that does not resolve to a region linked to the finding's own area value. */
    const val DOT_AREA_MISMATCH = "DOT_AREA_MISMATCH"

    /** The vehicle, inspection, finding, list or value named by id does not exist. */
    const val NOT_FOUND = "NOT_FOUND"
}

/** One row of the Settings screen (4.16): a list with its name, role and number of active values. */
data class SettingsList(
    val id: String,
    val role: com.streamlinemyjob.inspect.core.Role,
    val name: String,
    val removed: Boolean,
    val activeValues: Int,
)

/** One value on the List editor (4.16). */
data class ListValueItem(val id: String, val name: String)

/** What the List editor shows: the list, its active values in order, and its removed values by name. */
data class ListDetail(val list: SettingsList, val active: List<ListValueItem>, val removed: List<ListValueItem>)
