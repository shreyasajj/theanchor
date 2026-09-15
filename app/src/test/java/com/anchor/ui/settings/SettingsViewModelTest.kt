package com.anchor.ui.settings

import com.anchor.data.usage.LimitMode
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.awaitUntil
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimit
import com.anchor.ui.settings.sections.LimitSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var db: AnchorDatabase
    private lateinit var file: File
    private lateinit var repo: SettingsRepository
    @Volatile private var rescheduleCount = 0
    private val youtube = "com.google.android.youtube"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AnchorDatabase::class.java).allowMainThreadQueries().build()
        runBlocking { DefaultQuestions.ALL.forEach { db.customQuestionDao().upsert(it) } }
        file = File(context.cacheDir, "settings-vm-${System.nanoTime()}.preferences_pb")
        repo = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file }
        )
        rescheduleCount = 0
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        settingsRepository = repo,
        questionDao = db.customQuestionDao(),
        appLimitDao = db.appLimitDao(),
        onScheduleChanged = { rescheduleCount++ },
    )

    @Test
    fun `exposes the current settings`() = runTest {
        val vm = viewModel()
        awaitUntil { vm.settings.value.morningStartMinute == 5 * 60 }
        assertThat(vm.settings.value.morningStartMinute).isEqualTo(5 * 60)
    }

    @Test
    fun `updating a setting persists it`() = runTest {
        viewModel().updateSettings { it.copy(haBaseUrl = "http://ha.local:8123") }
        awaitUntil { repo.current().haBaseUrl == "http://ha.local:8123" }
    }

    @Test
    fun `changing the morning start reschedules the alarm`() = runTest {
        viewModel().updateSettings { it.copy(morningStartMinute = 6 * 60) }
        awaitUntil { repo.current().morningStartMinute == 6 * 60 }
        awaitUntil { rescheduleCount == 1 }
    }

    @Test
    fun `changing an unrelated setting does not reschedule`() = runTest {
        viewModel().updateSettings { it.copy(joplinToken = "abc") }
        awaitUntil { repo.current().joplinToken == "abc" }
        assertThat(rescheduleCount).isEqualTo(0)
    }

    @Test
    fun `toggling a blocked app adds then removes it`() = runTest {
        val vm = viewModel()
        vm.toggleBlockedApp(youtube)
        awaitUntil { youtube in repo.current().blockedPackages }

        vm.toggleBlockedApp(youtube)
        awaitUntil { repo.current().blockedPackages.isEmpty() }
    }

    @Test
    fun `toggling an allowlist app adds then removes it`() = runTest {
        val vm = viewModel()
        vm.toggleAllowlistApp("com.android.dialer")
        awaitUntil { "com.android.dialer" in repo.current().allowlistPackages }

        vm.toggleAllowlistApp("com.android.dialer")
        awaitUntil { repo.current().allowlistPackages.isEmpty() }
    }

    @Test
    fun `adding a question appends it with a custom slot key`() = runTest {
        viewModel().addQuestion(Phase.MORNING, "Who do I owe a reply?")
        awaitUntil { db.customQuestionDao().list(Phase.MORNING).size == 3 }

        val added = db.customQuestionDao().list(Phase.MORNING).last()
        assertThat(added.prompt).isEqualTo("Who do I owe a reply?")
        assertThat(SlotKey.isCustom(added.slotKey)).isTrue()
        assertThat(added.sortOrder).isEqualTo(2)
    }

    @Test
    fun `adding a blank question is ignored`() = runTest {
        viewModel().addQuestion(Phase.MORNING, "   ")
        assertThat(db.customQuestionDao().list(Phase.MORNING)).hasSize(2)
    }

    @Test
    fun `editing a question changes only its prompt`() = runTest {
        val original = db.customQuestionDao().list(Phase.MORNING).first()
        viewModel().editQuestion(original, "What matters most today?")
        awaitUntil { db.customQuestionDao().list(Phase.MORNING).first().prompt == "What matters most today?" }

        val updated = db.customQuestionDao().list(Phase.MORNING).first()
        assertThat(updated.slotKey).isEqualTo(original.slotKey)
        assertThat(updated.id).isEqualTo(original.id)
    }

    @Test
    fun `deleting a question removes it`() = runTest {
        viewModel().deleteQuestion(db.customQuestionDao().list(Phase.EVENING).first())
        awaitUntil { db.customQuestionDao().list(Phase.EVENING).size == 2 }
    }

    @Test
    fun `moving a question up swaps its sort order with its neighbour`() = runTest {
        val before = db.customQuestionDao().list(Phase.EVENING)
        viewModel().moveQuestion(before[1], delta = -1)
        awaitUntil { db.customQuestionDao().list(Phase.EVENING).first().slotKey == before[1].slotKey }

        val after = db.customQuestionDao().list(Phase.EVENING)
        assertThat(after[1].slotKey).isEqualTo(before[0].slotKey)
    }

    @Test
    fun `moving the first question up is a no-op`() = runTest {
        val before = db.customQuestionDao().list(Phase.EVENING).map { it.slotKey }
        viewModel().moveQuestion(db.customQuestionDao().list(Phase.EVENING).first(), delta = -1)
        awaitUntil { true }
        assertThat(db.customQuestionDao().list(Phase.EVENING).map { it.slotKey }).isEqualTo(before)
    }

    @Test
    fun `setting the export tree persists the uri`() = runTest {
        viewModel().setExportTree("content://com.android.externalstorage.documents/tree/primary%3ADocs")
        awaitUntil { repo.current().exportTreeUri?.contains("primary%3ADocs") == true }
    }

    @Test
    fun `room lists are parsed from the comma-separated field`() = runTest {
        viewModel().setRooms(Phase.EVENING, " Bedroom , Living Room ,")
        awaitUntil { repo.current().eveningAllowedRooms.isNotEmpty() }
        assertThat(repo.current().eveningAllowedRooms).containsExactly("Bedroom", "Living Room").inOrder()
    }

    @Test
    fun `location mode is set per phase`() = runTest {
        viewModel().setLocationMode(Phase.MORNING, LocationMode.SPECIFIC_ROOMS)
        awaitUntil { repo.current().morningLocationMode == LocationMode.SPECIFIC_ROOMS }
        assertThat(repo.current().eveningLocationMode).isEqualTo(LocationMode.AT_HOME)
    }

    // --- Usage limits ---

    @Test
    fun `setting a limit on an app with no row creates one`() = runTest {
        viewModel().setLimit(youtube) { it.copy(limitMode = LimitMode.TIME, dailyMinutes = 30) }
        awaitUntil { db.appLimitDao().find(youtube)?.dailyMinutes == 30 }
    }

    @Test
    fun `setting a second mechanic preserves the first`() = runTest {
        val vm = viewModel()
        vm.setLimit(youtube) { it.copy(limitMode = LimitMode.TIME, dailyMinutes = 30) }
        awaitUntil { db.appLimitDao().find(youtube)?.dailyMinutes == 30 }
        vm.setLimit(youtube) { it.copy(preOpenDelaySeconds = 30) }
        awaitUntil { db.appLimitDao().find(youtube)?.preOpenDelaySeconds == 30 }

        assertThat(db.appLimitDao().find(youtube)!!.dailyMinutes).isEqualTo(30)
    }

    @Test
    fun `clearing a limit removes the row entirely`() = runTest {
        val vm = viewModel()
        vm.setLimit(youtube) { it.copy(limitMode = LimitMode.TIME, dailyMinutes = 30) }
        awaitUntil { db.appLimitDao().find(youtube) != null }
        vm.clearLimit(youtube)
        awaitUntil { db.appLimitDao().find(youtube) == null }
    }

    @Test
    fun `appLimits exposes the configured apps`() = runTest {
        val vm = viewModel()
        vm.setLimit(youtube) { it.copy(dailyOpens = 5) }
        awaitUntil { vm.appLimits.value.flatMap { it.packages } == listOf(youtube) }
    }

    @Test
    fun `creating a limit for several apps makes one group`() = runTest {
        val vm = viewModel()
        vm.createLimit(setOf(youtube, "com.instagram.android"))
        awaitUntil { db.appLimitDao().find("com.instagram.android") != null }

        assertThat(db.appLimitDao().all()).hasSize(1)
        assertThat(db.appLimitDao().find(youtube)!!.id).isEqualTo(db.appLimitDao().find("com.instagram.android")!!.id)
    }

    @Test
    fun `an app moved into a new group leaves its old one`() = runTest {
        val vm = viewModel()
        vm.setLimit(youtube) { it.copy(dailyOpens = 5) }
        awaitUntil { db.appLimitDao().find(youtube) != null }
        val old = db.appLimitDao().find(youtube)!!.id

        vm.createLimit(setOf(youtube, "com.instagram.android"))
        awaitUntil { db.appLimitDao().find(youtube)?.id != old }

        assertThat(db.appLimitDao().all()).hasSize(1)   // the emptied group is gone
    }

    @Test
    fun `emptying a limit's apps removes it`() = runTest {
        val vm = viewModel()
        vm.setLimit(youtube) { it.copy(dailyOpens = 5) }
        awaitUntil { db.appLimitDao().find(youtube) != null }
        vm.setLimitPackages(db.appLimitDao().find(youtube)!!.id, emptySet())
        awaitUntil { db.appLimitDao().all().isEmpty() }
    }

    @Test
    fun `turning the evening sit on re-arms the alarms`() = runTest {
        val vm = viewModel()
        vm.updateSettings { it.copy(eveningSitRequired = true) }
        awaitUntil { repo.current().eveningSitRequired }
        assertThat(rescheduleCount).isEqualTo(1)
    }

    // --- Summary copy ---

    @Test
    fun `summary lists every configured mechanic, for the chosen mode only`() {
        val limit = AppLimit(youtube, limitMode = LimitMode.TIME, dailyMinutes = 30, dailyOpens = 5, preOpenDelaySeconds = 30)
        val byTime = LimitSummary.describe(limit)
        assertThat(byTime).contains("30 min/day")
        assertThat(byTime).doesNotContain("5 opens")
        assertThat(byTime).contains("30s pause")

        val byOpens = LimitSummary.describe(limit.copy(limitMode = LimitMode.OPENS))
        assertThat(byOpens).contains("5 opens")
        assertThat(byOpens).doesNotContain("min/day")
    }

    @Test
    fun `the picker hides apps limited at an overlapping time`() {
        val instagram = "com.instagram.android"
        val apps = listOf(InstalledApp(youtube, "YouTube"), InstalledApp(instagram, "Instagram"))
        val afternoon = AppLimit(packageName = youtube).copy(id = 1, windowStartMinute = 14 * 60, windowEndMinute = 17 * 60)
        val evening = AppLimit().copy(id = 2, windowStartMinute = 17 * 60, windowEndMinute = 21 * 60)
        val allDay = AppLimit().copy(id = 3)

        // A new all-day limit: anything limited is hidden.
        assertThat(LimitSummary.availableFor(AppLimit(), listOf(afternoon), apps).map { it.packageName })
            .containsExactly(instagram)
        // A limit at other hours may take the app.
        assertThat(LimitSummary.availableFor(evening, listOf(afternoon), apps).map { it.packageName })
            .containsExactly(youtube, instagram)
        // An all-day limit clashes with any window.
        assertThat(LimitSummary.availableFor(allDay, listOf(afternoon), apps).map { it.packageName })
            .containsExactly(instagram)
        // A limit always sees its own members.
        assertThat(LimitSummary.availableFor(afternoon, listOf(afternoon, allDay.copy(packages = setOf(youtube))), apps)
            .map { it.packageName }).contains(youtube)
    }

    @Test
    fun `the summary shows the hours first`() {
        val limit = AppLimit(youtube, dailyOpens = 2).copy(windowStartMinute = 14 * 60, windowEndMinute = 17 * 60)
        assertThat(LimitSummary.describe(limit)).isEqualTo("14:00–17:00 · 2 opens")
    }

    @Test
    fun `the row title is the name, or the apps`() {
        val labels = mapOf(youtube to "YouTube", "com.instagram.android" to "Instagram")
        assertThat(LimitSummary.title(AppLimit(name = "Social", packages = setOf(youtube)), labels)).isEqualTo("Social")
        assertThat(LimitSummary.title(AppLimit(packages = setOf(youtube, "com.instagram.android")), labels))
            .isEqualTo("Instagram & YouTube")
    }

    @Test
    fun `summary omits unset mechanics`() {
        assertThat(LimitSummary.describe(AppLimit(youtube, limitMode = LimitMode.TIME, dailyMinutes = 30))).isEqualTo("30 min/day")
    }

    @Test
    fun `summary of an empty limit says so rather than being blank`() {
        assertThat(LimitSummary.describe(AppLimit(youtube))).isEqualTo("No limits set")
    }

    @Test
    fun `summary includes cooldown and session cap`() {
        val summary = LimitSummary.describe(AppLimit(youtube, cooldownMinutes = 20, sessionMinutes = 10))
        assertThat(summary).contains("20 min cooldown")
        assertThat(summary).contains("10 min sessions")
    }
}
