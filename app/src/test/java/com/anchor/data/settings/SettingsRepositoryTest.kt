package com.anchor.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.anchor.data.export.NoteFormat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) { file }
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() { file.delete() }

    @Test
    fun `defaults match the spec`() = runTest {
        val s = repo.current()

        assertThat(s.morningStartMinute).isEqualTo(5 * 60)
        assertThat(s.morningEndMinute).isEqualTo(12 * 60)
        assertThat(s.eveningStartMinute).isEqualTo(20 * 60)
        assertThat(s.eveningEndMinute).isEqualTo(5 * 60)
        assertThat(s.morningLocationMode).isEqualTo(LocationMode.AT_HOME)
        assertThat(s.eveningLocationMode).isEqualTo(LocationMode.AT_HOME)
        assertThat(s.killSwitchEnabled).isFalse()
        assertThat(s.killSwitchOverrideState).isEqualTo("on")
        assertThat(s.blockedPackages).isEmpty()
        assertThat(s.exportTreeUri).isNull()
    }

    @Test
    fun `new Part II settings have the documented defaults`() = runTest {
        val s = repo.current()
        assertThat(s.dayResetMinute).isEqualTo(4 * 60)
        assertThat(s.killSwitchFailOpenOnOutage).isFalse()
        assertThat(s.noteFormat).isEqualTo(NoteFormat.PLAIN)
        assertThat(s.enforceWithoutHomeAssistant).isFalse()
        assertThat(s.showRelockBubble).isTrue()
    }

    @Test
    fun `round-trips a full configuration`() = runTest {
        repo.update {
            it.copy(
                haBaseUrl = "http://192.168.1.10:8123",
                haToken = "llat-secret",
                haDeviceTrackerEntityId = "device_tracker.pixel",
                morningLocationMode = LocationMode.SPECIFIC_ROOMS,
                morningAllowedRooms = listOf("Bedroom", "Office"),
                eveningAllowedRooms = listOf("Bedroom", "Living Room"),
                killSwitchEnabled = true,
                killSwitchEntityId = "input_boolean.anchor_override",
                killSwitchOverrideState = "off",
                blockedPackages = setOf("com.google.android.youtube", "com.instagram.android"),
                allowlistPackages = setOf("com.android.dialer"),
                exportTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                joplinBaseUrl = "http://192.168.1.10:41184",
                joplinToken = "joplin-token",
            )
        }

        val s = repo.current()
        assertThat(s.haBaseUrl).isEqualTo("http://192.168.1.10:8123")
        assertThat(s.morningLocationMode).isEqualTo(LocationMode.SPECIFIC_ROOMS)
        assertThat(s.morningAllowedRooms).containsExactly("Bedroom", "Office").inOrder()
        assertThat(s.killSwitchOverrideState).isEqualTo("off")
        assertThat(s.blockedPackages).hasSize(2)
        assertThat(s.joplinToken).isEqualTo("joplin-token")
    }

    @Test
    fun `new Part II settings round-trip`() = runTest {
        repo.update {
            it.copy(
                dayResetMinute = 3 * 60 + 30,
                killSwitchFailOpenOnOutage = true,
                noteFormat = NoteFormat.OBSIDIAN,
                enforceWithoutHomeAssistant = true,
                showRelockBubble = false,
            )
        }
        val s = repo.current()
        assertThat(s.enforceWithoutHomeAssistant).isTrue()
        assertThat(s.showRelockBubble).isFalse()
        assertThat(s.dayResetMinute).isEqualTo(210)
        assertThat(s.killSwitchFailOpenOnOutage).isTrue()
        assertThat(s.noteFormat).isEqualTo(NoteFormat.OBSIDIAN)
    }

    @Test
    fun `settings flow emits the new value after an update`() = runTest {
        repo.settings.test {
            assertThat(awaitItem().haToken).isEmpty()
            repo.update { it.copy(haToken = "abc") }
            assertThat(awaitItem().haToken).isEqualTo("abc")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `room lists are trimmed and blank entries dropped`() = runTest {
        repo.update { it.copy(morningAllowedRooms = parseRoomList(" Bedroom ,, Office ,")) }
        assertThat(repo.current().morningAllowedRooms)
            .containsExactly("Bedroom", "Office").inOrder()
    }
}
