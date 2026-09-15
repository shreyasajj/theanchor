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
class EveningGateTest {

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
    private val youtube = "com.google.android.youtube"

    /** 2026-09-09 22:00 local. */
    private val nightInstant = Instant.parse("2026-09-10T05:00:00Z")
    /** 2026-09-10 01:00 local, still "the 9th's" evening. */
    private val afterMidnightInstant = Instant.parse("2026-09-10T08:00:00Z")
    /** 2026-09-09 15:00 local. */
    private val afternoonInstant = Instant.parse("2026-09-09T22:00:00Z")

    private fun state(s: String, friendly: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendly
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) } ?: emptyMap()
        return HaResult.Ok(HaStateDto("e", s, attrs))
    }

    private fun configured() = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
        blockedPackages = setOf(youtube, "com.instagram.android"),
    )

    private fun gate(
        instant: Instant = nightInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
    ): EveningGate {
        val client = FakeHaClient(haStates)
        val clock = Clock.fixed(instant, zone)
        return EveningGate(
            settingsProvider = { settings },
            dailyLogDao = db.dailyLogDao(),
            client = client,
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
        )
    }

    @Test
    fun `strict overlay for a blocked app at home during the window`() = runTest {
        assertThat(gate().decide(youtube)).isEqualTo(EveningDecision.Strict)
    }

    // --- The questions on their own ---

    @Test
    fun `unprompted is off unless the setting is on`() = runTest {
        assertThat(gate().decideUnprompted()).isEqualTo(EveningDecision.Allow(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `unprompted asks in the window, at home, when tonight is not done`() = runTest {
        val g = gate(settings = configured().copy(eveningPromptOnItsOwn = true))
        assertThat(g.decideUnprompted()).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `unprompted respects the window and completion`() = runTest {
        val on = configured().copy(eveningPromptOnItsOwn = true)
        assertThat(gate(instant = afternoonInstant, settings = on).decideUnprompted())
            .isEqualTo(EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW))

        db.dailyLogDao().upsert(DailyLog(date = "2026-09-09", led = "x", eveningCompletedAt = 1L))
        assertThat(gate(settings = on).decideUnprompted())
            .isEqualTo(EveningDecision.Allow(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `unprompted does not ask when confirmed away, and asks on unknown only with ask-anyway`() = runTest {
        val on = configured().copy(eveningPromptOnItsOwn = true)
        assertThat(gate(settings = on, haStates = mapOf("device_tracker.pixel" to state("not_home"))).decideUnprompted())
            .isEqualTo(EveningDecision.SimpleDelay)
        assertThat(gate(settings = on, haStates = emptyMap()).decideUnprompted())
            .isEqualTo(EveningDecision.SimpleDelay)
        assertThat(gate(settings = on.copy(enforceWithoutHomeAssistant = true), haStates = emptyMap()).decideUnprompted())
            .isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `allows an app that is not on the blocked list`() = runTest {
        assertThat(gate().decide("com.android.calculator2"))
            .isEqualTo(EveningDecision.Allow(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `allows a blocked app outside the evening window`() = runTest {
        assertThat(gate(instant = afternoonInstant).decide(youtube))
            .isEqualTo(EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `blocks after midnight, which is still inside the wrapping window`() = runTest {
        assertThat(gate(instant = afterMidnightInstant).decide(youtube)).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `allows once tonight's evening check-in is complete`() = runTest {
        db.dailyLogDao().upsert(DailyLog(date = "2026-09-09", led = "x", eveningCompletedAt = 1L))
        assertThat(gate().decide(youtube)).isEqualTo(EveningDecision.Allow(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `a 10pm completion still counts at 1am the next morning`() = runTest {
        db.dailyLogDao().upsert(DailyLog(date = "2026-09-09", led = "x", eveningCompletedAt = 1L))
        assertThat(gate(instant = afterMidnightInstant).decide(youtube))
            .isEqualTo(EveningDecision.Allow(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `the block resets the next night`() = runTest {
        db.dailyLogDao().upsert(DailyLog(date = "2026-09-08", led = "yesterday", eveningCompletedAt = 1L))
        assertThat(gate().decide(youtube)).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `the kill switch allows the app through entirely`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val decision = gate(
            settings = settings,
            haStates = mapOf(
                "input_boolean.anchor_override" to state("on"),
                "device_tracker.pixel" to state("home"),
            ),
        ).decide(youtube)

        assertThat(decision).isEqualTo(EveningDecision.Allow(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `FAIL-OPEN - simple delay when Home Assistant is unreachable`() = runTest {
        val decision = gate(haStates = mapOf("device_tracker.pixel" to HaResult.Unavailable)).decide(youtube)
        assertThat(decision).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `FAIL-OPEN - simple delay when Home Assistant is not configured`() = runTest {
        assertThat(gate(settings = AnchorSettings(blockedPackages = setOf(youtube))).decide(youtube))
            .isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `with the ask-anyway toggle, an unreachable Home Assistant shows the questions`() = runTest {
        val settings = configured().copy(enforceWithoutHomeAssistant = true)
        val decision = gate(settings = settings, haStates = mapOf("device_tracker.pixel" to HaResult.Unavailable))
            .decide(youtube)
        assertThat(decision).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `with the ask-anyway toggle, an unconfigured Home Assistant shows the questions`() = runTest {
        val settings = AnchorSettings(blockedPackages = setOf(youtube), enforceWithoutHomeAssistant = true)
        assertThat(gate(settings = settings).decide(youtube)).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `the ask-anyway toggle still gives a confirmed away reading the simple delay`() = runTest {
        val settings = configured().copy(enforceWithoutHomeAssistant = true)
        val decision = gate(settings = settings, haStates = mapOf("device_tracker.pixel" to state("not_home")))
            .decide(youtube)
        assertThat(decision).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `simple delay when the user is away from home`() = runTest {
        val decision = gate(haStates = mapOf("device_tracker.pixel" to state("not_home"))).decide(youtube)
        assertThat(decision).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `specific rooms mode is strict only in a restricted room`() = runTest {
        val settings = configured().copy(
            eveningLocationMode = LocationMode.SPECIFIC_ROOMS,
            eveningAllowedRooms = listOf("Bedroom", "Living Room"),
        )
        val inBedroom = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Bedroom Sensor")),
        ).decide(youtube)
        val inGarage = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Garage Sensor")),
        ).decide(youtube)

        assertThat(inBedroom).isEqualTo(EveningDecision.Strict)
        assertThat(inGarage).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `never blocks the Anchor app itself`() = runTest {
        val settings = configured().copy(blockedPackages = configured().blockedPackages + "com.anchor")
        assertThat(gate(settings = settings).decide("com.anchor"))
            .isEqualTo(EveningDecision.Allow(SkipReason.NOT_IN_SCOPE))
    }
}
