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
class LimitOpenDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: LimitOpenDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.limitOpenDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `since returns one subject's opens from a time, oldest first`() = runTest {
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 30))
        dao.insert(LimitOpen(subject = "limit:1", packageName = "b", atMillis = 10))
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 5))
        dao.insert(LimitOpen(subject = "limit:2", packageName = "a", atMillis = 20))

        val opens = dao.since("limit:1", 10)

        assertThat(opens.map { it.atMillis }).containsExactly(10L, 30L).inOrder()
        assertThat(opens.map { it.packageName }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `prune drops only rows older than the cutoff`() = runTest {
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 5))
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 50))

        dao.prune(beforeMillis = 10)

        assertThat(dao.since("limit:1", 0).map { it.atMillis }).containsExactly(50L)
    }
}
