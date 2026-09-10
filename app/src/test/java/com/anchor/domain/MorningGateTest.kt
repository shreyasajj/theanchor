package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.DailyLog
import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class MorningGateTest {

    private lateinit var db: AnchorDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val zone = ZoneId.of("America/Los_Angeles")

    /** 2026-09-09 07:00 local. */
    private val morningInstant = Instant.parse("2026-09-09T14:00:00Z")
    /** 2026-09-09 15:00 local. */
    private val afternoonInstant = Instant.parse("2026-09-09T22:00:00Z")

    private fun state(s: String, friendly: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendly
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) } ?: emptyMap()
        return HaResult.Ok(HaStateDto("e", s, attrs))
    }

    private fun gate(
        instant: Instant = morningInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
    ): Pair<MorningGate, FakeHaClient> {
        val client = FakeHaClient(haStates)
        val clock = Clock.fixed(instant, zone)
        val gate = MorningGate(
            settingsProvider = { settings },
            dailyLogDao = db.dailyLogDao(),
            client = client,
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
        )
        return gate to client
    }

    private fun configured() = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
    )

    @Test
    fun `locks in the window, at home, not yet completed`() = runTest {
        val (g, _) = gate()
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `skips outside the morning window`() = runTest {
        val (g, _) = gate(instant = afternoonInstant)
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `skips when today's morning is already completed`() = runTest {
        db.dailyLogDao().upsert(DailyLog(date = "2026-09-09", mission = "done", morningCompletedAt = 1L))
        val (g, _) = gate()
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `a row with no morningCompletedAt does not count as completed`() = runTest {
        db.dailyLogDao().upsert(DailyLog(date = "2026-09-09", led = "evening only"))
        val (g, _) = gate()
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `skips when the kill switch override is active`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, _) = gate(
            settings = settings,
            haStates = mapOf(
                "input_boolean.anchor_override" to state("on"),
                "device_tracker.pixel" to state("home"),
            ),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `an inactive kill switch does not prevent locking`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, _) = gate(
            settings = settings,
            haStates = mapOf(
                "input_boolean.anchor_override" to state("off"),
                "device_tracker.pixel" to state("home"),
            ),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `the kill switch is checked before the location, saving a call`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, client) = gate(
            settings = settings,
            haStates = mapOf("input_boolean.anchor_override" to state("on")),
        )
        g.decide()
        assertThat(client.calls).containsExactly("input_boolean.anchor_override")
    }

    @Test
    fun `an outage with fail-open enabled skips as an override`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchFailOpenOnOutage = true,
        )
        val (g, _) = gate(settings = settings, haStates = emptyMap())
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `skips when the user is not home`() = runTest {
        val (g, _) = gate(haStates = mapOf("device_tracker.pixel" to state("not_home")))
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `FAIL-OPEN - skips when Home Assistant is unreachable`() = runTest {
        val (g, _) = gate(haStates = mapOf("device_tracker.pixel" to HaResult.Unavailable))
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN))
    }

    @Test
    fun `FAIL-OPEN - skips when Home Assistant is not configured at all`() = runTest {
        val (g, _) = gate(settings = AnchorSettings())
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN))
    }

    @Test
    fun `with the ask-anyway toggle, an unreachable Home Assistant still locks`() = runTest {
        val settings = configured().copy(enforceWithoutHomeAssistant = true)
        val (g, _) = gate(settings = settings, haStates = mapOf("device_tracker.pixel" to HaResult.Unavailable))
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `with the ask-anyway toggle, an unconfigured Home Assistant still locks`() = runTest {
        val (g, _) = gate(settings = AnchorSettings(enforceWithoutHomeAssistant = true))
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `the ask-anyway toggle never overrides a confirmed not-home reading`() = runTest {
        val settings = configured().copy(enforceWithoutHomeAssistant = true)
        val (g, _) = gate(settings = settings, haStates = mapOf("device_tracker.pixel" to state("not_home")))
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `specific rooms mode locks only in an allowed room`() = runTest {
        val settings = configured().copy(
            morningLocationMode = LocationMode.SPECIFIC_ROOMS,
            morningAllowedRooms = listOf("Bedroom", "Office"),
        )
        val inBedroom = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Bedroom Tracker")),
        ).first
        val inKitchen = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Kitchen Tracker")),
        ).first

        assertThat(inBedroom.decide()).isEqualTo(MorningDecision.Lock)
        assertThat(inKitchen.decide()).isEqualTo(MorningDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `honours a custom morning window`() = runTest {
        val settings = configured().copy(morningStartMinute = 8 * 60)
        val (g, _) = gate(settings = settings)
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `evaluates the window before making any network call`() = runTest {
        val (g, client) = gate(instant = afternoonInstant)
        g.decide()
        assertThat(client.calls).isEmpty()
    }
}
