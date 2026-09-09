package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.export.CheckInExporter
import com.anchor.data.export.ExportResult
import com.anchor.data.export.JoplinExporter
import com.anchor.data.export.MarkdownExporter
import com.anchor.data.export.NoJoplinApi
import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SubmitCheckInTest {

    private lateinit var db: AnchorDatabase
    private lateinit var recorded: MutableList<Pair<Phase, DailyLog>>

    private inner class RecordingExporter : CheckInExporter(
        markdown = object : MarkdownExporter({ null }) {},
        joplin = object : JoplinExporter(NoJoplinApi) {},
    ) {
        override suspend fun export(
            log: DailyLog,
            questions: List<CustomQuestion>,
            phase: Phase,
            settings: AnchorSettings,
        ): ExportResult {
            recorded += phase to log
            return ExportResult.Written("${log.date}.md", "content")
        }
    }

    /** 2026-09-09 07:00 local. */
    private val clock = Clock.fixed(Instant.parse("2026-09-09T14:00:00Z"), ZoneId.of("America/Los_Angeles"))

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        DefaultQuestions.ALL.forEach { db.customQuestionDao().upsert(it) }
        recorded = mutableListOf()
    }

    @After
    fun tearDown() = db.close()

    private fun submit(clock: Clock = this.clock, exporter: CheckInExporter = RecordingExporter()) = SubmitCheckIn(
        dailyLogDao = db.dailyLogDao(),
        questionDao = db.customQuestionDao(),
        exporter = exporter,
        anchorDate = AnchorDate(clock),
        settingsProvider = { AnchorSettings() },
    )

    @Test
    fun `saves morning answers to the named columns and stamps the timestamp`() = runTest {
        val result = submit()(
            Phase.MORNING,
            mapOf(SlotKey.MISSION to "Ship the plan", SlotKey.AVOIDING to "The invoice"),
        )

        val saved = db.dailyLogDao().findByDate("2026-09-09")!!
        assertThat(saved.mission).isEqualTo("Ship the plan")
        assertThat(saved.avoiding).isEqualTo("The invoice")
        assertThat(saved.morningCompletedAt).isEqualTo(clock.millis())
        assertThat(saved.eveningCompletedAt).isNull()
        assertThat(result.export).isInstanceOf(ExportResult.Written::class.java)
    }

    @Test
    fun `an evening submission merges into the same row as the morning`() = runTest {
        val s = submit()
        s(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship", SlotKey.AVOIDING to "Email"))
        s(Phase.EVENING, mapOf(SlotKey.LED to "Decided", SlotKey.SOFTENED to "Called", SlotKey.FAKED to "Nodded"))

        assertThat(db.dailyLogDao().recent(10)).hasSize(1)
        val saved = db.dailyLogDao().findByDate("2026-09-09")!!
        assertThat(saved.mission).isEqualTo("Ship")
        assertThat(saved.led).isEqualTo("Decided")
        assertThat(saved.morningCompletedAt).isNotNull()
        assertThat(saved.eveningCompletedAt).isNotNull()
    }

    @Test
    fun `a custom question's answer round-trips through extras`() = runTest {
        db.customQuestionDao().upsert(
            CustomQuestion(phase = Phase.MORNING, slotKey = "custom:zz", prompt = "Who?", sortOrder = 9)
        )

        submit()(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship", "custom:zz" to "Priya"))

        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.extraAnswersJson).contains("Priya")
    }

    @Test
    fun `blank answers are stored as-is rather than dropped`() = runTest {
        submit()(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship", SlotKey.AVOIDING to ""))
        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.avoiding).isEmpty()
    }

    @Test
    fun `it exports the phase that was submitted, with the merged log`() = runTest {
        val s = submit()
        s(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship"))
        s(Phase.EVENING, mapOf(SlotKey.LED to "Decided"))

        assertThat(recorded.map { it.first }).containsExactly(Phase.MORNING, Phase.EVENING).inOrder()
        assertThat(recorded.last().second.mission).isEqualTo("Ship")
    }

    @Test
    fun `the evening submission uses the evening anchor date`() = runTest {
        // 01:00 local on the 10th -> the night of the 9th.
        val lateClock = Clock.fixed(Instant.parse("2026-09-10T08:00:00Z"), ZoneId.of("America/Los_Angeles"))

        submit(clock = lateClock)(Phase.EVENING, mapOf(SlotKey.LED to "Decided"))

        assertThat(db.dailyLogDao().findByDate("2026-09-09")).isNotNull()
        assertThat(db.dailyLogDao().findByDate("2026-09-10")).isNull()
    }

    @Test
    fun `an export failure still persists the answers`() = runTest {
        val failing = object : CheckInExporter(
            markdown = object : MarkdownExporter({ null }) {},
            joplin = object : JoplinExporter(NoJoplinApi) {},
        ) {
            override suspend fun export(
                log: DailyLog, questions: List<CustomQuestion>, phase: Phase, settings: AnchorSettings,
            ) = ExportResult.Failed("no folder")
        }

        val result = submit(exporter = failing)(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship"))

        assertThat(result.export).isInstanceOf(ExportResult.Failed::class.java)
        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.mission).isEqualTo("Ship")
        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.morningCompletedAt).isNotNull()
    }
}
