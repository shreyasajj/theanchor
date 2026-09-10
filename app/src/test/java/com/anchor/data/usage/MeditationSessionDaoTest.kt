package com.anchor.data.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeditationSessionDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: MeditationSessionDao

    private val dayStart = 1_757_000_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.meditationSessionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an empty table sums to zero rather than null`() = runTest {
        assertThat(dao.secondsSince(dayStart)).isEqualTo(0)
        assertThat(dao.countSince(dayStart)).isEqualTo(0)
    }

    @Test
    fun `it sums today's sitting`() = runTest {
        dao.insert(MeditationSession(startedAtMillis = dayStart + 10 * minute, seconds = 60))
        dao.insert(MeditationSession(startedAtMillis = dayStart + 200 * minute, seconds = 180))

        assertThat(dao.secondsSince(dayStart)).isEqualTo(240)
        assertThat(dao.countSince(dayStart)).isEqualTo(2)
    }

    @Test
    fun `sitting before the day reset does not count toward today`() = runTest {
        dao.insert(MeditationSession(startedAtMillis = dayStart - 30 * minute, seconds = 300))
        dao.insert(MeditationSession(startedAtMillis = dayStart + minute, seconds = 60))

        assertThat(dao.secondsSince(dayStart)).isEqualTo(60)
        assertThat(dao.countSince(dayStart)).isEqualTo(1)
    }

    @Test
    fun `it remembers which app was not opened`() = runTest {
        dao.insert(
            MeditationSession(
                insteadOfPackage = "com.google.android.youtube",
                startedAtMillis = dayStart + minute,
                seconds = 60,
            )
        )
        dao.insert(MeditationSession(startedAtMillis = dayStart + 2 * minute, seconds = 60))

        assertThat(dao.secondsSince(dayStart)).isEqualTo(120)
    }
}
