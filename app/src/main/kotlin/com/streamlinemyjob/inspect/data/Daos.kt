package com.streamlinemyjob.inspect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

// DAOs over the 4.11 tables. Only the Repository, SeedLoader and DataCheck call them; the UI calls
// only the Repository (designs/stage-1-foundation.md 4.12).

@Dao
interface VehicleDao {
    @Insert fun insert(vehicle: Vehicle)
    @Update fun update(vehicle: Vehicle)
    @Query("DELETE FROM vehicle WHERE id = :id") fun delete(id: String): Int
    @Query("SELECT * FROM vehicle WHERE id = :id") fun get(id: String): Vehicle?
    @Query("SELECT * FROM vehicle ORDER BY id") fun all(): List<Vehicle>
}

@Dao
interface InspectionDao {
    @Insert fun insert(inspection: Inspection)
    @Update fun update(inspection: Inspection)
    @Query("DELETE FROM inspection WHERE id = :id") fun delete(id: String): Int
    @Query("SELECT * FROM inspection WHERE id = :id") fun get(id: String): Inspection?
    @Query("SELECT * FROM inspection WHERE vehicle_id = :vehicleId") fun forVehicle(vehicleId: String): List<Inspection>
    @Query("SELECT COUNT(*) FROM inspection WHERE vehicle_id = :vehicleId") fun countForVehicle(vehicleId: String): Int
    @Query("SELECT * FROM inspection ORDER BY id") fun all(): List<Inspection>
}

@Dao
interface TagDao {
    @Insert fun insertList(list: TagList)
    @Insert fun insertValue(value: TagValue)
    @Update fun updateList(list: TagList)
    @Update fun updateValue(value: TagValue)
    @Query("SELECT COUNT(*) FROM tag_list") fun listCount(): Int
    @Query("SELECT * FROM tag_list WHERE id = :id") fun getList(id: String): TagList?
    @Query("SELECT * FROM tag_value WHERE id = :id") fun getValue(id: String): TagValue?
    @Query("SELECT * FROM tag_value WHERE list_id = :listId") fun valuesOf(listId: String): List<TagValue>
    @Query("SELECT * FROM tag_list ORDER BY id") fun allLists(): List<TagList>
    @Query("SELECT * FROM tag_value ORDER BY id") fun allValues(): List<TagValue>
}

@Dao
interface FindingDao {
    @Insert fun insert(finding: Finding)
    @Update fun update(finding: Finding)
    @Query("DELETE FROM finding WHERE id = :id") fun delete(id: String): Int
    @Query("SELECT * FROM finding WHERE id = :id") fun get(id: String): Finding?
    @Query("SELECT * FROM finding WHERE inspection_id = :inspectionId") fun forInspection(inspectionId: String): List<Finding>
    @Query(
        "SELECT COUNT(*) FROM finding WHERE inspection_id IN (SELECT id FROM inspection WHERE vehicle_id = :vehicleId)",
    )
    fun countForVehicle(vehicleId: String): Int
    @Query("SELECT * FROM finding ORDER BY id") fun all(): List<Finding>
    /** Findings whose `no_action` is neither 0 nor 1 (`IC_ACTION_STATE`, 4.14); a Boolean column cannot show it. */
    @Query("SELECT id FROM finding WHERE no_action NOT IN (0, 1) ORDER BY id") fun badActionState(): List<String>
}

@Dao
interface RegionLinkDao {
    @Insert fun insert(link: RegionLink)
    @Query("SELECT * FROM region_link ORDER BY drawing_id, region_id") fun all(): List<RegionLink>
    @Query("SELECT * FROM region_link WHERE drawing_id = :drawingId AND region_id = :regionId")
    fun get(drawingId: String, regionId: String): RegionLink?
    @Query("UPDATE region_link SET value_id = :valueId WHERE drawing_id = :drawingId AND region_id = :regionId")
    fun setValue(drawingId: String, regionId: String, valueId: String?)
    /** Findings whose dot columns are not all NULL or all set (`IC_DOT`, 4.14); an entity cannot show a half-set dot. */
    @Query("SELECT id FROM finding WHERE (dot_x IS NULL) + (dot_y IS NULL) + (dot_drawing IS NULL) IN (1, 2) ORDER BY id")
    fun halfDots(): List<String>
}
