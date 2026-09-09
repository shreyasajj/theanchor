package com.anchor.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomQuestionDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: CustomQuestionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.customQuestionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `defaults contain the two morning and three evening questions`() {
        val morning = DefaultQuestions.ALL.filter { it.phase == Phase.MORNING }
        val evening = DefaultQuestions.ALL.filter { it.phase == Phase.EVENING }

        assertThat(morning.map { it.slotKey })
            .containsExactly(SlotKey.MISSION, SlotKey.AVOIDING).inOrder()
        assertThat(evening.map { it.slotKey })
            .containsExactly(SlotKey.LED, SlotKey.SOFTENED, SlotKey.FAKED).inOrder()
        assertThat(morning.first().prompt).isEqualTo("What is my mission today?")
    }

    @Test
    fun `lists only the requested phase, ordered by sortOrder`() = runTest {
        DefaultQuestions.ALL.forEach { dao.upsert(it) }

        assertThat(dao.list(Phase.MORNING).map { it.prompt })
            .containsExactly("What is my mission today?", "What am I currently avoiding?")
            .inOrder()
        assertThat(dao.list(Phase.EVENING)).hasSize(3)
    }

    @Test
    fun `disabled questions are excluded from list`() = runTest {
        DefaultQuestions.ALL.forEach { dao.upsert(it) }
        val first = dao.list(Phase.MORNING).first()
        dao.upsert(first.copy(enabled = false))

        assertThat(dao.list(Phase.MORNING).map { it.slotKey }).containsExactly(SlotKey.AVOIDING)
    }

    @Test
    fun `a user-added question gets a unique custom slot key`() = runTest {
        val a = SlotKey.custom()
        val b = SlotKey.custom()
        assertThat(a).startsWith("custom:")
        assertThat(a).isNotEqualTo(b)

        dao.upsert(
            CustomQuestion(phase = Phase.MORNING, slotKey = a, prompt = "Who do I owe a reply?", sortOrder = 99)
        )
        assertThat(dao.list(Phase.MORNING).map { it.slotKey }).containsExactly(a)
    }

    @Test
    fun `deleting a question removes it`() = runTest {
        DefaultQuestions.ALL.forEach { dao.upsert(it) }
        dao.delete(dao.list(Phase.EVENING).first())
        assertThat(dao.list(Phase.EVENING)).hasSize(2)
    }

    @Test
    fun `an on-disk database seeds the five default questions on create`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("seed-test.db")
        val seeded = Room
            .databaseBuilder(context, AnchorDatabase::class.java, "seed-test.db")
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DefaultQuestions.seed(db)
            })
            .allowMainThreadQueries()
            .build()

        assertThat(seeded.customQuestionDao().count()).isEqualTo(5)
        assertThat(seeded.customQuestionDao().list(Phase.EVENING)).hasSize(3)
        seeded.close()
        context.deleteDatabase("seed-test.db")
    }
}
