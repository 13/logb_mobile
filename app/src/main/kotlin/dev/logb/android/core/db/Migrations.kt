package dev.logb.android.core.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

object Migrations {
    /**
     * Tags and own types (logb 0.8.0). Ends by asking for a bootstrap: rows the server already
     * holds carry tags and types this mirror could not store before, and only a snapshot brings
     * them in (spec, phase 3 "Data").
     */
    private val SQL_1_2 = listOf(
        "ALTER TABLE `objects` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'",
        "ALTER TABLE `activities` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'",
        "CREATE TABLE IF NOT EXISTS `object_types` (`uuid` TEXT NOT NULL, `server_id` INTEGER, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `categories` TEXT NOT NULL, `counter_unit` TEXT, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, `deleted_at` TEXT, PRIMARY KEY(`uuid`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_object_types_server_id` ON `object_types` (`server_id`)",
        "CREATE INDEX IF NOT EXISTS `index_object_types_deleted_at` ON `object_types` (`deleted_at`)",
        "UPDATE `sync_state` SET `bootstrap_needed` = 1",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        // The app opens Room with the bundled driver (connection overload); the test helper uses SupportSQLite.
        override fun migrate(connection: SQLiteConnection) = SQL_1_2.forEach { connection.execSQL(it) }
        override fun migrate(db: SupportSQLiteDatabase) = SQL_1_2.forEach { db.execSQL(it) }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
