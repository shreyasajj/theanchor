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
    private val instagram = "com.instagram.android"

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
                name = "Social",
                packages = setOf(youtube, instagram),
                limitMode = LimitMode.TIME,
                dailyMinutes = 30,
                dailyOpens = 5,
                cooldownMinutes = 20,
                sessionMinutes = 10,
                preOpenDelaySeconds = 30,
            )
        )

        val found = dao.find(youtube)!!
        assertThat(found.name).isEqualTo("Social")
        assertThat(found.packages).containsExactly(youtube, instagram)
        assertThat(found.limitMode).isEqualTo(LimitMode.TIME)
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
        assertThat(base.copy(limitMode = LimitMode.TIME, dailyMinutes = 30).hasAnyLimit).isTrue()
        assertThat(base.copy(dailyOpens = 5).hasAnyLimit).isTrue()
        assertThat(base.copy(cooldownMinutes = 20).hasAnyLimit).isTrue()
        assertThat(base.copy(sessionMinutes = 10).hasAnyLimit).isTrue()
        assertThat(base.copy(preOpenDelaySeconds = 30).hasAnyLimit).isTrue()
    }

    @Test
    fun `only the budget for the chosen mode counts as a limit`() {
        val base = AppLimit(packageName = youtube)
        assertThat(base.copy(limitMode = LimitMode.OPENS, dailyMinutes = 30).hasAnyLimit).isFalse()
        assertThat(base.copy(limitMode = LimitMode.TIME, dailyOpens = 5).hasAnyLimit).isFalse()
        assertThat(base.copy(limitMode = LimitMode.OPENS, dailyOpens = 5).effectiveDailyOpens).isEqualTo(5)
        assertThat(base.copy(limitMode = LimitMode.TIME, dailyOpens = 5).effectiveDailyOpens).isNull()
    }

    @Test
    fun `upserting with the same id replaces rather than duplicating`() = runTest {
        val id = dao.upsert(AppLimit(packageName = youtube, dailyOpens = 3))
        dao.upsert(AppLimit(packageName = youtube, dailyOpens = 1).copy(id = id))

        assertThat(dao.all()).hasSize(1)
        assertThat(dao.find(youtube)!!.dailyOpens).isEqualTo(1)
    }

    @Test
    fun `deleting by package removes the whole group`() = runTest {
        dao.upsert(AppLimit(packages = setOf(youtube, instagram), dailyOpens = 3))
        dao.delete(youtube)
        assertThat(dao.find(youtube)).isNull()
        assertThat(dao.find(instagram)).isNull()
    }

    @Test
    fun `find locates the group by any member`() = runTest {
        val id = dao.upsert(AppLimit(name = "Social", packages = setOf(youtube, instagram), dailyOpens = 3))
        assertThat(dao.find(instagram)!!.id).isEqualTo(id)
        assertThat(dao.find(youtube)!!.subject).isEqualTo("limit:$id")
        assertThat(dao.find("com.other")).isNull()
    }

    @Test
    fun `all returns every configured limit`() = runTest {
        dao.upsert(AppLimit(packageName = youtube, dailyOpens = 3))
        dao.upsert(AppLimit(packageName = instagram, dailyOpens = 3))

        assertThat(dao.all().flatMap { it.packages }).containsExactly(youtube, instagram)
    }
}
