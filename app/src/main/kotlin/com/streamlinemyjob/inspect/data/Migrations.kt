package com.streamlinemyjob.inspect.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 to 2 (designs/stage-1-foundation.md 4.29), hand-written so the SQL is reviewed. Adds the
 * `no_action` flag, then marks every finding that was saved with no action as "No action": until this
 * version the app had no way to say "not chosen yet", so each of them was a deliberate "No action".
 * Room runs a migration inside one transaction.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE finding ADD COLUMN no_action INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE finding SET no_action = 1 WHERE action_value_id IS NULL")
    }
}

/**
 * Version 2 to 3 (designs/stage-1-foundation.md 4.23, 4.27), hand-written so the SQL is reviewed. Adds the
 * three dot columns to `finding`, the `vehicle_type` column to `vehicle` and `tag_list` (every existing
 * row becomes `car`), and the `region_link` table with its index, written as Room's exported `3.json`
 * writes them. No data changes: every finding has no dot and keeps its action state. The region links
 * themselves are made by [RegionLinkLoader] the first time the version 3 database opens.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE finding ADD COLUMN dot_x REAL")
        db.execSQL("ALTER TABLE finding ADD COLUMN dot_y REAL")
        db.execSQL("ALTER TABLE finding ADD COLUMN dot_drawing TEXT")
        db.execSQL("ALTER TABLE vehicle ADD COLUMN vehicle_type TEXT NOT NULL DEFAULT 'car'")
        db.execSQL("ALTER TABLE tag_list ADD COLUMN vehicle_type TEXT NOT NULL DEFAULT 'car'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `region_link` (`drawing_id` TEXT NOT NULL, `region_id` TEXT NOT NULL, `value_id` TEXT, " +
                "PRIMARY KEY(`drawing_id`, `region_id`), FOREIGN KEY(`value_id`) REFERENCES `tag_value`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_region_link_value_id` ON `region_link` (`value_id`)")
    }
}

/**
 * Version 3 to 4 (designs/stage-2-camera.md 4.4), hand-written so the SQL is reviewed. Adds the `photo` table with its two
 * indexes (one unique, on `rel_path`) and the `app_setting` table, written as Room's exported `4.json` writes them. No other
 * table changes and no data changes: after it both new tables are empty.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `photo` (`id` TEXT NOT NULL, `finding_id` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`rel_path` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `bytes` INTEGER NOT NULL, " +
                "`quality` INTEGER NOT NULL, `reduced` INTEGER NOT NULL, `taken_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`finding_id`) REFERENCES `finding`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_finding_id` ON `photo` (`finding_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photo_rel_path` ON `photo` (`rel_path`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}
