package com.anchor.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DailyLogDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: DailyLogDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dailyLogDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `returns null when no log exists for the date`() = runTest {
        assertThat(dao.findByDate("2026-09-09")).isNull()
    }

    @Test
    fun `stores and reads back a morning entry`() = runTest {
        dao.upsert(
            DailyLog(
                date = "2026-09-09",
                mission = "Ship the plan",
                avoiding = "The hard conversation",
                morningCompletedAt = 1_757_000_000_000L,
            )
        )

        val found = dao.findByDate("2026-09-09")!!
        assertThat(found.mission).isEqualTo("Ship the plan")
        assertThat(found.avoiding).isEqualTo("The hard conversation")
        assertThat(found.eveningCompletedAt).isNull()
    }

    @Test
    fun `upserting the evening keeps the morning answers on the same row`() = runTest {
        val id = dao.upsert(DailyLog(date = "2026-09-09", mission = "Ship", avoiding = "Email"))

        val existing = dao.findByDate("2026-09-09")!!
        dao.upsert(
            existing.copy(
                led = "Chose the architecture",
                softened = "Thanked a friend",
                faked = "Agreed to a meeting I did not want",
                eveningCompletedAt = 1_757_040_000_000L,
            )
        )

        val merged = dao.findByDate("2026-09-09")!!
        assertThat(merged.id).isEqualTo(id)
        assertThat(merged.mission).isEqualTo("Ship")
        assertThat(merged.led).isEqualTo("Chose the architecture")
        assertThat(dao.recent(10)).hasSize(1)
    }

    @Test
    fun `date is unique so two inserts for one day collapse to one row`() = runTest {
        dao.upsert(DailyLog(date = "2026-09-09", mission = "First"))
        dao.upsert(DailyLog(date = "2026-09-09", mission = "Second"))

        assertThat(dao.recent(10)).hasSize(1)
        assertThat(dao.findByDate("2026-09-09")!!.mission).isEqualTo("Second")
    }

    @Test
    fun `recent returns newest dates first`() = runTest {
        dao.upsert(DailyLog(date = "2026-09-07", mission = "A"))
        dao.upsert(DailyLog(date = "2026-09-09", mission = "C"))
        dao.upsert(DailyLog(date = "2026-09-08", mission = "B"))

        assertThat(dao.recent(2).map { it.date })
            .containsExactly("2026-09-09", "2026-09-08").inOrder()
    }
}
