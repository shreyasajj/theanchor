package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.EarlyLock
import com.anchor.data.usage.UsageEvent
import com.anchor.data.usage.UsageStatsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class LimitGateTest {

    private lateinit var db: AnchorDatabase
    private val app = "com.google.android.youtube"
    private val zone = ZoneId.of("America/Los_Angeles")
    private val minute = 60_000L

    /** Fixed "now": 2026-09-09 15:00 local. Usage day started at 04:00. */
    private val clock = Clock.fixed(LocalDateTime.parse("2026-09-09T15:00:00").atZone(zone).toInstant(), zone)
    private val dayStartMillis = LocalDateTime.parse("2026-09-09T04:00:00").atZone(zone).toInstant().toEpochMilli()

    private class FakeSource(private val events: List<UsageEvent>) : UsageStatsSource {
        override suspend fun events(fromMillis: Long, toMillis: Long) =
            events.filter { it.timestampMillis in fromMillis..toMillis }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun fg(minutesAfterDayStart: Long) =
        UsageEvent(app, UsageEvent.Type.FOREGROUND, dayStartMillis + minutesAfterDayStart * minute)

    private fun bg(minutesAfterDayStart: Long) =
        UsageEvent(app, UsageEvent.Type.BACKGROUND, dayStartMillis + minutesAfterDayStart * minute)

    private fun gate(
        events: List<UsageEvent> = emptyList(),
        settings: AnchorSettings = AnchorSettings(),
        haResult: HaResult = HaResult.Unavailable,
        source: UsageStatsSource = FakeSource(events),
    ): LimitGate {
        val client = FakeHaClient(haResult)
        return LimitGate(
            appLimitDao = db.appLimitDao(),
            earlyLockDao = db.earlyLockDao(),
            usageStatsSource = source,
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
            settingsProvider = { settings },
        )
    }

    // --- No configuration ---

    @Test
    fun `an app with no limit row is allowed`() = runTest {
        assertThat(gate().decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `a disabled limit row is allowed`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, enabled = false, dailyMinutes = 1))
        assertThat(gate().decide(app)).isEqualTo(LimitDecision.Allow)
    }

    // --- Daily time ---

    @Test
    fun `allowed while under the daily time budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 30))
        assertThat(gate(events = listOf(fg(60), bg(80))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `blocked once the daily time budget is spent`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 30))
        val decision = gate(events = listOf(fg(60), bg(95))).decide(app)

        assertThat(decision).isInstanceOf(LimitDecision.Blocked::class.java)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_TIME)
    }

    @Test
    fun `blocked exactly at the budget, not one minute past`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 30))
        assertThat(gate(events = listOf(fg(60), bg(90))).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `the block reports the next reset time`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 30))
        val decision = gate(events = listOf(fg(60), bg(95))).decide(app) as LimitDecision.Blocked

        val expected = LocalDateTime.parse("2026-09-10T04:00:00").atZone(zone).toInstant().toEpochMilli()
        assertThat(decision.resetsAtMillis).isEqualTo(expected)
    }

    @Test
    fun `usage before the day reset does not count against today`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 30))
        assertThat(gate(events = listOf(fg(-100), bg(-60))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    // --- Daily opens ---

    @Test
    fun `allowed while under the open count`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 3))
        val events = listOf(fg(10), bg(15), fg(60), bg(65))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `blocked past the open count`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2))
        val events = listOf(fg(10), bg(15), fg(60), bg(65), fg(120), bg(125))
        val decision = gate(events = events).decide(app)

        assertThat(decision).isInstanceOf(LimitDecision.Blocked::class.java)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_OPENS)
    }

    @Test
    fun `at exactly the open count the user is still allowed in`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2))
        val events = listOf(fg(10), bg(15), fg(60), bg(65))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    // --- Cooldown ---

    @Test
    fun `blocked during the cooldown after closing`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        val decision = gate(events = listOf(fg(600), bg(650))).decide(app)

        assertThat(decision).isInstanceOf(LimitDecision.Blocked::class.java)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.COOLDOWN)
    }

    @Test
    fun `the cooldown block reports when the app becomes available`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        val decision = gate(events = listOf(fg(600), bg(650))).decide(app) as LimitDecision.Blocked

        assertThat(decision.resetsAtMillis).isEqualTo(dayStartMillis + 680 * minute)
    }

    @Test
    fun `allowed once the cooldown has elapsed`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        assertThat(gate(events = listOf(fg(560), bg(600))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `a cooldown started before the daily reset still applies`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        val gate = gate(source = object : UsageStatsSource {
            override suspend fun events(fromMillis: Long, toMillis: Long) = listOf(fg(-700), bg(-10))
        })
        assertThat(gate.decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `the source is queried from before the day start`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        var from = 0L
        gate(source = object : UsageStatsSource {
            override suspend fun events(fromMillis: Long, toMillis: Long): List<UsageEvent> {
                from = fromMillis
                return emptyList()
            }
        }).decide(app)
        assertThat(from).isLessThan(dayStartMillis)
    }

    // --- Session-window rejoin ---

    @Test
    fun `returning inside the session window skips the cooldown`() = runTest {
        // 10-min sessions, 30-min cooldown. Opened at 655, left at 658, now 660.
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, cooldownMinutes = 30))
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `returning inside the session window is not charged an open`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, dailyOpens = 1))
        // One open already spent at 100; the current one began at 655.
        val events = listOf(fg(100), bg(110), fg(655), bg(658))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `returning inside the session window skips the pre-open pause`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `returning inside the session window still respects the time budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, dailyMinutes = 30))
        val events = listOf(fg(100), bg(130), fg(655), bg(658))   // 33 minutes used
        val decision = gate(events = events).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_TIME)
    }

    @Test
    fun `after the session window the cooldown applies again`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, cooldownMinutes = 30))
        // Opened at 640, left at 645; now is 660, past 640+10.
        val decision = gate(events = listOf(fg(640), bg(645))).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.COOLDOWN)
    }

    // --- Early lock ---

    @Test
    fun `lockEarly records a lock only for a limited app`() = runTest {
        assertThat(gate().lockEarly(app)).isFalse()
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10))
        assertThat(gate().lockEarly(app)).isTrue()
        assertThat(db.earlyLockDao().since(app, 0)).containsExactly(clock.millis())
    }

    @Test
    fun `an early lock ends the session window so a return is a new open`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        db.earlyLockDao().insert(EarlyLock(packageName = app, atMillis = dayStartMillis + 658 * minute))
        // Would rejoin (opened at 655) if not for the lock; now it gets the pause.
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(app)).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `an early lock starts the cooldown`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        db.earlyLockDao().insert(EarlyLock(packageName = app, atMillis = dayStartMillis + 650 * minute))
        val decision = gate(events = listOf(fg(600), bg(640))).decide(app) as LimitDecision.Blocked
        assertThat(decision.reason).isEqualTo(LimitReason.COOLDOWN)
        assertThat(decision.resetsAtMillis).isEqualTo(dayStartMillis + 680 * minute)
    }

    @Test
    fun `the return after an early lock costs half an open`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2))
        db.earlyLockDao().insert(EarlyLock(packageName = app, atMillis = dayStartMillis + 115 * minute))
        // Opens at 100 (1.0), 120 (0.5, after the lock), 300 (1.0) = 2.5 > 2.
        val events = listOf(fg(100), bg(110), fg(120), bg(130), fg(300), bg(310))
        val decision = gate(events = events).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_OPENS)

        // Without the half-open the same day would be exactly 2, and allowed.
        val summary = gate(events = events).summaryFor(app)
        assertThat(summary.openUnits).isEqualTo(2.5)
    }

    // --- Pre-open pause ---

    @Test
    fun `a configured pause is returned when nothing else blocks`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, preOpenDelaySeconds = 30))
        assertThat(gate().decide(app)).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `a hard block outranks the pause`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 10, preOpenDelaySeconds = 30))
        assertThat(gate(events = listOf(fg(60), bg(90))).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    // --- Ordering and the kill switch ---

    @Test
    fun `cooldown is reported ahead of the daily time budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 10, cooldownMinutes = 30))
        val decision = gate(events = listOf(fg(600), bg(650))).decide(app)

        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.COOLDOWN)
    }

    @Test
    fun `an active kill switch allows everything through`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 1))
        val settings = AnchorSettings(
            haBaseUrl = "http://ha.local:8123",
            haToken = "t",
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val decision = gate(
            events = listOf(fg(60), bg(120)),
            settings = settings,
            haResult = HaResult.Ok(HaStateDto("e", "on", emptyMap())),
        ).decide(app)

        assertThat(decision).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `FAIL-OPEN - no usage data measured means nothing is blocked`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyMinutes = 1, dailyOpens = 1))
        assertThat(gate(events = emptyList()).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `summaryFor exposes usage for the dashboard`() = runTest {
        val summary = gate(events = listOf(fg(60), bg(80))).summaryFor(app)
        assertThat(summary.foregroundMillis).isEqualTo(20 * minute)
        assertThat(summary.opens).isEqualTo(1)
    }
}
