package com.anchor.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.usage.AppLimit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Updating the app used to wipe the database, questions and all. These tests
 * build a version-3 database by hand, open it with the current schema, and
 * check that the questions and the daily log came through.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before
    fun clean() { context.deleteDatabase(name) }

    @After
    fun tearDown() { context.deleteDatabase(name) }

    /** Version 3 as it was shipped: limits keyed by package, locks by package. */
    private fun createVersion3() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE `daily_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `mission` TEXT, `avoiding` TEXT, `led` TEXT, `softened` TEXT, `faked` TEXT, `extraAnswersJson` TEXT, `morningCompletedAt` INTEGER, `eveningCompletedAt` INTEGER)")
                        db.execSQL("CREATE UNIQUE INDEX `index_daily_log_date` ON `daily_log` (`date`)")
                        db.execSQL("CREATE TABLE `custom_question` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `phase` TEXT NOT NULL, `slotKey` TEXT NOT NULL, `prompt` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE `app_limit` (`packageName` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `dailyMinutes` INTEGER, `dailyOpens` INTEGER, `cooldownMinutes` INTEGER, `sessionMinutes` INTEGER, `preOpenDelaySeconds` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
                        db.execSQL("CREATE TABLE `early_lock` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `atMillis` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE `meditation_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `insteadOfPackage` TEXT, `startedAtMillis` INTEGER NOT NULL, `seconds` INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO custom_question (phase, slotKey, prompt, sortOrder, enabled) VALUES ('MORNING', 'custom:abc', 'What would make today good?', 2, 1)")
                        db.execSQL("INSERT INTO daily_log (date, mission, morningCompletedAt) VALUES ('2026-09-13', 'ship it', 1)")
                        db.execSQL("INSERT INTO app_limit (packageName, enabled, dailyOpens, preOpenDelaySeconds) VALUES ('com.x', 1, 3, 0)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
    }

    private fun open(): AnchorDatabase = Room.databaseBuilder(context, AnchorDatabase::class.java, name)
        .addMigrations(*Migrations.ALL)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `questions and the daily log survive the move from 3 to 4`() = runTest {
        createVersion3()
        val db = open()
        try {
            val questions = db.customQuestionDao().list(Phase.MORNING)
            assertThat(questions.map { it.prompt }).contains("What would make today good?")
            assertThat(db.dailyLogDao().findByDate("2026-09-13")!!.mission).isEqualTo("ship it")
        } finally {
            db.close()
        }
    }

    @Test
    fun `limits are rebuilt in the new shape and usable`() = runTest {
        createVersion3()
        val db = open()
        try {
            assertThat(db.appLimitDao().all()).isEmpty()
            db.appLimitDao().upsert(AppLimit(packageName = "com.y", dailyOpens = 2))
            assertThat(db.appLimitDao().find("com.y")!!.dailyOpens).isEqualTo(2)
            db.earlyLockDao().insert(com.anchor.data.usage.EarlyLock(subject = "limit:1", atMillis = 1L))
            assertThat(db.earlyLockDao().since("limit:1", 0)).containsExactly(1L)
        } finally {
            db.close()
        }
    }
}
