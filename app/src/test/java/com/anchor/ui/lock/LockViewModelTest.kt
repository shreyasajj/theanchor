package com.anchor.ui.lock

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.awaitUntil
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.export.ExportResult
import com.anchor.domain.SubmitResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LockViewModelTest {

    private lateinit var db: AnchorDatabase

    /** Records what was submitted and returns a configurable result. */
    private class FakeSubmit(
        private val result: SubmitResult = SubmitResult(
            log = DailyLog(date = "2026-09-09"),
            export = ExportResult.Written("2026-09-09.md", "content"),
        ),
    ) {
        @Volatile var submitted: Pair<Phase, Map<String, String>>? = null
        val fn: suspend (Phase, Map<String, String>) -> SubmitResult = { phase, answers ->
            submitted = phase to answers
            result
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking { DefaultQuestions.ALL.forEach { db.customQuestionDao().upsert(it) } }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(phase: Phase = Phase.MORNING, submit: FakeSubmit = FakeSubmit()): LockViewModel {
        val vm = LockViewModel(phase = phase, questionDao = db.customQuestionDao(), submitCheckIn = submit.fn)
        awaitUntil { vm.state.value.questions.isNotEmpty() }
        return vm
    }

    private suspend fun LockViewModel.submitAndWait() {
        submit()
        awaitUntil { state.value.submitted || !state.value.isSubmitting }
    }

    @Test
    fun `loads the questions for its phase, in order`() = runTest {
        val vm = viewModel()
        assertThat(vm.state.value.questions.map { it.slotKey })
            .containsExactly(SlotKey.MISSION, SlotKey.AVOIDING).inOrder()
    }

    @Test
    fun `the evening view model loads three questions`() = runTest {
        assertThat(viewModel(phase = Phase.EVENING).state.value.questions).hasSize(3)
    }

    @Test
    fun `cannot submit until every question has a non-blank answer`() = runTest {
        val vm = viewModel()
        assertThat(vm.state.value.canSubmit).isFalse()

        vm.onAnswerChanged(SlotKey.MISSION, "Ship the plan")
        assertThat(vm.state.value.canSubmit).isFalse()
        assertThat(vm.state.value.answeredCount).isEqualTo(1)

        vm.onAnswerChanged(SlotKey.AVOIDING, "The invoice")
        assertThat(vm.state.value.canSubmit).isTrue()
    }

    @Test
    fun `whitespace-only answers do not enable submission`() = runTest {
        val vm = viewModel()
        vm.onAnswerChanged(SlotKey.MISSION, "Ship")
        vm.onAnswerChanged(SlotKey.AVOIDING, "    ")
        assertThat(vm.state.value.canSubmit).isFalse()
    }

    @Test
    fun `submit passes trimmed answers keyed by slot`() = runTest {
        val submit = FakeSubmit()
        val vm = viewModel(submit = submit)

        vm.onAnswerChanged(SlotKey.MISSION, "  Ship the plan  ")
        vm.onAnswerChanged(SlotKey.AVOIDING, "The invoice")
        vm.submitAndWait()

        assertThat(submit.submitted!!.first).isEqualTo(Phase.MORNING)
        assertThat(submit.submitted!!.second).containsExactly(
            SlotKey.MISSION, "Ship the plan",
            SlotKey.AVOIDING, "The invoice",
        )
    }

    @Test
    fun `submitted becomes true so the activity can finish`() = runTest {
        val vm = viewModel()
        vm.onAnswerChanged(SlotKey.MISSION, "a")
        vm.onAnswerChanged(SlotKey.AVOIDING, "b")

        assertThat(vm.state.value.submitted).isFalse()
        vm.submitAndWait()
        assertThat(vm.state.value.submitted).isTrue()
        assertThat(vm.state.value.exportWarning).isNull()
    }

    @Test
    fun `submit is a no-op when the form is incomplete`() = runTest {
        val submit = FakeSubmit()
        val vm = viewModel(submit = submit)

        vm.submit()

        assertThat(submit.submitted).isNull()
        assertThat(vm.state.value.submitted).isFalse()
        assertThat(vm.state.value.isSubmitting).isFalse()
    }

    @Test
    fun `an export failure still lets the user through, with a warning`() = runTest {
        val submit = FakeSubmit(SubmitResult(DailyLog(date = "2026-09-09"), ExportResult.NoDirectoryConfigured))
        val vm = viewModel(submit = submit)
        vm.onAnswerChanged(SlotKey.MISSION, "a")
        vm.onAnswerChanged(SlotKey.AVOIDING, "b")
        vm.submitAndWait()

        assertThat(vm.state.value.submitted).isTrue()
        assertThat(vm.state.value.exportWarning).isNotNull()
    }

    @Test
    fun `a user-added question is rendered and required like any other`() = runTest {
        db.customQuestionDao().upsert(
            CustomQuestion(phase = Phase.MORNING, slotKey = "custom:zz", prompt = "Who do I owe a reply?", sortOrder = 5)
        )
        val vm = viewModel()

        assertThat(vm.state.value.questions).hasSize(3)
        vm.onAnswerChanged(SlotKey.MISSION, "a")
        vm.onAnswerChanged(SlotKey.AVOIDING, "b")
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.onAnswerChanged("custom:zz", "Priya")
        assertThat(vm.state.value.canSubmit).isTrue()
    }
}
