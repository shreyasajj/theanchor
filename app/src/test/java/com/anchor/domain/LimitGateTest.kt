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
