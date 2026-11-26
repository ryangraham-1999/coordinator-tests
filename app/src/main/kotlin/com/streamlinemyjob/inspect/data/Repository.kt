package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice
import com.streamlinemyjob.inspect.core.Codes
import com.streamlinemyjob.inspect.core.Dates
import com.streamlinemyjob.inspect.core.Drawing
import com.streamlinemyjob.inspect.core.FindingText
import com.streamlinemyjob.inspect.core.FindingView
import com.streamlinemyjob.inspect.core.Hit
import com.streamlinemyjob.inspect.core.Mileage
import com.streamlinemyjob.inspect.core.Reg
import com.streamlinemyjob.inspect.core.Role
import com.streamlinemyjob.inspect.core.Text
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The one class over the DAOs (designs/stage-1-foundation.md 4.12): vehicles, inspections and
 * findings. Every write validates first and returns [Ok] or [Refused]; a refused write changes
 * nothing. Every write, its checks included, runs in one transaction. Times come from [clock]. List
 * editing (4.13) and `offeredValues` are below the findings.
 *
 * [drawing] is the bundled drawing (4.20): taps, dots and region links (4.24) need it; a repository made
 * without one refuses to touch them (throws) because every dot is resolved against a drawing.
 *
 * Calls block: the UI calls them off the main thread.
 */
class Repository(private val db: AppDatabase, private val clock: Clock, private val drawing: Drawing? = null) {
    private val vehicles = db.vehicleDao()
    private val inspections = db.inspectionDao()
    private val tags = db.tagDao()
    private val findings = db.findingDao()
    private val links = db.regionLinkDao()

    private fun newId(): String = UUID.randomUUID().toString()

    private fun <T> write(block: () -> Result<T>): Result<T> = db.runInTransaction<Result<T>> { block() }

    // ---- Vehicles ----

    /** 4.8 rules; `REG_TAKEN` carries the existing vehicle's id as its detail. */
    fun addVehicle(rawReg: String): Result<String> = write {
        when (val reg = Reg.checkNew(rawReg, vehicles.all().associate { it.regKey to it.id })) {
            is Reg.Refused -> refused(reg)
            is Reg.Ok -> {
                val id = newId()
                vehicles.insert(Vehicle(id, reg.display, reg.key, clock.millis()))
                Ok(id)
            }
        }
    }

    /** As [addVehicle]; a key equal to the vehicle's own current key is fine (changing only spacing). */
    fun renameVehicle(id: String, rawReg: String): Result<String> = write {
        val vehicle = vehicles.get(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        val others = vehicles.all().filter { it.id != id }.associate { it.regKey to it.id }
        when (val reg = Reg.checkNew(rawReg, others)) {
            is Reg.Refused -> refused(reg)
            is Reg.Ok -> {
                vehicles.update(vehicle.copy(regDisplay = reg.display, regKey = reg.key))
                Ok(id)
            }
        }
    }

    /** Deletes the vehicle; its inspections and findings go with it (foreign key cascade). */
    fun deleteVehicle(id: String): Result<String> = write {
        if (vehicles.delete(id) == 0) Refused(RepoCodes.NOT_FOUND, id) else Ok(id)
    }

    /** What [deleteVehicle] would remove, for the screen to show first. */
    fun countsForVehicle(id: String): VehicleCounts =
        VehicleCounts(inspections.countForVehicle(id), findings.countForVehicle(id))

    fun vehicle(id: String): Vehicle? = vehicles.get(id)

    /**
     * 4.8 search: vehicles whose reg key contains the query's key (empty matches all; a bad
     * character matches none). Sorted by latest inspection date, newest first; then vehicles with
     * no inspection by `created_at`, newest first; ties by reg key.
     */
    fun searchVehicles(query: String): List<Vehicle> {
        val key = Reg.searchKey(query) ?: return emptyList()
        val latest = inspections.all().groupBy { it.vehicleId }.mapValues { (_, list) -> list.maxOf { it.date } }
        val (inspected, never) = vehicles.all().filter { it.regKey.contains(key) }.partition { it.id in latest }
        return inspected.sortedWith(compareByDescending<Vehicle> { latest.getValue(it.id) }.thenBy { it.regKey }) +
            never.sortedWith(compareByDescending<Vehicle> { it.createdAt }.thenBy { it.regKey })
    }

    // ---- Inspections ----

    /**
     * 4.9 rules. A mileage lower than the previous inspection's is `MILEAGE_LOWER_UNCONFIRMED`
     * (detail `<mileage> <yyyy-mm-dd>` of the previous one) unless [confirmedLower]; then it is saved
     * and the warning comes back as data on [Ok].
     */
    fun addInspection(vehicleId: String, rawDate: String, rawMileage: String, confirmedLower: Boolean = false): Result<String> = write {
        if (vehicles.get(vehicleId) == null) return@write Refused(RepoCodes.NOT_FOUND, vehicleId)
        val createdAt = clock.millis()
        checkInspection(vehicleId, rawDate, rawMileage, createdAt, null, confirmedLower) { date, mileage, warning ->
            val id = newId()
            inspections.insert(Inspection(id, vehicleId, Dates.toStored(date), mileage, createdAt))
            Ok(id, warning)
        }
    }

    /** As [addInspection], for an existing inspection; its own `created_at` places it among the others. */
    fun editInspection(id: String, rawDate: String, rawMileage: String, confirmedLower: Boolean = false): Result<String> = write {
        val old = inspections.get(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        checkInspection(old.vehicleId, rawDate, rawMileage, old.createdAt, id, confirmedLower) { date, mileage, warning ->
            inspections.update(old.copy(date = Dates.toStored(date), mileage = mileage))
            Ok(id, warning)
        }
    }

    private inline fun checkInspection(
        vehicleId: String, rawDate: String, rawMileage: String, createdAt: Long, excludingId: String?,
        confirmedLower: Boolean, save: (LocalDate, Int, Warning?) -> Result<String>,
    ): Result<String> {
        val date = when (val d = Dates.parse(rawDate, LocalDate.now(clock))) {
            is Dates.Refused -> return Refused(d.problem.code, d.problem.detail)
            is Dates.Ok -> d.date
        }
        val mileage = when (val m = Mileage.parse(rawMileage)) {
            is Mileage.Refused -> return Refused(m.problem.code, m.problem.detail)
            is Mileage.Ok -> m.miles
        }
        val previous = previousMileage(vehicleId, date, createdAt, excludingId)
        val warning = previous?.takeIf { mileage < it.mileage }
        if (warning != null && !confirmedLower) {
            return Refused(RepoCodes.MILEAGE_LOWER_UNCONFIRMED, "${warning.mileage} ${Dates.toStored(warning.date)}")
        }
        return save(date, mileage, warning)
    }

    /**
     * The vehicle's previous inspection (4.9): the latest by (date, created time) strictly before
     * ([date], [createdAt]), leaving out [excludingInspectionId]. Null when there is none.
     */
    fun previousMileage(vehicleId: String, date: LocalDate, createdAt: Long, excludingInspectionId: String?): PreviousMileage? {
        val stored = Dates.toStored(date)
        return inspections.forVehicle(vehicleId)
            .filter { it.id != excludingInspectionId }
            .filter { it.date < stored || (it.date == stored && it.createdAt < createdAt) }
            .maxWithOrNull(compareBy<Inspection> { it.date }.thenBy { it.createdAt })
            ?.let { PreviousMileage(it.mileage, Dates.fromStored(it.date)) }
    }

    /** Deletes the inspection; its findings go with it (foreign key cascade). */
    fun deleteInspection(id: String): Result<String> = write {
        if (inspections.delete(id) == 0) Refused(RepoCodes.NOT_FOUND, id) else Ok(id)
    }

    fun inspection(id: String): Inspection? = inspections.get(id)

    /** A vehicle's inspections: newest date first, then newest `created_at`. */
    fun inspectionsForVehicle(vehicleId: String): List<Inspection> =
        inspections.forVehicle(vehicleId).sortedWith(
            compareByDescending<Inspection> { it.date }.thenByDescending { it.createdAt }.thenBy { it.id },
        )

    // ---- Findings ----

    /**
     * 4.12 (C3, C4): the area, damage and (if a `Value`) action values must be of their role
     * (`ROLE_MISMATCH area|damage|action`) and offered (`VALUE_REMOVED <id>`); the note follows 4.7;
     * another finding in the inspection on the same area value is `SAME_AREA_UNCONFIRMED <its id>`
     * unless [confirmedSameArea]. `NoAction` and `NotChosen` are stored as 4.29 says and never refused.
     * [dot] (4.24): null saves no dot; otherwise it must pass [DOT_BAD] and [DOT_AREA_MISMATCH] after the
     * rules above.
     */
    fun addFinding(
        inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: Dot? = null,
    ): Result<String> = write {
        if (inspections.get(inspectionId) == null) return@write Refused(RepoCodes.NOT_FOUND, inspectionId)
        checkFinding(null, inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot) { note ->
            val id = newId()
            val now = clock.millis()
            findings.insert(
                Finding(
                    id, inspectionId, areaValueId, damageValueId, action.valueId(), action == ActionChoice.NoAction, note, now, now,
                    dot?.x, dot?.y, dot?.let { drawing().id },
                ),
            )
            Ok(id)
        }
    }

    /**
     * As [addFinding], for an existing finding, except each value may also be the finding's own
     * current value for that place even if that value is removed (4.13). Changing to a different
     * removed value is `VALUE_REMOVED`. [dot] is the dot the finding has after the edit (4.24): null removes
     * it, so a caller that keeps a finding's dot passes the finding's own dot back. If the area value and
     * the dot are both unchanged the dot is not checked again (the owner may have re-linked that region
     * since); if either changed, [addFinding]'s dot rules apply.
     */
    fun editFinding(
        id: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: Dot? = null,
    ): Result<String> = write {
        val old = findings.get(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        checkFinding(old, old.inspectionId, areaValueId, damageValueId, action, rawNote, confirmedSameArea, dot) { note ->
            findings.update(
                old.copy(
                    areaValueId = areaValueId, damageValueId = damageValueId, actionValueId = action.valueId(),
                    noAction = action == ActionChoice.NoAction,
                    note = note, updatedAt = clock.millis(),
                    dotX = dot?.x, dotY = dot?.y, dotDrawing = dot?.let { d -> if (old.sameDot(d)) old.dotDrawing else drawing().id },
                ),
            )
            Ok(id)
        }
    }

    private inline fun checkFinding(
        old: Finding?, inspectionId: String, areaValueId: String, damageValueId: String, action: ActionChoice,
        rawNote: String?, confirmedSameArea: Boolean, dot: Dot?, save: (String?) -> Result<String>,
    ): Result<String> {
        val slots = listOfNotNull(
            Triple(Role.AREA, areaValueId, old?.areaValueId),
            Triple(Role.DAMAGE, damageValueId, old?.damageValueId),
            action.valueId()?.let { Triple(Role.ACTION, it, old?.actionValueId) },
        )
        val resolved = slots.map { (role, valueId, current) ->
            val value = tags.getValue(valueId)
            val list = value?.let { tags.getList(it.listId) }
            if (value == null || list == null || list.role != role) {
                return Refused(RepoCodes.ROLE_MISMATCH, role.name.lowercase())
            }
            Triple(value, list, current)
        }
        for ((value, list, current) in resolved) {
            val offered = !value.removed && !list.removed
            if (!offered && value.id != current) return Refused(RepoCodes.VALUE_REMOVED, value.id)
        }
        val note = Text.checkNote(rawNote)
        note.problem?.let { return Refused(it.code, it.detail) }
        if (!confirmedSameArea) {
            val other = findings.forInspection(inspectionId)
                .filter { it.id != old?.id && it.areaValueId == areaValueId }
                .minWithOrNull(compareBy<Finding> { it.createdAt }.thenBy { it.id })
            if (other != null) return Refused(RepoCodes.SAME_AREA_UNCONFIRMED, other.id)
        }
        if (dot != null) checkDot(old, areaValueId, dot)?.let { return it }
        return save(note.cleaned)
    }

    /** 4.24's dot rules; null when the dot is fine. An unchanged area and dot on an edit are not checked again. */
    private fun checkDot(old: Finding?, areaValueId: String, dot: Dot): Refused? {
        if (old != null && old.areaValueId == areaValueId && old.sameDot(dot)) return null
        if (!dot.x.isFinite() || !dot.y.isFinite() || dot.x < 0.0 || dot.x > 1.0 || dot.y < 0.0 || dot.y > 1.0) {
            return Refused(RepoCodes.DOT_BAD, "${dot.x} ${dot.y}")
        }
        val tap = resolveTap(dot.x, dot.y)
        if (tap !is TapResult.Picked || tap.valueId != areaValueId) return Refused(RepoCodes.DOT_AREA_MISMATCH, tap.toString())
        return null
    }

    private fun Finding.sameDot(d: Dot): Boolean = dotX == d.x && dotY == d.y && dotDrawing == drawing().id

    private fun drawing(): Drawing = drawing ?: throw IllegalStateException("This Repository was made without a drawing")

    // ---- Taps and region links (4.24) ----

    /**
     * What a tap at the dot ([dotX], [dotY], fractions 0..1 of the drawing) does: [TapResult.Picked] when it
     * resolves to a region linked to an offered value, [TapResult.NotLinked], [TapResult.Removed] (the value,
     * or its list, is removed) or [TapResult.Outside] (no region). Points outside 0..1 or not finite are Outside.
     */
    fun resolveTap(dotX: Double, dotY: Double): TapResult = db.runInTransaction<TapResult> {
        val d = drawing()
        val region = (if (dotX.isFinite() && dotY.isFinite()) Hit.resolveDot(d, dotX, dotY) else null)
            ?: return@runInTransaction TapResult.Outside
        val valueId = links.get(d.id, region)?.valueId ?: return@runInTransaction TapResult.NotLinked(region)
        val value = tags.getValue(valueId)
        val list = value?.let { tags.getList(it.listId) }
        if (value == null || list == null || value.removed || list.removed) TapResult.Removed(region, valueId)
        else TapResult.Picked(region, valueId)
    }

    /**
     * The area as 4.17 writes it, `<list name>[ (removed)] › <area name>[ (removed)]`, for the finding editor's status line
     * (4.25), or null if there is no such value. Works for removed values too.
     */
    fun areaLabel(valueId: String): String? = db.runInTransaction<String?> {
        val value = tags.getValue(valueId) ?: return@runInTransaction null
        val list = tags.getList(value.listId) ?: return@runInTransaction null
        fun marked(name: String, removed: Boolean) = if (removed) name + FindingText.REMOVED else name
        marked(list.name, list.removed) + " › " + marked(value.name, value.removed)
    }

    /** Every region of the drawing with the value it picks, or null for not linked (4.24). */
    fun regionLinks(): Map<String, String?> {
        val d = drawing()
        return links.all().filter { it.drawingId == d.id }.associate { it.regionId to it.valueId }
    }

    /**
     * Links [regionId] to the area value [valueId] (4.24). Refused, changing nothing: `REGION_UNKNOWN` (not a region of the
     * drawing), `ROLE_MISMATCH area` (not a value of an area list), `VALUE_REMOVED` (the value or its list is removed).
     * Linking a region to the value it already has is `Ok` and changes nothing.
     */
    fun linkRegion(regionId: String, valueId: String): Result<String> = write {
        val d = drawing()
        if (d.regions.none { it.id == regionId }) return@write Refused(RepoCodes.REGION_UNKNOWN, regionId)
        val value = tags.getValue(valueId)
        val list = value?.let { tags.getList(it.listId) }
        if (value == null || list == null || list.role != Role.AREA) return@write Refused(RepoCodes.ROLE_MISMATCH, "area")
        if (value.removed || list.removed) return@write Refused(RepoCodes.VALUE_REMOVED, valueId)
        setLink(d.id, regionId, valueId)
        Ok(regionId)
    }

    /** Sets [regionId] to not linked (4.24): `REGION_UNKNOWN` as above; a region that is not linked stays so (`Ok`). */
    fun unlinkRegion(regionId: String): Result<String> = write {
        val d = drawing()
        if (d.regions.none { it.id == regionId }) return@write Refused(RepoCodes.REGION_UNKNOWN, regionId)
        setLink(d.id, regionId, null)
        Ok(regionId)
    }

    private fun setLink(drawingId: String, regionId: String, valueId: String?) {
        val row = links.get(drawingId, regionId)
        when {
            row == null -> links.insert(RegionLink(drawingId, regionId, valueId))
            row.valueId != valueId -> links.setValue(drawingId, regionId, valueId)
        }
    }

    private fun ActionChoice.valueId(): String? = (this as? ActionChoice.Value)?.valueId

    fun deleteFinding(id: String): Result<String> = write {
        if (findings.delete(id) == 0) Refused(RepoCodes.NOT_FOUND, id) else Ok(id)
    }

    fun finding(id: String): Finding? = findings.get(id)

    /** An inspection's findings, by `created_at`. */
    fun findingsForInspection(inspectionId: String): List<Finding> =
        findings.forInspection(inspectionId).sortedWith(compareBy<Finding> { it.createdAt }.thenBy { it.id })

    // ---- Offered values (4.12) ----

    /**
     * The active values of active lists of [role], in list order then value order. If [currentValueId]
     * is a value of that role that is no longer offered (it, or its list, is removed), it is placed
     * first with its removed flags set, so the editor can show it as the current choice.
     */
    fun offeredValues(role: Role, currentValueId: String? = null): List<OfferedValue> = db.runInTransaction<List<OfferedValue>> {
        val lists = tags.allLists().filter { it.role == role && !it.removed }.sortedBy { it.position }
        val values = tags.allValues().filter { !it.removed }.groupBy { it.listId }
        val offered = lists.flatMap { l ->
            values[l.id].orEmpty().sortedBy { it.position }.map { OfferedValue(it.id, l.id, it.name) }
        }
        val current = currentValueId?.let { tags.getValue(it) }
        val currentList = current?.let { tags.getList(it.listId) }
        if (current != null && currentList != null && currentList.role == role && (current.removed || currentList.removed)) {
            listOf(OfferedValue(current.id, current.listId, current.name, current.removed, currentList.removed)) + offered
        } else {
            offered
        }
    }

    /**
     * The active lists of [role], in list order, plus the list of [currentValueId] first and marked
     * removed if that list is removed (the area-list tabs of the finding editor, 4.16).
     */
    fun offeredLists(role: Role, currentValueId: String? = null): List<OfferedList> = db.runInTransaction<List<OfferedList>> {
        val lists = tags.allLists().filter { it.role == role && !it.removed }.sortedBy { it.position }
            .map { OfferedList(it.id, it.name) }
        val currentList = currentValueId?.let { tags.getValue(it) }?.let { tags.getList(it.listId) }
        if (currentList != null && currentList.role == role && currentList.removed) {
            listOf(OfferedList(currentList.id, currentList.name, removed = true)) + lists
        } else {
            lists
        }
    }

    // ---- Settings screens (4.16) ----

    /**
     * Every list for the Settings screen: active area lists in order, then removed area lists, then the
     * damage list, then the action list. Each with its number of active values.
     */
    fun settingsLists(): List<SettingsList> = db.runInTransaction<List<SettingsList>> {
        val values = tags.allValues().filter { !it.removed }.groupingBy { it.listId }.eachCount()
        fun row(l: TagList) = SettingsList(l.id, l.role, l.name, l.removed, values[l.id] ?: 0)
        val lists = tags.allLists()
        val area = lists.filter { it.role == Role.AREA }
        area.filter { !it.removed }.sortedBy { it.position }.map(::row) +
            area.filter { it.removed }.sortedBy { it.nameKey }.map(::row) +
            lists.filter { it.role == Role.DAMAGE }.sortedBy { it.position }.map(::row) +
            lists.filter { it.role == Role.ACTION }.sortedBy { it.position }.map(::row)
    }

    /** One list for the List editor: its active values in order and its removed values by name; null if it does not exist. */
    fun listDetail(id: String): ListDetail? = db.runInTransaction<ListDetail?> {
        val list = tags.getList(id) ?: return@runInTransaction null
        val values = tags.valuesOf(id)
        val active = values.filter { !it.removed }.sortedBy { it.position }
        ListDetail(
            SettingsList(list.id, list.role, list.name, list.removed, active.size),
            active.map { ListValueItem(it.id, it.name) },
            values.filter { it.removed }.sortedBy { it.nameKey }.map { ListValueItem(it.id, it.name) },
        )
    }

    // ---- Findings with their names (4.17) ----

    /** An inspection's findings, by `created_at`, each with the names it shows now. */
    fun resolvedFindingsForInspection(inspectionId: String): List<ResolvedFinding> = db.runInTransaction<List<ResolvedFinding>> {
        findingsForInspection(inspectionId).map { ResolvedFinding(it, view(it)) }
    }

    /** One finding with the names it shows now, or null if it does not exist. */
    fun resolvedFinding(id: String): ResolvedFinding? = db.runInTransaction<ResolvedFinding?> {
        findings.get(id)?.let { ResolvedFinding(it, view(it)) }
    }

    private fun view(f: Finding): FindingView {
        val area = tags.getValue(f.areaValueId)!!
        val areaList = tags.getList(area.listId)!!
        val damage = tags.getValue(f.damageValueId)!!
        val action = f.actionValueId?.let { tags.getValue(it)!! }
        return FindingView(
            areaListName = areaList.name, areaListRemoved = areaList.removed,
            areaName = area.name, areaRemoved = area.removed,
            damageName = damage.name, damageRemoved = damage.removed,
            actionName = action?.name, actionRemoved = action?.removed ?: false, noAction = f.noAction,
            note = f.note,
        )
    }

    // ---- List editing: values (4.13, C5, C6) ----

    /**
     * 4.7 name rules. An active value in the list with the same key is `NAME_TAKEN`; a removed one is
     * restored (to the end of the active values, keeping its name) and the result is `Ok(id, restored =
     * true)`; otherwise a new value (UUID id) goes at the end.
     */
    fun addValue(listId: String, rawName: String): Result<String> = write {
        if (tags.getList(listId) == null) return@write Refused(RepoCodes.NOT_FOUND, listId)
        val name = Text.checkName(rawName)
        name.problems.firstOrNull()?.let { return@write Refused(it.code, it.detail) }
        val values = tags.valuesOf(listId)
        val same = values.firstOrNull { it.nameKey == name.key }
        val end = values.count { !it.removed }
        when {
            same == null -> {
                val id = newId()
                tags.insertValue(TagValue(id, listId, name.cleaned, name.key, end))
                Ok(id)
            }
            !same.removed -> Refused(Codes.NAME_TAKEN, same.id)
            else -> {
                tags.updateValue(same.copy(removed = false, position = end))
                Ok(same.id, restored = true)
            }
        }
    }

    /**
     * 4.7. The same key as the value itself (a case change only) is fine; the same key as another
     * active value is `NAME_TAKEN`, as a removed one `NAME_TAKEN_BY_REMOVED` (restore that one instead).
     */
    fun renameValue(id: String, rawName: String): Result<String> = write {
        val value = tags.getValue(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        val name = Text.checkName(rawName)
        name.problems.firstOrNull()?.let { return@write Refused(it.code, it.detail) }
        val clash = tags.valuesOf(value.listId).firstOrNull { it.id != id && it.nameKey == name.key }
        if (clash != null) {
            return@write Refused(if (clash.removed) RepoCodes.NAME_TAKEN_BY_REMOVED else Codes.NAME_TAKEN, clash.id)
        }
        tags.updateValue(value.copy(name = name.cleaned, nameKey = name.key))
        Ok(id)
    }

    /** Swaps the value with its neighbour among the list's active values; the ends are `MOVE_AT_END`. */
    fun moveValue(id: String, direction: Direction): Result<String> = write {
        val value = tags.getValue(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        if (value.removed) return@write Refused(RepoCodes.VALUE_REMOVED, id)
        val active = tags.valuesOf(value.listId).filter { !it.removed }.sortedBy { it.position }
        val from = active.indexOfFirst { it.id == id }
        val to = if (direction == Direction.UP) from - 1 else from + 1
        if (to !in active.indices) return@write Refused(RepoCodes.MOVE_AT_END, id)
        tags.updateValue(active[from].copy(position = active[to].position))
        tags.updateValue(active[to].copy(position = active[from].position))
        Ok(id)
    }

    /**
     * Sets `removed` and renumbers the list's remaining active values 0..n-1. Refused if it would leave
     * the damage list with no active value (`LAST_DAMAGE_VALUE`) or no active value in any active area
     * list (`LAST_AREA_VALUE`). The action list may be emptied.
     */
    fun removeValue(id: String): Result<String> = write {
        val value = tags.getValue(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        if (value.removed) return@write Refused(RepoCodes.VALUE_REMOVED, id)
        val list = tags.getList(value.listId) ?: return@write Refused(RepoCodes.NOT_FOUND, value.listId)
        val active = tags.valuesOf(list.id).filter { !it.removed }
        if (list.role == Role.DAMAGE && active.size == 1) return@write Refused(RepoCodes.LAST_DAMAGE_VALUE, id)
        if (list.role == Role.AREA && !list.removed && activeAreaValues(excludingValueId = id) == 0) {
            return@write Refused(RepoCodes.LAST_AREA_VALUE, id)
        }
        tags.updateValue(value.copy(removed = true))
        active.filter { it.id != id }.sortedBy { it.position }.forEachIndexed { i, v ->
            if (v.position != i) tags.updateValue(v.copy(position = i))
        }
        Ok(id)
    }

    /**
     * Sets `removed = 0` and puts the value at the end of the active values. Refused if its name key
     * now clashes with an active value (`NAME_TAKEN`); the (list, key) index makes that unreachable
     * today, but the rule is kept as the design words it.
     */
    fun restoreValue(id: String): Result<String> = write {
        val value = tags.getValue(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        if (!value.removed) return@write Refused(RepoCodes.NOT_REMOVED, id)
        val siblings = tags.valuesOf(value.listId).filter { it.id != id && !it.removed }
        siblings.firstOrNull { it.nameKey == value.nameKey }?.let { return@write Refused(Codes.NAME_TAKEN, it.id) }
        tags.updateValue(value.copy(removed = false, position = siblings.size))
        Ok(id)
    }

    /** Active values in active area lists, leaving out [excludingValueId] and the values of [excludingListId]. */
    private fun activeAreaValues(excludingValueId: String? = null, excludingListId: String? = null): Int {
        val lists = tags.allLists().filter { it.role == Role.AREA && !it.removed && it.id != excludingListId }.map { it.id }.toSet()
        return tags.allValues().count { !it.removed && it.listId in lists && it.id != excludingValueId }
    }

    // ---- List editing: lists (4.13) ----

    /** 4.7. List name keys are unique across all lists, active and removed: a clash is `NAME_TAKEN`. */
    fun renameList(id: String, rawName: String): Result<String> = write {
        val list = tags.getList(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        val name = Text.checkName(rawName)
        name.problems.firstOrNull()?.let { return@write Refused(it.code, it.detail) }
        tags.allLists().firstOrNull { it.id != id && it.nameKey == name.key }?.let { return@write Refused(Codes.NAME_TAKEN, it.id) }
        tags.updateList(list.copy(name = name.cleaned, nameKey = name.key))
        Ok(id)
    }

    /**
     * Adds a new AREA list at the end, with no values. Same key as a removed area list: that list is
     * restored instead (`Ok(id, restored = true)`). Only AREA lists can be added (`ROLE_FIXED`).
     */
    fun addAreaList(rawName: String): Result<String> = addList(Role.AREA, rawName)

    /** [addAreaList] with the role as an argument, so that `ROLE_FIXED` has a way to be reached. */
    fun addList(role: Role, rawName: String): Result<String> = write {
        if (role != Role.AREA) return@write Refused(RepoCodes.ROLE_FIXED, role.name)
        val name = Text.checkName(rawName)
        name.problems.firstOrNull()?.let { return@write Refused(it.code, it.detail) }
        val lists = tags.allLists()
        val same = lists.firstOrNull { it.nameKey == name.key }
        val end = lists.count { it.role == Role.AREA && !it.removed }
        when {
            same == null -> {
                val id = newId()
                tags.insertList(TagList(id, Role.AREA, name.cleaned, name.key, end))
                Ok(id)
            }
            same.role == Role.AREA && same.removed -> {
                tags.updateList(same.copy(removed = false, position = end))
                Ok(same.id, restored = true)
            }
            else -> Refused(Codes.NAME_TAKEN, same.id)
        }
    }

    /** Swaps the list with its neighbour among the active lists of the same role. */
    fun moveList(id: String, direction: Direction): Result<String> = write {
        val list = tags.getList(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        if (list.removed) return@write Refused(RepoCodes.LIST_REMOVED, id)
        val active = tags.allLists().filter { it.role == list.role && !it.removed }.sortedBy { it.position }
        val from = active.indexOfFirst { it.id == id }
        val to = if (direction == Direction.UP) from - 1 else from + 1
        if (to !in active.indices) return@write Refused(RepoCodes.MOVE_AT_END, id)
        tags.updateList(active[from].copy(position = active[to].position))
        tags.updateList(active[to].copy(position = active[from].position))
        Ok(id)
    }

    /**
     * AREA lists only (`LIST_NOT_REMOVABLE`). Refused if no active value would remain in any active
     * area list (`LAST_AREA_VALUE`). Its values keep their own `removed` flags; while the list is
     * removed none of them is offered.
     */
    fun removeAreaList(id: String): Result<String> = write {
        val list = tags.getList(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        if (list.role != Role.AREA) return@write Refused(RepoCodes.LIST_NOT_REMOVABLE, id)
        if (list.removed) return@write Refused(RepoCodes.LIST_REMOVED, id)
        if (activeAreaValues(excludingListId = id) == 0) return@write Refused(RepoCodes.LAST_AREA_VALUE, id)
        tags.updateList(list.copy(removed = true))
        tags.allLists().filter { it.role == Role.AREA && !it.removed && it.id != id }
            .sortedBy { it.position }.forEachIndexed { i, l -> if (l.position != i) tags.updateList(l.copy(position = i)) }
        Ok(id)
    }

    /** Puts a removed area list back at the end of the area lists. */
    fun restoreAreaList(id: String): Result<String> = write {
        val list = tags.getList(id) ?: return@write Refused(RepoCodes.NOT_FOUND, id)
        if (list.role != Role.AREA) return@write Refused(RepoCodes.LIST_NOT_REMOVABLE, id)
        if (!list.removed) return@write Refused(RepoCodes.NOT_REMOVED, id)
        val end = tags.allLists().count { it.role == Role.AREA && !it.removed }
        tags.updateList(list.copy(removed = false, position = end))
        Ok(id)
    }

    private fun refused(reg: Reg.Refused): Refused =
        if (reg.problem.code == Codes.REG_TAKEN) Refused(Codes.REG_TAKEN, reg.existingVehicleId ?: "")
        else Refused(reg.problem.code, reg.problem.detail)
}
