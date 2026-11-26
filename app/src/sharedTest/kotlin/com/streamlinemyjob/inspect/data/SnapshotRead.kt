package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.testdata.Snapshot

/** Reads every table into a [Snapshot] (designs/stage-1-foundation.md 4.15). */
fun Snapshot.Companion.read(db: AppDatabase): Snapshot = db.runInTransaction<Snapshot> {
    val lists = db.tagDao().allLists()
    val values = db.tagDao().allValues()
    val listById = lists.associateBy { it.id }
    val valueById = values.associateBy { it.id }
    Snapshot(
        vehicles = db.vehicleDao().all().map { Snapshot.VehicleRow(it.id, it.regDisplay, it.regKey, it.createdAt, it.vehicleType) },
        inspections = db.inspectionDao().all().map { Snapshot.InspectionRow(it.id, it.vehicleId, it.date, it.mileage, it.createdAt) },
        lists = lists.map { Snapshot.ListRow(it.id, it.role, it.name, it.nameKey, it.position, it.removed, it.vehicleType) },
        values = values.map { Snapshot.ValueRow(it.id, it.listId, it.name, it.nameKey, it.position, it.removed) },
        findings = db.findingDao().all().map { f ->
            val area = valueById[f.areaValueId]
            val areaList = area?.let { listById[it.listId] }
            val damage = valueById[f.damageValueId]
            val action = f.actionValueId?.let { valueById[it] }
            Snapshot.FindingRow(
                f.id, f.inspectionId, f.areaValueId, f.damageValueId, f.actionValueId, f.noAction, f.note, f.createdAt, f.updatedAt,
                areaListName = areaList?.name, areaName = area?.name, damageName = damage?.name, actionName = action?.name,
                areaListRemoved = areaList?.removed ?: false, areaRemoved = area?.removed ?: false,
                damageRemoved = damage?.removed ?: false, actionRemoved = action?.removed ?: false,
                dotX = f.dotX, dotY = f.dotY, dotDrawing = f.dotDrawing,
            )
        },
        regionLinks = db.regionLinkDao().all().map { Snapshot.RegionLinkRow(it.drawingId, it.regionId, it.valueId) },
    )
}
