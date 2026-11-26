package com.streamlinemyjob.inspect.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's database (designs/stage-1-foundation.md 4.11): file `smj.db`, version 3 (4.23; version 2 is 4.29, version 1 is 4.11), schema
 * exported to `app/schemas/` and committed. Foreign keys are switched on explicitly when it opens.
 * From stage 2 on, every version change needs a migration and an upgrade test (RULES.md).
 */
@Database(
    entities = [Vehicle::class, Inspection::class, TagList::class, TagValue::class, Finding::class, RegionLink::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun inspectionDao(): InspectionDao
    abstract fun tagDao(): TagDao
    abstract fun findingDao(): FindingDao
    abstract fun regionLinkDao(): RegionLinkDao

    companion object {
        const val FILE_NAME = "smj.db"

        /** Switches SQLite foreign keys on for every connection Room opens (4.11). */
        val FOREIGN_KEYS_ON = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /**
         * Builds the database on its file [name] (default `smj.db`) with foreign keys on. The caller
         * then runs [SeedLoader.loadIfEmpty] once. Tests pass [allowMainThread] (Robolectric runs
         * tests on the main thread); the app never does.
         */
        fun build(context: Context, name: String = FILE_NAME, allowMainThread: Boolean = false): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addCallback(FOREIGN_KEYS_ON)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .apply { if (allowMainThread) allowMainThreadQueries() }
                .build()
    }
}
