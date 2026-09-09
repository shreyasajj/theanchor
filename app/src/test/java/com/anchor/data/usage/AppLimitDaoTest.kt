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
class AppLimitDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: AppLimitDao

    private val youtube = "com.google.android.youtube"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.appLimitDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `returns null for an app with no limit configured`() = runTest {
        assertThat(dao.find(youtube)).isNull()
    }

    @Test
    fun `stores and reads back every field`() = runTest {
        dao.upsert(
            AppLimit(
                packageName = youtube,
                dailyMinutes = 30,
                dailyOpens = 5,
                cooldownMinutes = 20,
                sessionMinutes = 10,
                preOpenDelaySeconds = 30,
            )
        )

        val found = dao.find(youtube)!!
        assertThat(found.dailyMinutes).isEqualTo(30)
        assertThat(found.dailyOpens).isEqualTo(5)
        assertThat(found.cooldownMinutes).isEqualTo(20)
        assertThat(found.sessionMinutes).isEqualTo(10)
        assertThat(found.preOpenDelaySeconds).isEqualTo(30)
        assertThat(found.enabled).isTrue()
    }

    @Test
    fun `all fields are optional and default to no limit`() = runTest {
        dao.upsert(AppLimit(packageName = youtube))

        val found = dao.find(youtube)!!
        assertThat(found.dailyMinutes).isNull()
        assertThat(found.dailyOpens).isNull()
        assertThat(found.cooldownMinutes).isNull()
        assertThat(found.sessionMinutes).isNull()
        assertThat(found.preOpenDelaySeconds).isEqualTo(0)
        assertThat(found.hasAnyLimit).isFalse()
    }

    @Test
    fun `hasAnyLimit is true when any single mechanic is set`() {
        val base = AppLimit(packageName = youtube)
        assertThat(base.copy(dailyMinutes = 30).hasAnyLimit).isTrue()
        assertThat(base.copy(dailyOpens = 5).hasAnyLimit).isTrue()
        assertThat(base.copy(cooldownMinutes = 20).hasAnyLimit).isTrue()
        assertThat(base.copy(sessionMinutes = 10).hasAnyLimit).isTrue()
        assertThat(base.copy(preOpenDelaySeconds = 30).hasAnyLimit).isTrue()
    }

    @Test
    fun `upserting the same package replaces rather than duplicating`() = runTest {
        dao.upsert(AppLimit(packageName = youtube, dailyMinutes = 30))
        dao.upsert(AppLimit(packageName = youtube, dailyMinutes = 15))

        assertThat(dao.all()).hasSize(1)
        assertThat(dao.find(youtube)!!.dailyMinutes).isEqualTo(15)
    }

    @Test
    fun `deleting removes the row`() = runTest {
        dao.upsert(AppLimit(packageName = youtube, dailyMinutes = 30))
        dao.delete(youtube)
        assertThat(dao.find(youtube)).isNull()
    }

    @Test
    fun `all returns every configured app`() = runTest {
        dao.upsert(AppLimit(packageName = youtube, dailyMinutes = 30))
        dao.upsert(AppLimit(packageName = "com.instagram.android", dailyOpens = 3))

        assertThat(dao.all().map { it.packageName }).containsExactly(youtube, "com.instagram.android")
    }
}
