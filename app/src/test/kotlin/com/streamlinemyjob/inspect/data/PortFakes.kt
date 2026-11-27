package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.testdata.PortDot
import com.streamlinemyjob.inspect.testdata.PortResult
import com.streamlinemyjob.inspect.testdata.RepositoryPort
import com.streamlinemyjob.inspect.testdata.Snapshot
import java.time.LocalDate

/** A port that passes every call to [inner]. Tests override the calls they change. */
open class PassThroughPort(val inner: RepositoryPort) : RepositoryPort {
    override fun addVehicle(rawReg: String): PortResult = inner.addVehicle(rawReg)

    override fun addInspection(vehicleId: String, rawDate: String, rawMileage: String, confirmedLower: Boolean): PortResult =
        inner.addInspection(vehicleId, rawDate, rawMileage, confirmedLower)

    override fun addFinding(
        inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: PortDot?,
    ): PortResult = inner.addFinding(inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot)

    override fun regionLinks(): Map<String, String?> = inner.regionLinks()

    override fun linkRegion(regionId: String, valueId: String): PortResult = inner.linkRegion(regionId, valueId)
    override fun unlinkRegion(regionId: String): PortResult = inner.unlinkRegion(regionId)
    override fun renameList(listId: String, rawName: String): PortResult = inner.renameList(listId, rawName)
    override fun addValue(listId: String, rawName: String): PortResult = inner.addValue(listId, rawName)
    override fun addAreaList(rawName: String): PortResult = inner.addAreaList(rawName)
    override fun addPhoto(findingId: String, jpeg: ByteArray, width: Int, height: Int, quality: Int, reduced: Boolean): PortResult =
        inner.addPhoto(findingId, jpeg, width, height, quality, reduced)
    override fun movePhoto(photoId: String, toPosition: Int): PortResult = inner.movePhoto(photoId, toPosition)
    override fun zone(): java.time.ZoneId = inner.zone()
    override fun snapshot(): Snapshot = inner.snapshot()
    override fun setTime(epochMillis: Long) = inner.setTime(epochMillis)
    override fun today(): LocalDate = inner.today()
}

/** Records every write and its result, in order, so a test can see which refusals were hit and confirmed. */
class RecordingPort(inner: RepositoryPort) : PassThroughPort(inner) {
    data class Call(val kind: String, val args: List<String?>, val confirmed: Boolean, val result: PortResult)

    val calls = ArrayList<Call>()

    override fun addVehicle(rawReg: String): PortResult =
        super.addVehicle(rawReg).also { calls += Call("vehicle", listOf(rawReg), false, it) }

    override fun addInspection(vehicleId: String, rawDate: String, rawMileage: String, confirmedLower: Boolean): PortResult =
        super.addInspection(vehicleId, rawDate, rawMileage, confirmedLower)
            .also { calls += Call("inspection", listOf(vehicleId, rawDate, rawMileage), confirmedLower, it) }

    override fun addFinding(
        inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: PortDot?,
    ): PortResult = super.addFinding(inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot)
        .also { calls += Call("finding", listOf(inspectionId, areaValueId, damageValueId, action.toString(), rawNote), confirmedSameArea, it) }

    /**
     * How many times a [kind] call unconfirmed was refused with [code] and the very next call was the
     * same one confirmed and added.
     */
    fun refusedThenConfirmed(kind: String, code: String): Int = calls.zipWithNext().count { (a, b) ->
        a.kind == kind && !a.confirmed && (a.result as? PortResult.Refused)?.code == code &&
            b.kind == kind && b.confirmed && b.args == a.args && b.result is PortResult.Added
    }

    /** Calls made confirmed that were not right after the same call refused unconfirmed. */
    fun confirmedWithoutRefusal(): List<Call> = calls.withIndex().filter { (i, c) ->
        c.confirmed && (i == 0 || calls[i - 1].let { p -> p.kind != c.kind || p.args != c.args || p.confirmed || p.result !is PortResult.Refused })
    }.map { it.value }
}
