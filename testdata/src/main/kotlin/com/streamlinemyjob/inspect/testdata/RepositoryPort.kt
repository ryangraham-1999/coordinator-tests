package com.streamlinemyjob.inspect.testdata

import com.streamlinemyjob.inspect.core.ActionChoice

/**
 * The repository port (designs/stage-1-foundation.md 2, 4.15): the small interface through which the
 * generators write to and read from the database. `testdata` cannot see the app, so it never calls
 * `Repository` directly; the app's shared test code (`RepositoryAdapter`) implements this over the
 * real `Repository`, so every write still goes through all its rules.
 *
 * It has only what the generators need: no deletes, no edits, no reads other than [snapshot] and
 * [today]. The list-editing calls `Mutation` needs ([renameList], [addValue], [addAreaList]) came in S1.12.
 */
interface RepositoryPort {
    /** Repository.addVehicle. */
    fun addVehicle(rawReg: String): PortResult

    /** Repository.addInspection. */
    fun addInspection(vehicleId: String, rawDate: String, rawMileage: String, confirmedLower: Boolean): PortResult

    /** Repository.addFinding. [dot] is null for a finding picked from the lists (4.26, from S1.27). */
    fun addFinding(
        inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: PortDot? = null,
    ): PortResult

    /** Repository.regionLinks (4.26, from S1.27): every region of the drawing with the value it picks, or null for not linked. */
    fun regionLinks(): Map<String, String?>

    /** Repository.linkRegion (4.26, from S1.29). */
    fun linkRegion(regionId: String, valueId: String): PortResult

    /** Repository.unlinkRegion (4.26, from S1.29). */
    fun unlinkRegion(regionId: String): PortResult

    /** Repository.renameList. */
    fun renameList(listId: String, rawName: String): PortResult

    /** Repository.addValue. A removed value brought back counts as `Added` with its id. */
    fun addValue(listId: String, rawName: String): PortResult

    /** Repository.addAreaList. A removed list brought back counts as `Added` with its id. */
    fun addAreaList(rawName: String): PortResult

    /** Repository.addPhoto (stage 2 design 4.10, from S2.4). */
    fun addPhoto(findingId: String, jpeg: ByteArray, width: Int, height: Int, quality: Int, reduced: Boolean): PortResult

    /** Repository.movePhoto (stage 2 design 4.10, from S2.4). */
    fun movePhoto(photoId: String, toPosition: Int): PortResult

    /** The time zone of the Repository's clock, which names a photo by the time it was saved (4.12). */
    fun zone(): java.time.ZoneId

    /** Every table, read from the database now (Snapshot.read), with the photo folder's files if there is one. */
    fun snapshot(): Snapshot

    /** Sets the time the Repository's clock gives from now on, until set again. */
    fun setTime(epochMillis: Long)

    /** The date "today" on the Repository's clock (its own time zone), at the time last set. */
    fun today(): java.time.LocalDate
}

/** A dot, as fractions 0..1 of the drawing's width and height (the app's `Dot`; `testdata` cannot see the app). */
data class PortDot(val x: Double, val y: Double)

/** What a port write returns: the Repository's `Ok(id)` or `Refused(code, detail)`. */
sealed interface PortResult {
    data class Added(val id: String) : PortResult
    data class Refused(val code: String, val detail: String) : PortResult
}

/**
 * The Repository codes the generator expects to see (4.15). `testdata` cannot see the app's
 * `RepoCodes`, so they are written here; an app JVM test asserts each equals its `RepoCodes` constant.
 */
object ExpectedCodes {
    const val MILEAGE_LOWER_UNCONFIRMED = "MILEAGE_LOWER_UNCONFIRMED"
    const val SAME_AREA_UNCONFIRMED = "SAME_AREA_UNCONFIRMED"
    const val PHOTO_LIMIT = "PHOTO_LIMIT"
}
