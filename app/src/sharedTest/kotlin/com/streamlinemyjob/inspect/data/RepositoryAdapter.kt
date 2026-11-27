package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.photo.PhotoFolder
import com.streamlinemyjob.inspect.testdata.PortDot
import com.streamlinemyjob.inspect.testdata.PortResult
import com.streamlinemyjob.inspect.testdata.RepositoryPort
import com.streamlinemyjob.inspect.testdata.Snapshot
import java.time.LocalDate

/**
 * The repository adapter (designs/stage-1-foundation.md 2, 4.15): makes the real [Repository] a
 * [RepositoryPort]. The only code that joins the two. It makes its own [SetClock], builds
 * `Repository(db, clock, drawing)` and passes each call straight through: `Ok(id)` → `Added(id)`,
 * `Refused(code, detail)` → `Refused(code, detail)`. The lower-mileage warning that comes with an
 * `Ok` is not passed on. It holds no state of its own besides the clock. With a [folder] (stage 2, S2.4) its Repository saves photos
 * there and its snapshot also lists the folder's files.
 */
class RepositoryAdapter(private val db: AppDatabase, drawing: Drawing? = null, private val folder: PhotoFolder? = null) : RepositoryPort {
    private val clock = SetClock()
    private val repository = Repository(db, clock, drawing, folder)

    override fun addVehicle(rawReg: String): PortResult = port(repository.addVehicle(rawReg))

    override fun addInspection(vehicleId: String, rawDate: String, rawMileage: String, confirmedLower: Boolean): PortResult =
        port(repository.addInspection(vehicleId, rawDate, rawMileage, confirmedLower))

    override fun addFinding(
        inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: PortDot?,
    ): PortResult = port(
        repository.addFinding(inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot?.let { Dot(it.x, it.y) }),
    )

    override fun regionLinks(): Map<String, String?> = repository.regionLinks()

    override fun linkRegion(regionId: String, valueId: String): PortResult = port(repository.linkRegion(regionId, valueId))

    override fun unlinkRegion(regionId: String): PortResult = port(repository.unlinkRegion(regionId))

    override fun renameList(listId: String, rawName: String): PortResult = port(repository.renameList(listId, rawName))

    override fun addValue(listId: String, rawName: String): PortResult = port(repository.addValue(listId, rawName))

    override fun addAreaList(rawName: String): PortResult = port(repository.addAreaList(rawName))

    override fun addPhoto(findingId: String, jpeg: ByteArray, width: Int, height: Int, quality: Int, reduced: Boolean): PortResult =
        port(repository.addPhoto(findingId, jpeg, width, height, quality, reduced))

    override fun movePhoto(photoId: String, toPosition: Int): PortResult = port(repository.movePhoto(photoId, toPosition))

    override fun zone(): java.time.ZoneId = clock.zone

    override fun snapshot(): Snapshot = Snapshot.read(db, folder)

    override fun setTime(epochMillis: Long) = clock.set(epochMillis)

    override fun today(): LocalDate = LocalDate.now(clock)

    private fun port(r: Result<String>): PortResult = when (r) {
        is Ok -> PortResult.Added(r.value)
        is Refused -> PortResult.Refused(r.code, r.detail)
    }
}
