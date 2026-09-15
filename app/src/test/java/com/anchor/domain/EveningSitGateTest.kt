package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.usage.MeditationSession
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
class EveningSitGateTest {

    private lateinit var db: AnchorDatabase
    private val zone = ZoneId.of("America/Los_Angeles")

    /** 2026-09-09 21:00 local. */
    private val eveningInstant = Instant.parse("2026-09-10T04:00:00Z")
    /** 2026-09-09 15:00 local. */
    private val afternoonInstant = Instant.parse("2026-09-09T22:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun state(s: String) = HaResult.Ok(HaStateDto("e", s, emptyMap()))

    private fun configured() = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
        eveningSitRequired = true,
        eveningSitMinutes = 5,
    )

    private fun gate(
        instant: Instant = eveningInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
    ): EveningSitGate {
        val client = FakeHaClient(haStates)
        return EveningSitGate(
            settingsProvider = { settings },
            meditationDao = db.meditationSessionDao(),
            client = client,
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(Clock.fixed(instant, zone)),
        )
    }

    private suspend fun sat(seconds: Int, atInstant: Instant = eveningInstant.minusSeconds(3600)) {
        db.meditationSessionDao().insert(
            MeditationSession(startedAtMillis = atInstant.toEpochMilli(), seconds = seconds)
        )
    }

    @Test
    fun `locks in the evening, at home, with no sitting done`() = runTest {
        assertThat(gate().decide()).isEqualTo(SitDecision.Lock(requiredSeconds = 300, satSeconds = 0))
    }

    @Test
    fun `off by default`() = runTest {
        assertThat(gate(settings = configured().copy(eveningSitRequired = false)).decide())
            .isEqualTo(SitDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `skips outside the evening window`() = runTest {
        assertThat(gate(instant = afternoonInstant).decide()).isEqualTo(SitDecision.Skip(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `sitting done earlier in the day counts`() = runTest {
        sat(300, atInstant = Instant.parse("2026-09-09T16:00:00Z"))   // 09:00 local
        assertThat(gate().decide()).isEqualTo(SitDecision.Skip(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `short sits add up`() = runTest {
        sat(120)
        sat(180)
        assertThat(gate().decide()).isEqualTo(SitDecision.Skip(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `not enough yet reports how much was sat`() = runTest {
        sat(120)
        assertThat(gate().decide()).isEqualTo(SitDecision.Lock(requiredSeconds = 300, satSeconds = 120))
    }

    @Test
    fun `yesterday's sitting does not count`() = runTest {
        sat(600, atInstant = Instant.parse("2026-09-09T08:00:00Z"))   // 01:00 local, before the 04:00 reset
        assertThat(gate().decide()).isInstanceOf(SitDecision.Lock::class.java)
    }

    @Test
    fun `skips when the kill switch override is active`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val g = gate(
            settings = settings,
            haStates = mapOf(
                "device_tracker.pixel" to state("home"),
                "input_boolean.anchor_override" to state("on"),
            ),
        )
        assertThat(g.decide()).isEqualTo(SitDecision.Skip(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `a confirmed away skips`() = runTest {
        assertThat(gate(haStates = mapOf("device_tracker.pixel" to state("not_home"))).decide())
            .isEqualTo(SitDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `FAIL-OPEN - an unreachable Home Assistant skips by default`() = runTest {
        assertThat(gate(haStates = emptyMap()).decide()).isEqualTo(SitDecision.Skip(SkipReason.LOCATION_UNKNOWN))
    }

    @Test
    fun `ask-anyway locks when location is unknown`() = runTest {
        val g = gate(settings = configured().copy(enforceWithoutHomeAssistant = true), haStates = emptyMap())
        assertThat(g.decide()).isInstanceOf(SitDecision.Lock::class.java)
    }

    @Test
    fun `the requirement is never under a minute`() {
        assertThat(EveningSit.requiredSeconds(AnchorSettings(eveningSitMinutes = 0))).isEqualTo(60)
    }
}
