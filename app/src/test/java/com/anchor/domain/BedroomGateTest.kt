package com.anchor.domain

import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.KillSwitch
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BedroomGateTest {

    private val youtube = "com.google.android.youtube"
    private val tracker = "device_tracker.pixel"
    private val override = "input_boolean.anchor_override"

    private fun at(hour: Int, minute: Int = 0) = AnchorDate(
        Clock.fixed(Instant.parse("2026-10-12T00:00:00Z").plusSeconds(hour * 3600L + minute * 60L), ZoneOffset.UTC)
    )

    private fun state(s: String, friendly: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendly
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) } ?: emptyMap()
        return HaResult.Ok(HaStateDto(tracker, s, attrs))
    }

    private fun configured() = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = tracker,
        blockedPackages = setOf(youtube),
        bedroomBlockEnabled = true,
        bedroomRooms = listOf("Bedroom"),
    )

    private fun gate(
        settings: AnchorSettings = configured(),
        states: Map<String, HaResult> = mapOf(tracker to state("home", "Bedroom Presence")),
        date: AnchorDate = at(23),
    ): Pair<BedroomGate, FakeHaClient> {
        val client = FakeHaClient(states)
        return BedroomGate({ settings }, client, KillSwitch(client), date) to client
    }

    @Test
    fun `blocks in the bedroom during the hours`() = runTest {
        assertThat(gate().first.decide(youtube)).isEqualTo(BedroomDecision.Block(7 * 60))
    }

    @Test
    fun `a room named only in the state also blocks`() = runTest {
        val (g, _) = gate(states = mapOf(tracker to state("Bedroom")))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Block(7 * 60))
    }

    @Test
    fun `the hours wrap midnight`() = runTest {
        assertThat(gate(date = at(2)).first.decide(youtube)).isInstanceOf(BedroomDecision.Block::class.java)
        assertThat(gate(date = at(6, 59)).first.decide(youtube)).isInstanceOf(BedroomDecision.Block::class.java)
    }

    @Test
    fun `switched off allows without asking anything`() = runTest {
        val (g, client) = gate(settings = configured().copy(bedroomBlockEnabled = false))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.DISABLED))
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `an app not on the blocked list is never touched`() = runTest {
        val (g, client) = gate()
        assertThat(g.decide("com.android.chrome")).isEqualTo(BedroomDecision.Allow(BedroomSkip.NOT_BLOCKED_APP))
        assertThat(g.decide(EveningGate.OWN_PACKAGE)).isEqualTo(BedroomDecision.Allow(BedroomSkip.NOT_BLOCKED_APP))
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `outside the hours allows without a network call`() = runTest {
        val (g, client) = gate(date = at(12))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.OUTSIDE_HOURS))
        assertThat(gate(date = at(7, 30)).first.decide(youtube))
            .isEqualTo(BedroomDecision.Allow(BedroomSkip.OUTSIDE_HOURS))
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `vacation mode turns it off without a network call`() = runTest {
        val (g, client) = gate(settings = configured().copy(vacationMode = true, vacationPlace = SavedPlace(1.0, 2.0)))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.VACATION))
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `the kill switch wins over a bedroom reading`() = runTest {
        val settings = configured().copy(killSwitchEnabled = true, killSwitchEntityId = override, killSwitchOverrideState = "on")
        val (g, _) = gate(settings = settings, states = mapOf(override to state("on"), tracker to state("Bedroom")))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.OVERRIDE_ACTIVE))
    }

    @Test
    fun `another room allows`() = runTest {
        val (g, _) = gate(states = mapOf(tracker to state("home", "Living Room Presence")))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.NOT_IN_BEDROOM))
    }

    @Test
    fun `FAIL-OPEN - Home Assistant unreachable allows`() = runTest {
        val (g, _) = gate(states = mapOf(tracker to HaResult.Unavailable))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.LOCATION_UNKNOWN))
    }

    @Test
    fun `FAIL-OPEN - an unknown tracker state allows`() = runTest {
        val (g, _) = gate(states = mapOf(tracker to state("unknown", "Bedroom Presence")))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.LOCATION_UNKNOWN))
    }

    @Test
    fun `FAIL-OPEN - ask anyway never turns unknown into a wall`() = runTest {
        val (g, _) = gate(
            settings = configured().copy(enforceWithoutHomeAssistant = true),
            states = mapOf(tracker to HaResult.Unavailable),
        )
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.LOCATION_UNKNOWN))
    }

    @Test
    fun `an empty room list never blocks`() = runTest {
        val (g, _) = gate(settings = configured().copy(bedroomRooms = emptyList()))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.NOT_IN_BEDROOM))
    }

    @Test
    fun `watches blocked apps whenever the block is on, at any hour, offline`() = runTest {
        val (g, client) = gate(date = at(12))
        assertThat(g.watches(youtube)).isTrue()
        assertThat(g.watches("com.android.chrome")).isFalse()
        assertThat(gate(settings = configured().copy(bedroomBlockEnabled = false)).first.watches(youtube)).isFalse()
        assertThat(client.calls).isEmpty()
    }

    // --- Latency ---

    /** Each Home Assistant request takes a second of virtual time. */
    private class SlowHaClient(byEntity: Map<String, HaResult>) : FakeHaClient(byEntity) {
        override suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult {
            delay(1_000)
            return super.fetchState(baseUrl, token, entityId)
        }
    }

    private class MovableClock(var instant: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = instant
    }

    private val withKillSwitch = configured().copy(
        killSwitchEnabled = true, killSwitchEntityId = override, killSwitchOverrideState = "on",
    )
    private val bedroomAndSwitchOff = mapOf(tracker to state("Bedroom"), override to state("off"))

    @Test
    fun `the kill switch and the tracker are asked at the same time`() = runTest {
        val client = SlowHaClient(bedroomAndSwitchOff)
        val g = BedroomGate({ withKillSwitch }, client, KillSwitch(client), at(23))
        assertThat(g.decide(youtube)).isInstanceOf(BedroomDecision.Block::class.java)
        assertThat(currentTime).isEqualTo(1_000)
    }

    @Test
    fun `a burst of events for one app makes one lookup`() = runTest {
        val client = SlowHaClient(bedroomAndSwitchOff)
        val g = BedroomGate({ withKillSwitch }, client, KillSwitch(client), at(23))
        val all = List(4) { async { g.decide(youtube) } }.map { it.await() }
        assertThat(all.toSet()).containsExactly(BedroomDecision.Block(7 * 60))
        assertThat(client.calls).hasSize(2)
    }

    @Test
    fun `the answer is remembered for a few seconds, then asked again`() = runTest {
        val clock = MovableClock(Instant.parse("2026-10-12T23:00:00Z"))
        val client = FakeHaClient(bedroomAndSwitchOff)
        val g = BedroomGate({ withKillSwitch }, client, KillSwitch(client), AnchorDate(clock))
        g.decide(youtube)
        clock.instant = clock.instant.plusMillis(BedroomGate.MEMORY_MILLIS - 1)
        g.decide(youtube)
        assertThat(client.calls).hasSize(2)
        clock.instant = clock.instant.plusMillis(1)
        g.decide(youtube)
        assertThat(client.calls).hasSize(4)
    }

    @Test
    fun `a room in the state counts even when the tracker has a name`() = runTest {
        val (g, _) = gate(states = mapOf(tracker to state("Bedroom", "Pixel 8")))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Block(7 * 60))
    }
}
