# Bedroom Block Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During bedroom hours, when Home Assistant confirms you are in a bedroom room, evening-blocked apps do not open at all, and an app already open is closed within 2 minutes.

**Architecture:** A `BedroomGate` (pure decision over settings, clock, kill switch and the HA tracker) is asked by the accessibility service before anything else for each app open. A `BedroomWatcher`, shaped like `SessionCapWatcher`, re-asks every 2 minutes while a blocked app stays in front. A plain `BedroomBlockedActivity` is the wall. Four new settings and a Settings card.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore Preferences, coroutines (virtual-time tests), JUnit4 + Robolectric + Truth.

**Spec:** `docs/superpowers/specs/2026-10-12-bedroom-block-design.md`

## Global Constraints

- minSdk 33, compileSdk/targetSdk 35. No new dependencies.
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` before every Gradle command, and pass `--no-daemon`.
- Fail-open: the bedroom block fires only on a confirmed `IN_SCOPE` room reading. Not configured, unreachable, unknown, empty room list or no match → not blocked. "Ask even when location is unknown" does NOT apply to it.
- The bedroom block never throws out of the accessibility service.
- Precedence: bedroom block > usage-limit block > strict evening overlay > pause, and it is checked before the pause ledger's shortcuts.
- Defaults: off, rooms `Bedroom`, hours 22:00–07:00, re-check every 2 minutes.
- Match the surrounding code: KDoc explaining *why*, backtick test names, Truth assertions.

## Review Focus

1. **Home Assistant down while in bed** → apps go through the normal evening rules, never the wall, even with "ask anyway" on (Task 2 test).
2. **Bedroom block on, room list emptied** → never blocks (Task 2 test).
3. **App opened at 21:55, in bed at 22:00** → closed on the first 2-minute tick inside the hours (Task 3 test).
4. **Switching to another app, the keyboard, or our own overlay** → does not stop the re-check of the blocked app still in front; leaving the app does (Task 3 tests).
5. **Vacation mode on** → bedroom block off, without any network call (Task 2 test).

Precedence (spec §4 "Routing") is enforced by the order of checks in the accessibility service, which has no unit-test harness in this project (it is a Hilt `@AndroidEntryPoint` service). It is covered by a code comment, the manual checklist (Task 6), and by `BedroomGate` being called before `limitGate`/`PauseLedger` in one visible place.

---

### Task 1: Bedroom settings and persistence

**Files:**
- Modify: `app/src/main/java/com/anchor/data/settings/AnchorSettings.kt`
- Modify: `app/src/main/java/com/anchor/data/settings/SettingsRepository.kt`
- Test: `app/src/test/java/com/anchor/data/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `AnchorSettings.bedroomBlockEnabled: Boolean = false`, `bedroomRooms: List<String> = listOf("Bedroom")`, `bedroomStartMinute: Int = 22 * 60`, `bedroomEndMinute: Int = 7 * 60`.

- [ ] **Step 1: Write the failing tests** — append to `SettingsRepositoryTest`:

```kotlin
    @Test
    fun `bedroom settings have the documented defaults`() = runTest {
        val s = repo.current()
        assertThat(s.bedroomBlockEnabled).isFalse()
        assertThat(s.bedroomRooms).containsExactly("Bedroom")
        assertThat(s.bedroomStartMinute).isEqualTo(22 * 60)
        assertThat(s.bedroomEndMinute).isEqualTo(7 * 60)
    }

    @Test
    fun `bedroom settings round-trip`() = runTest {
        repo.update {
            it.copy(
                bedroomBlockEnabled = true,
                bedroomRooms = listOf("Bedroom", "Guest Room"),
                bedroomStartMinute = 21 * 60 + 30,
                bedroomEndMinute = 6 * 60,
            )
        }
        val s = repo.current()
        assertThat(s.bedroomBlockEnabled).isTrue()
        assertThat(s.bedroomRooms).containsExactly("Bedroom", "Guest Room").inOrder()
        assertThat(s.bedroomStartMinute).isEqualTo(21 * 60 + 30)
        assertThat(s.bedroomEndMinute).isEqualTo(6 * 60)
    }

    @Test
    fun `an emptied bedroom room list stays empty`() = runTest {
        repo.update { it.copy(bedroomRooms = emptyList()) }
        assertThat(repo.current().bedroomRooms).isEmpty()
    }
```

- [ ] **Step 2: Run to verify RED**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.settings.SettingsRepositoryTest' --no-daemon -q`
Expected: compilation FAILS, unresolved `bedroomBlockEnabled`.

- [ ] **Step 3: Implement**

`AnchorSettings.kt`, after `vacationPlace`:

```kotlin

    // --- Bedroom block ---
    /**
     * During [bedroomStartMinute]–[bedroomEndMinute], blocked apps do not
     * open at all while Home Assistant places you in one of [bedroomRooms],
     * even after the evening questions. Only a confirmed room reading blocks.
     */
    val bedroomBlockEnabled: Boolean = false,
    val bedroomRooms: List<String> = listOf("Bedroom"),
    val bedroomStartMinute: Int = 22 * 60,
    val bedroomEndMinute: Int = 7 * 60,
```

`SettingsRepository.kt` — keys:

```kotlin
        val BEDROOM_BLOCK = booleanPreferencesKey("bedroom_block")
        val BEDROOM_ROOMS = stringPreferencesKey("bedroom_rooms")
        val BEDROOM_START = intPreferencesKey("bedroom_start")
        val BEDROOM_END = intPreferencesKey("bedroom_end")
```

in `update`, after the vacation place lines:

```kotlin
            prefs[Keys.BEDROOM_BLOCK] = next.bedroomBlockEnabled
            prefs[Keys.BEDROOM_ROOMS] = formatRoomList(next.bedroomRooms)
            prefs[Keys.BEDROOM_START] = next.bedroomStartMinute
            prefs[Keys.BEDROOM_END] = next.bedroomEndMinute
```

in `toSettings()`, after `vacationPlace = ...`:

```kotlin
            bedroomBlockEnabled = this[Keys.BEDROOM_BLOCK] ?: d.bedroomBlockEnabled,
            bedroomRooms = this[Keys.BEDROOM_ROOMS]?.let(::parseRoomList) ?: d.bedroomRooms,
            bedroomStartMinute = this[Keys.BEDROOM_START] ?: d.bedroomStartMinute,
            bedroomEndMinute = this[Keys.BEDROOM_END] ?: d.bedroomEndMinute,
```

- [ ] **Step 4: Run to verify GREEN** — same command. Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat: persist the bedroom block settings"`

---

### Task 2: BedroomGate

**Files:**
- Create: `app/src/main/java/com/anchor/domain/BedroomGate.kt`
- Test: `app/src/test/java/com/anchor/domain/BedroomGateTest.kt`

**Interfaces:**
- Consumes: Task 1 fields; existing `SettingsProvider`, `HomeAssistantClient.fetchDeviceTracker`, `KillSwitch.blockingDisabled`, `AnchorDate.minuteOfDay`, `TimeWindow.contains`, `LocationGate.evaluate`, `PresenceSource.VACATION`, `EveningGate.OWN_PACKAGE`; test `FakeHaClient`.
- Produces:
  - `enum class BedroomSkip { DISABLED, NOT_BLOCKED_APP, OUTSIDE_HOURS, VACATION, OVERRIDE_ACTIVE, LOCATION_UNKNOWN, NOT_IN_BEDROOM }`
  - `sealed interface BedroomDecision { data class Block(val endMinute: Int); data class Allow(val reason: BedroomSkip) }`
  - `@Singleton class BedroomGate @Inject constructor(settingsProvider: SettingsProvider, client: HomeAssistantClient, killSwitch: KillSwitch, anchorDate: AnchorDate)` with `suspend fun decide(packageName: String): BedroomDecision` and `suspend fun watches(packageName: String): Boolean`

- [ ] **Step 1: Write the failing tests** — `BedroomGateTest.kt`:

```kotlin
package com.anchor.domain

import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
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
    fun `the kill switch wins, and is asked before the tracker`() = runTest {
        val settings = configured().copy(killSwitchEnabled = true, killSwitchEntityId = override, killSwitchOverrideState = "on")
        val (g, client) = gate(settings = settings, states = mapOf(override to state("on"), tracker to state("Bedroom")))
        assertThat(g.decide(youtube)).isEqualTo(BedroomDecision.Allow(BedroomSkip.OVERRIDE_ACTIVE))
        assertThat(client.calls).containsExactly(override)
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
}
```

- [ ] **Step 2: Run to verify RED**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.BedroomGateTest' --no-daemon -q`
Expected: compilation FAILS, unresolved `BedroomGate`.

- [ ] **Step 3: Implement** — `BedroomGate.kt`:

```kotlin
package com.anchor.domain

import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.PresenceSource
import com.anchor.data.settings.SettingsProvider
import javax.inject.Inject
import javax.inject.Singleton

enum class BedroomSkip {
    DISABLED,
    NOT_BLOCKED_APP,
    OUTSIDE_HOURS,
    VACATION,
    OVERRIDE_ACTIVE,
    LOCATION_UNKNOWN,
    NOT_IN_BEDROOM,
}

sealed interface BedroomDecision {
    /** No way in; the app is available again at [endMinute]. */
    data class Block(val endMinute: Int) : BedroomDecision
    data class Allow(val reason: BedroomSkip) : BedroomDecision
}

/**
 * Decides whether a blocked app is walled off because you are in the
 * bedroom. Cheapest checks first; the network last, kill switch before the
 * tracker. Only a confirmed room reading blocks: unlike the evening gate,
 * "ask anyway" never turns an unknown location into a wall.
 */
@Singleton
class BedroomGate @Inject constructor(
    private val settingsProvider: SettingsProvider,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(packageName: String): BedroomDecision {
        val settings = settingsProvider()
        if (!covers(settings, packageName)) {
            return BedroomDecision.Allow(
                if (!settings.bedroomBlockEnabled) BedroomSkip.DISABLED else BedroomSkip.NOT_BLOCKED_APP
            )
        }
        if (!TimeWindow.contains(anchorDate.minuteOfDay(), settings.bedroomStartMinute, settings.bedroomEndMinute)) {
            return BedroomDecision.Allow(BedroomSkip.OUTSIDE_HOURS)
        }
        // On vacation you are not in this house's bedroom.
        if (settings.presenceSource == PresenceSource.VACATION) return BedroomDecision.Allow(BedroomSkip.VACATION)
        if (killSwitch.blockingDisabled(settings)) return BedroomDecision.Allow(BedroomSkip.OVERRIDE_ACTIVE)

        val presence = LocationGate.evaluate(
            client.fetchDeviceTracker(settings),
            LocationMode.SPECIFIC_ROOMS,
            settings.bedroomRooms,
        )
        return when (presence) {
            Presence.IN_SCOPE -> BedroomDecision.Block(settings.bedroomEndMinute)
            Presence.OUT_OF_SCOPE -> BedroomDecision.Allow(BedroomSkip.NOT_IN_BEDROOM)
            Presence.UNKNOWN -> BedroomDecision.Allow(BedroomSkip.LOCATION_UNKNOWN)
        }
    }

    /**
     * Whether the watcher should keep an eye on [packageName]. No hours and
     * no network: an app opened just before the hours is caught once they
     * start.
     */
    suspend fun watches(packageName: String): Boolean = covers(settingsProvider(), packageName)

    private fun covers(settings: AnchorSettings, packageName: String): Boolean =
        settings.bedroomBlockEnabled &&
            packageName != EveningGate.OWN_PACKAGE &&
            packageName in settings.blockedPackages
}
```

- [ ] **Step 4: Run to verify GREEN** — same command. Expected: PASS (14 tests).
- [ ] **Step 5: Commit** — `git commit -m "feat: decide when the bedroom walls off a blocked app"`

---

### Task 3: BedroomWatcher

**Files:**
- Create: `app/src/main/java/com/anchor/domain/BedroomWatcher.kt`
- Test: `app/src/test/java/com/anchor/domain/BedroomWatcherTest.kt`

**Interfaces:**
- Consumes: `BedroomDecision`, `BedroomSkip` (Task 2).
- Produces: `interface BedroomQueries { suspend fun watches(packageName: String): Boolean; suspend fun isInForeground(packageName: String): Boolean; suspend fun decide(packageName: String): BedroomDecision }`; `class BedroomWatcher(scope: CoroutineScope, queries: BedroomQueries, onBlocked: (packageName: String, decision: BedroomDecision.Block) -> Unit)` with `fun onForegroundApp(packageName: String)`, `fun cancel()`, `companion const val INTERVAL_MILLIS = 120_000L`.

- [ ] **Step 1: Write the failing tests** — `BedroomWatcherTest.kt`:

```kotlin
package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BedroomWatcherTest {

    private val youtube = "com.google.android.youtube"
    private val instagram = "com.instagram.android"
    private val tick = BedroomWatcher.INTERVAL_MILLIS

    private class Fake : BedroomQueries {
        var watched = setOf("com.google.android.youtube", "com.instagram.android")
        var inFront = setOf("com.google.android.youtube")
        var inBedroom = false
        var decisions = 0
        override suspend fun watches(packageName: String) = packageName in watched
        override suspend fun isInForeground(packageName: String) = packageName in inFront
        override suspend fun decide(packageName: String): BedroomDecision {
            decisions++
            return if (inBedroom) BedroomDecision.Block(7 * 60) else BedroomDecision.Allow(BedroomSkip.NOT_IN_BEDROOM)
        }
    }

    private fun runWatcher(fake: Fake, block: suspend TestScope.(BedroomWatcher, MutableList<String>) -> Unit) = runTest {
        val fired = mutableListOf<String>()
        val watcher = BedroomWatcher(this, fake) { pkg, _ -> fired += pkg }
        block(watcher, fired)
        watcher.cancel()
        runCurrent()
    }

    @Test
    fun `closes the app on the first tick in the bedroom`() {
        val fake = Fake().apply { inBedroom = true }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(tick - 1); runCurrent()
            assertThat(fired).isEmpty()
            advanceTimeBy(2); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @Test
    fun `stays quiet elsewhere, then catches the walk into the bedroom`() {
        val fake = Fake()
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(tick + 1); runCurrent()
            assertThat(fired).isEmpty()
            fake.inBedroom = true
            advanceTimeBy(tick); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @Test
    fun `fires once, not every tick after`() {
        val fake = Fake().apply { inBedroom = true }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(5 * tick); runCurrent()
            assertThat(fired).hasSize(1)
        }
    }

    @Test
    fun `stops once the app has left the front`() {
        val fake = Fake().apply { inFront = emptySet() }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(tick + 1); runCurrent()
            assertThat(fake.decisions).isEqualTo(0)
            fake.inFront = setOf(youtube); fake.inBedroom = true
            advanceTimeBy(3 * tick); runCurrent()
            assertThat(fired).isEmpty()
        }
    }

    @Test
    fun `other windows do not stop the watch`() {
        val fake = Fake()
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(tick / 2); runCurrent()
            watcher.onForegroundApp("com.anchor")
            watcher.onForegroundApp("com.android.systemui")
            watcher.onForegroundApp("com.google.android.inputmethod.latin")
            fake.inBedroom = true
            advanceTimeBy(tick / 2 + 1); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @Test
    fun `the same app again does not restart the clock`() {
        val fake = Fake()
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(tick * 3 / 4); runCurrent()
            watcher.onForegroundApp(youtube)
            fake.inBedroom = true
            advanceTimeBy(tick / 4 + 1); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @Test
    fun `another blocked app takes over the watch`() {
        val fake = Fake()
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(tick / 2); runCurrent()
            fake.inFront = setOf(instagram)
            watcher.onForegroundApp(instagram)
            fake.inBedroom = true
            advanceTimeBy(tick / 2 + 1); runCurrent()
            assertThat(fired).isEmpty()
            advanceTimeBy(tick / 2); runCurrent()
            assertThat(fired).containsExactly(instagram)
        }
    }

    @Test
    fun `cancel stops it`() {
        val fake = Fake().apply { inBedroom = true }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            runCurrent()
            watcher.cancel()
            advanceTimeBy(3 * tick); runCurrent()
            assertThat(fired).isEmpty()
        }
    }
}
```

- [ ] **Step 2: Run to verify RED**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.BedroomWatcherTest' --no-daemon -q`
Expected: compilation FAILS, unresolved `BedroomWatcher`.

- [ ] **Step 3: Implement** — `BedroomWatcher.kt`:

```kotlin
package com.anchor.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the watcher needs; [BedroomGate] and the usage log answer, tests fake it. */
interface BedroomQueries {
    suspend fun watches(packageName: String): Boolean
    suspend fun isInForeground(packageName: String): Boolean
    suspend fun decide(packageName: String): BedroomDecision
}

/**
 * Catches a blocked app that was already open when you walked into the
 * bedroom. While one is in front it asks [BedroomGate] again every
 * [INTERVAL_MILLIS]. Like [SessionCapWatcher] it trusts no window event:
 * the keyboard, the shade and our own overlay all raise one, so nothing but
 * another blocked app or a lockdown stops it; instead each tick checks the
 * usage log, and stops once the app has left the front.
 */
class BedroomWatcher(
    private val scope: CoroutineScope,
    private val queries: BedroomQueries,
    private val onBlocked: (packageName: String, decision: BedroomDecision.Block) -> Unit,
) {
    private val lock = Mutex()
    private var timer: Job? = null
    private var watched: String? = null

    /** Call on every foreground-app change; only blocked apps arm it. */
    fun onForegroundApp(packageName: String) {
        scope.launch {
            if (!queries.watches(packageName)) return@launch
            lock.withLock {
                if (packageName == watched && timer?.isActive == true) return@launch
                timer?.cancel()
                watched = packageName
                timer = scope.launch { watch(packageName) }
            }
        }
    }

    private suspend fun watch(packageName: String) {
        while (true) {
            delay(INTERVAL_MILLIS)
            if (!queries.isInForeground(packageName)) return
            val decision = queries.decide(packageName)
            if (decision is BedroomDecision.Block) {
                onBlocked(packageName, decision)
                return
            }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        watched = null
    }

    companion object {
        const val INTERVAL_MILLIS = 2 * 60_000L
    }
}
```

- [ ] **Step 4: Run to verify GREEN** — same command. Expected: PASS (8 tests).
- [ ] **Step 5: Commit** — `git commit -m "feat: re-check every 2 minutes while a blocked app stays open"`

---

### Task 4: The bedroom screen

**Files:**
- Create: `app/src/main/java/com/anchor/ui/lock/BedroomBlockedActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register, like `LimitBlockedActivity`)
- Modify: `app/src/debug/AndroidManifest.xml` (export for the emulator script, like `LimitBlockedActivity`)
- Test: `app/src/test/java/com/anchor/ui/lock/BedroomCopyTest.kt`

**Interfaces:**
- Consumes: `formatMinute` (`com.anchor.ui.settings.sections`), `AppLabels.app`, `Eyebrow`, `AnchorTheme`.
- Produces: `object BedroomCopy { EYEBROW; TITLE; fun body(appName: String?, endMinute: Int): String }`; `BedroomBlockedActivity.intent(context: Context, blockedPackage: String, endMinute: Int): Intent`.

- [ ] **Step 1: Write the failing test** — `BedroomCopyTest.kt`:

```kotlin
package com.anchor.ui.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BedroomCopyTest {

    @Test
    fun `names the app and when it opens again`() {
        assertThat(BedroomCopy.body("YouTube", 7 * 60)).isEqualTo("YouTube stays closed in the bedroom until 07:00.")
    }

    @Test
    fun `falls back when the app has no label`() {
        assertThat(BedroomCopy.body(null, 6 * 60 + 30)).isEqualTo("This app stays closed in the bedroom until 06:30.")
    }

    @Test
    fun `title and eyebrow`() {
        assertThat(BedroomCopy.TITLE).isEqualTo("Not in the bedroom")
        assertThat(BedroomCopy.EYEBROW).isEqualTo("Bedroom")
    }
}
```

- [ ] **Step 2: Run to verify RED** — `./gradlew :app:testDebugUnitTest --tests 'com.anchor.ui.lock.BedroomCopyTest' --no-daemon -q` → compilation FAILS.

- [ ] **Step 3: Implement** — `BedroomBlockedActivity.kt`:

```kotlin
package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.settings.sections.formatMinute
import com.anchor.ui.theme.AnchorTheme

/** All bedroom-screen copy in one testable place. */
object BedroomCopy {
    const val EYEBROW = "Bedroom"
    const val TITLE = "Not in the bedroom"

    fun body(appName: String?, endMinute: Int): String =
        "${appName ?: "This app"} stays closed in the bedroom until ${formatMinute(endMinute)}."
}

/**
 * The bedroom's wall. There is no way through: answering the questions does
 * not open it, and the remote kill switch is the deliberate escape hatch.
 * Back and the one button both go to the home screen rather than back to the
 * app, which would only bring this screen up again.
 */
class BedroomBlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        val endMinute = intent.getIntExtra(EXTRA_END_MINUTE, 7 * 60)
        val appName = blockedPackage?.let { AppLabels.app(this, it) }

        setContent {
            AnchorTheme {
                BackHandler(enabled = true) { goHome() }
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Eyebrow(BedroomCopy.EYEBROW, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(20.dp))
                        Text(BedroomCopy.TITLE, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            BedroomCopy.body(appName, endMinute),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(44.dp))
                        Button(
                            onClick = ::goHome,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Go to home screen", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
        const val EXTRA_END_MINUTE = "com.anchor.extra.END_MINUTE"

        fun intent(context: Context, blockedPackage: String, endMinute: Int): Intent =
            Intent(context, BedroomBlockedActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
                putExtra(EXTRA_END_MINUTE, endMinute)
            }
    }
}
```

Main manifest, next to `LimitBlockedActivity`:

```xml
        <activity
            android:name=".ui.lock.BedroomBlockedActivity"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleInstance"
            android:taskAffinity=".lock" />
```

Debug manifest, next to the `LimitBlockedActivity` override:

```xml
        <activity
            android:name=".ui.lock.BedroomBlockedActivity"
            android:exported="true"
            tools:replace="android:exported" />
```

- [ ] **Step 4: Run to verify GREEN, and build** — `./gradlew :app:testDebugUnitTest --tests 'com.anchor.ui.lock.BedroomCopyTest' :app:assembleDebug --no-daemon -q` → PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git commit -m "feat: the Not in the bedroom screen"`

---

### Task 5: Wire the service, and the Settings card

**Files:**
- Modify: `app/src/main/java/com/anchor/service/AnchorAccessibilityService.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/sections/BedroomSection.kt`
- Modify: `app/src/main/java/com/anchor/ui/settings/sections/HomeAssistantSection.kt` (call `BedroomSection` after "Evening location")

**Interfaces:**
- Consumes: `BedroomGate`, `BedroomDecision`, `BedroomWatcher`, `BedroomQueries` (Tasks 2–3); `BedroomBlockedActivity.intent` (Task 4); `LimitGate.isInForeground`; `TimeRow`, `TextSetting`, `SettingsSection`, `SettingRow`, `Hint`, `parseRoomList`, `formatRoomList`.

This task has no new unit tests (the service and Compose UI have no harness here); it is verified by the full suite, a build, and the emulator.

- [ ] **Step 1: Service** — in `AnchorAccessibilityService`:

1. `@Inject lateinit var bedroomGate: BedroomGate` (import `com.anchor.domain.BedroomGate`, `BedroomDecision`, `BedroomQueries`, `BedroomWatcher`, `com.anchor.ui.lock.BedroomBlockedActivity`).
2. Below `sessionCapWatcher`:

```kotlin
    private val bedroomWatcher by lazy {
        BedroomWatcher(
            scope = scope,
            queries = object : BedroomQueries {
                override suspend fun watches(packageName: String) = bedroomGate.watches(packageName)
                override suspend fun isInForeground(packageName: String) = limitGate.isInForeground(packageName)
                override suspend fun decide(packageName: String) = bedroomGate.decide(packageName)
            },
            onBlocked = { packageName, decision -> showBedroomBlock(packageName, decision) },
        )
    }

    private fun showBedroomBlock(packageName: String, decision: BedroomDecision.Block) {
        relockBubble.hideNow()
        startActivity(BedroomBlockedActivity.intent(this, packageName, decision.endMinute))
    }
```

3. In `EvaluateEvening`, after `sessionCapWatcher.onForegroundApp(target)`: `bedroomWatcher.onForegroundApp(target)`.
4. Immediately after `if (PauseLedger.isIgnored(subject, now)) return@launch`:

```kotlin
                    // Before the pause ledger and both gates: in the bedroom
                    // nothing lets a blocked app through, not a pause sat
                    // elsewhere and not a spent limit's streak door.
                    val bedroom = bedroomGate.decide(target)
                    if (bedroom is BedroomDecision.Block) {
                        showBedroomBlock(target, bedroom)
                        return@launch
                    }
```

5. In `ReassertLock` and `onDestroy`, next to `sessionCapWatcher.cancel()`: `bedroomWatcher.cancel()`.

- [ ] **Step 2: Settings card** — `BedroomSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.PresenceSource
import com.anchor.data.settings.formatRoomList
import com.anchor.data.settings.parseRoomList
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SettingRow

@Composable
fun BedroomSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Bedroom block") {
        SettingRow(
            "Bedroom block",
            supporting = "Blocked apps don't open at all in these rooms during these hours, even after the " +
                "evening questions. Needs Home Assistant room detection; if it can't tell, apps open as usual.",
        ) {
            Switch(
                checked = settings.bedroomBlockEnabled,
                onCheckedChange = { on -> onChange { it.copy(bedroomBlockEnabled = on) } },
            )
        }
        if (settings.bedroomBlockEnabled) {
            if (settings.presenceSource == PresenceSource.VACATION) Hint("Off while vacation mode is on.")
            TextSetting("Rooms (comma-separated)", formatRoomList(settings.bedroomRooms), "Bedroom") { v ->
                onChange { it.copy(bedroomRooms = parseRoomList(v)) }
            }
            Hint("Matched against the tracker's name or state, case-insensitively.")
            TimeRow("Starts", settings.bedroomStartMinute) { m -> onChange { it.copy(bedroomStartMinute = m) } }
            TimeRow("Ends", settings.bedroomEndMinute) { m -> onChange { it.copy(bedroomEndMinute = m) } }
            Hint("Uses your blocked apps list.")
        }
    }
}
```

In `HomeAssistantSection`, directly after the closing brace of the `SettingsSection(title = "Evening location")` block: `BedroomSection(settings, onChange)`.

- [ ] **Step 3: Verify** — `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon -q` → BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 4: Emulator** — `scripts/emulator.sh up && scripts/emulator.sh install`; open Settings, scroll to "Bedroom block", switch it on, screenshot: rooms field shows `Bedroom`, Starts 22:00, Ends 07:00. Then `adb -e shell am start -n com.anchor/.ui.lock.BedroomBlockedActivity --es com.anchor.extra.BLOCKED_PACKAGE com.android.chrome --ei com.anchor.extra.END_MINUTE 420` and screenshot: "Not in the bedroom", "Chrome stays closed in the bedroom until 07:00.", one button; tapping it reaches the launcher. `scripts/emulator.sh down`.
- [ ] **Step 5: Commit** — `git commit -m "feat: wall off blocked apps in the bedroom; Settings card"`

---

### Task 6: Docs and final verification

**Files:**
- Modify: `README.md`
- Modify: `docs/manual-verification.md`

- [ ] **Step 1: README** — after the "Phone location and vacation mode" subsection, add:

```markdown
### Bedroom block

Home and bed can have different rules. With **Settings → Bedroom block** on, during
its hours (22:00–07:00 by default) a blocked app does not open at all while Home
Assistant places you in one of the bedroom rooms (`Bedroom` by default, matched in
the tracker's name or state). Answering the evening questions does not change that;
elsewhere at home the questions work as before. An app already open when you walk
in is closed within 2 minutes. It uses the evening blocked-apps list.

It only blocks on a confirmed reading. If Home Assistant is down, the room is
unknown, vacation mode is on or the kill switch is flipped, apps open under the
normal rules. "Ask even when location is unknown" does not make it stricter.
```

In the fail-open table, add a column-consistent row:

```markdown
| Bedroom block, Home Assistant unreachable | — | normal evening rules | unaffected |
```

- [ ] **Step 2: Manual checklist** — before `## 8.` in `docs/manual-verification.md`:

```markdown
## 7k. Bedroom block

1. Turn on Bedroom block with hours containing now, a blocked app, and the HA
   tracker reporting `Bedroom`. Open the app: verify "Not in the bedroom" with the
   app's name and end time, and that back and the button both reach the home screen.
2. Answer the evening questions, open the app again in the bedroom: still blocked.
3. Set the tracker to `Living Room`: the evening questions (or pause) appear instead.
4. Stop Home Assistant: the app opens under the normal rules, never the bedroom screen.
5. Open the app in the living room, then set the tracker to `Bedroom`: verify it is
   closed within 2 minutes.
6. With a daily limit spent and streak mode on, in the bedroom: the bedroom screen
   appears, not the limit screen with "Open anyway".
7. Flip the HA kill switch: the app opens.
```

- [ ] **Step 3: Full verification** — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon -q` → BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 4: Commit** — `git commit -m "docs: the bedroom block"`
