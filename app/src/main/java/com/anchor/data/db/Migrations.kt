package com.anchor.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations. The database used to be recreated on every schema bump,
 * which threw away the questions and the daily log with each update. Every
 * bump from now on migrates; only the tables that changed are touched.
 */
object Migrations {

    /**
     * Limits became groups (a set of packages, a mode, hours) and early locks
     * are keyed by the limit rather than the package. Both tables are rebuilt
     * and their rows dropped: limits have to be entered again once. The daily
     * log, the questions and the recorded sits are untouched.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `app_limit`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `app_limit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `packages` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                    "`limitMode` TEXT NOT NULL, `dailyMinutes` INTEGER, `dailyOpens` INTEGER, " +
                    "`cooldownMinutes` INTEGER, `sessionMinutes` INTEGER, `preOpenDelaySeconds` INTEGER NOT NULL, " +
                    "`windowStartMinute` INTEGER, `windowEndMinute` INTEGER)"
            )
            db.execSQL("DROP TABLE IF EXISTS `early_lock`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `early_lock` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`subject` TEXT NOT NULL, `atMillis` INTEGER NOT NULL)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4)
}
