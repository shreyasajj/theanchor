package com.anchor.domain

import com.anchor.data.usage.LimitMode
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
    fun resetLedger() = PauseLedger.reset()

    @After
    fun clearLedger() = PauseLedger.reset()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun fg(minutesAfterDayStart: Long, pkg: String = app) =
        UsageEvent(pkg, UsageEvent.Type.FOREGROUND, dayStartMillis + minutesAfterDayStart * minute)

    private fun bg(minutesAfterDayStart: Long, pkg: String = app) =
        UsageEvent(pkg, UsageEvent.Type.BACKGROUND, dayStartMillis + minutesAfterDayStart * minute)

    /** Stores the limit and returns its ledger subject. */
    private suspend fun limit(limit: AppLimit): String = "limit:${db.appLimitDao().upsert(limit)}"

    private fun gate(
        events: List<UsageEvent> = emptyList(),
        settings: AnchorSettings = AnchorSettings(),
        haResult: HaResult = HaResult.Unavailable,
        source: UsageStatsSource = FakeSource(events),
    ): LimitGate {
        val client = FakeHaClient(haResult)
        return LimitGate(
            limits = DaoLimitLookup(db.appLimitDao()),
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
        db.appLimitDao().upsert(AppLimit(app, enabled = false, limitMode = LimitMode.TIME, dailyMinutes = 1))
        assertThat(gate().decide(app)).isEqualTo(LimitDecision.Allow)
    }

    // --- Daily time ---

    @Test
    fun `allowed while under the daily time budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30))
        assertThat(gate(events = listOf(fg(60), bg(80))).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `blocked once the daily time budget is spent`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30))
        val decision = gate(events = listOf(fg(60), bg(95))).decide(app)

        assertThat(decision).isInstanceOf(LimitDecision.Blocked::class.java)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_TIME)
    }

    @Test
    fun `blocked exactly at the budget, not one minute past`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30))
        assertThat(gate(events = listOf(fg(60), bg(90))).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `the block reports the next reset time`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30))
        val decision = gate(events = listOf(fg(60), bg(95))).decide(app) as LimitDecision.Blocked

        val expected = LocalDateTime.parse("2026-09-10T04:00:00").atZone(zone).toInstant().toEpochMilli()
        assertThat(decision.resetsAtMillis).isEqualTo(expected)
    }

    @Test
    fun `usage before the day reset does not count against today`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30))
        assertThat(gate(events = listOf(fg(-100), bg(-60))).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    // --- Daily opens ---

    @Test
    fun `allowed while under the open count`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 3))
        val events = listOf(fg(10), bg(15), fg(60), bg(65))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Pause(0))
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
    fun `at exactly the open count the next launch is blocked`() = runTest {
        // Two opens done and closed; a third would be one too many.
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2))
        val events = listOf(fg(10), bg(15), fg(60), bg(65))
        assertThat(gate(events = events).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `the running open never counts against itself`() = runTest {
        // Second open of two is in progress (its foreground event landed); a
        // re-evaluation mid-use must not block it.
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2))
        val events = listOf(fg(10), bg(15), fg(600))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `in opens mode the minute budget is ignored`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.OPENS, dailyMinutes = 10, dailyOpens = 5))
        assertThat(gate(events = listOf(fg(60), bg(120))).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `in time mode the open budget is ignored`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 100, dailyOpens = 1))
        assertThat(gate(events = listOf(fg(10), bg(15), fg(60), bg(65))).decide(app)).isEqualTo(LimitDecision.Pause(0))
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
        assertThat(gate(events = listOf(fg(560), bg(600))).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `a cooldown started before the daily reset still applies`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        val gate = gate(source = object : UsageStatsSource {
            override suspend fun events(fromMillis: Long, toMillis: Long) = listOf(fg(-700), bg(-10))
        })
        assertThat(gate.decide(app)).isEqualTo(LimitDecision.Pause(0))
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
    fun `a fresh launch whose own foreground event is already logged is not a rejoin`() = runTest {
        // The launch being decided (started now, never left) must still pay
        // the pause; otherwise the pre-open pause never shows at all.
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        assertThat(gate(events = listOf(fg(660))).decide(app)).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `a fresh launch after an old open pays the cooldown and open`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, cooldownMinutes = 30))
        // Left at 645, launched again at 660 (logged). 660 - 640 > 10 min window.
        val decision = gate(events = listOf(fg(640), bg(645), fg(660))).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.COOLDOWN)
    }

    @Test
    fun `THE BYPASS - walking away from the pause does not become a rejoin`() = runTest {
        // Opening the app shows the pause, which itself pushes the app to the
        // background and logs a BACKGROUND event. Coming back must not read
        // as "left and returned", or the pause is skipped for good.
        val subject = limit(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        PauseLedger.begin(subject)
        PauseLedger.abandon(subject)

        val decision = gate(events = listOf(fg(655), bg(656))).decide(app)

        assertThat(decision).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `once the pause is served, the return no longer costs an open`() = runTest {
        // dailyOpens is already spent, so only a rejoin can let this through.
        val subject = limit(AppLimit(app, sessionMinutes = 10, dailyOpens = 1))
        PauseLedger.begin(subject)
        PauseLedger.complete(subject, clock.millis())

        val events = listOf(fg(100), bg(110), fg(655), bg(658))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    // --- What counts as an open worth rejoining ---

    @Test
    fun `a launch blip is not an established open`() = runTest {
        // Launching an app emits foreground then background a fraction of a
        // second later. Treating that as "left and returned" waved the pause
        // through on the very first open of any app with a session cap.
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        val justNow = dayStartMillis + 660 * minute
        val events = listOf(
            UsageEvent(app, UsageEvent.Type.FOREGROUND, justNow - 800),
            UsageEvent(app, UsageEvent.Type.BACKGROUND, justNow - 600),
        )

        val decision = gate(events = events).decide(app)

        assertThat(decision).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `an open must be running a while before a return rejoins it`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, dailyOpens = 1))
        val justNow = dayStartMillis + 660 * minute
        // Ten seconds old: under the floor, so this is a fresh open and the
        // spent open budget applies.
        val young = listOf(
            fg(100), bg(110),
            UsageEvent(app, UsageEvent.Type.FOREGROUND, justNow - 10_000),
            UsageEvent(app, UsageEvent.Type.BACKGROUND, justNow - 500),
        )
        assertThat(gate(events = young).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)

        // A minute old: a real session, so returning to it is a rejoin and the
        // second open is not charged again.
        val established = listOf(
            fg(100), bg(110),
            UsageEvent(app, UsageEvent.Type.FOREGROUND, justNow - 60_000),
            UsageEvent(app, UsageEvent.Type.BACKGROUND, justNow - 500),
        )
        assertThat(gate(events = established).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `a pause owed from a previous process still blocks the rejoin`() = runTest {
        // The debt is persisted precisely because the process restarts and an
        // in-memory flag would be forgotten.
        val subject = limit(AppLimit(app, sessionMinutes = 10, dailyOpens = 1))
        val settings = AnchorSettings(pausesOwed = setOf(subject))
        val events = listOf(fg(100), bg(110), fg(655), bg(658))

        val decision = gate(events = events, settings = settings).decide(app)

        assertThat(decision).isInstanceOf(LimitDecision.Blocked::class.java)
    }

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
    fun `a rejoin is not paused again - the pause was served on the way in`() = runTest {
        // Sat through the pause at 655, stepped out at 658, back at 660 with
        // seven minutes of the session left: the same open, straight in.
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `once the session has elapsed the next open is paused again`() = runTest {
        // Opened at 650, ten-minute session, now 660: over, even though the
        // user was only in it for two minutes. The session is wall clock.
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        assertThat(gate(events = listOf(fg(650), bg(652))).decide(app)).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `the user's example - two minutes used, back after the session, budget intact`() = runTest {
        // 20-minute budget, 5-minute session. Two minutes in at 650, out at
        // 652, back at 660: asked again (pause), and 18 minutes still left.
        db.appLimitDao().upsert(
            AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 20, sessionMinutes = 5, preOpenDelaySeconds = 20)
        )
        val gate = gate(events = listOf(fg(650), bg(652)))
        assertThat(gate.decide(app)).isEqualTo(LimitDecision.Pause(20))
        assertThat(gate.budgetFor(app)).contains("18 of 20 minutes left today")
    }

    @Test
    fun `time away does not count against the minutes budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 5, sessionMinutes = 10))
        // In for two minutes, out for six, back at 660 inside the session.
        val events = listOf(fg(652), bg(654))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `a rejoin with no pause configured just opens`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10))
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `returning inside the session window still respects the time budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, limitMode = LimitMode.TIME, dailyMinutes = 30))
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
        val subject = limit(AppLimit(app, sessionMinutes = 10))
        assertThat(gate().lockEarly(app)).isTrue()
        assertThat(db.earlyLockDao().since(subject, 0)).containsExactly(clock.millis())
    }

    @Test
    fun `an early lock ends the session window so a return is a new open`() = runTest {
        val subject = limit(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 30))
        db.earlyLockDao().insert(EarlyLock(subject = subject, atMillis = dayStartMillis + 658 * minute))
        // Would rejoin (opened at 655) if not for the lock; now it gets the pause.
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(app)).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `an early lock starts the cooldown`() = runTest {
        val subject = limit(AppLimit(app, cooldownMinutes = 30))
        db.earlyLockDao().insert(EarlyLock(subject = subject, atMillis = dayStartMillis + 650 * minute))
        val decision = gate(events = listOf(fg(600), bg(640))).decide(app) as LimitDecision.Blocked
        assertThat(decision.reason).isEqualTo(LimitReason.COOLDOWN)
        assertThat(decision.resetsAtMillis).isEqualTo(dayStartMillis + 680 * minute)
    }

    @Test
    fun `the return after an early lock is a whole new open`() = runTest {
        val subject = limit(AppLimit(app, dailyOpens = 2, sessionMinutes = 30))
        db.earlyLockDao().insert(EarlyLock(subject = subject, atMillis = dayStartMillis + 105 * minute))
        // Opened at 100, locked at 105, back at 110: inside the session, but
        // the lock ended it, so that is open number two and it costs one.
        val events = listOf(fg(100), bg(105), fg(110), bg(120))
        val summary = gate(events = events).summaryFor(app)
        assertThat(summary.opens).isEqualTo(2)
        assertThat(summary.openUnits).isEqualTo(2.0)
        // Both opens are over, so the third launch is blocked.
        val decision = gate(events = events).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_OPENS)
    }

    // --- Pre-open pause ---

    @Test
    fun `a configured pause is returned when nothing else blocks`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, preOpenDelaySeconds = 30))
        assertThat(gate().decide(app)).isEqualTo(LimitDecision.Pause(30))
    }

    @Test
    fun `a hard block outranks the pause`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 10, preOpenDelaySeconds = 30))
        assertThat(gate(events = listOf(fg(60), bg(90))).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    // --- Ordering and the kill switch ---

    @Test
    fun `a spent daily budget is reported ahead of a cooldown`() = runTest {
        // Otherwise the user sits out the cooldown only to be told the day
        // is over: the longer wait is the honest answer.
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 10, cooldownMinutes = 30))
        val decision = gate(events = listOf(fg(600), bg(650))).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_TIME)

        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 1, cooldownMinutes = 30).copy(id = db.appLimitDao().find(app)!!.id))
        val byOpens = gate(events = listOf(fg(600), bg(650))).decide(app)
        assertThat((byOpens as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_OPENS)
    }

    @Test
    fun `a cooldown with budget left is still a cooldown`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 3, cooldownMinutes = 30))
        val decision = gate(events = listOf(fg(600), bg(650))).decide(app)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.COOLDOWN)
    }

    // --- After the session cap fires ---

    @Test
    fun `after the last open's session, the cap screen says the day is spent`() = runTest {
        // Two opens, ten-minute sessions. The second session, running now,
        // just hit its cap: that was the last open, and the screen should
        // say so rather than promise a cooldown.
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2, sessionMinutes = 10, cooldownMinutes = 30))
        val blocked = gate(events = listOf(fg(100), bg(110), fg(650))).afterSessionCap(app) as LimitDecision.Blocked
        assertThat(blocked.reason).isEqualTo(LimitReason.DAILY_OPENS)
    }

    @Test
    fun `after a session with opens left, the cap screen promises the cooldown`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 3, sessionMinutes = 10, cooldownMinutes = 30))
        val blocked = gate(events = listOf(fg(650))).afterSessionCap(app) as LimitDecision.Blocked
        assertThat(blocked.reason).isEqualTo(LimitReason.SESSION_CAP)
        assertThat(blocked.resetsAtMillis).isEqualTo(clock.millis() + 30 * minute)
    }

    @Test
    fun `after a session, with nothing else in the way, the user is asked again`() = runTest {
        // The session's job is to force the question: the pause comes up,
        // at the limit's own length.
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10, preOpenDelaySeconds = 20))
        assertThat(gate(events = listOf(fg(650))).afterSessionCap(app)).isEqualTo(LimitDecision.Pause(20))
    }

    @Test
    fun `after a session with no pause configured, the question is asked with no countdown`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10))
        assertThat(gate(events = listOf(fg(650))).afterSessionCap(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `continuing after the session is a new open`() = runTest {
        // Session 650-660 ended; the user sat the pause and came back at 660.
        // That is open number two.
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 5, sessionMinutes = 10))
        val summary = gate(events = listOf(fg(650), bg(659), fg(660))).summaryFor(app)
        assertThat(summary.opens).isEqualTo(2)
    }

    // --- Early lock grace ---

    @Test
    fun `an early lock ignores the app's momentary return while it is on its way out`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, sessionMinutes = 10))
        val gate = gate(events = listOf(fg(650)))
        val subject = gate.limitFor(app)!!.subject
        assertThat(gate.lockEarly(app)).isTrue()
        assertThat(PauseLedger.isIgnored(subject, clock.millis() + 1_000)).isTrue()
        assertThat(PauseLedger.isIgnored(subject, clock.millis() + PauseLedger.EARLY_LOCK_GRACE_MILLIS)).isFalse()
    }

    // --- Schedules: limits with hours ---
    // "Now" is 15:00. The usage day started at 04:00.

    @Test
    fun `a limit outside its hours does nothing`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 0).copy(windowStartMinute = 17 * 60, windowEndMinute = 21 * 60))
        assertThat(gate(events = listOf(fg(600), bg(610))).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `a limit inside its hours applies`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 0).copy(windowStartMinute = 14 * 60, windowEndMinute = 17 * 60))
        assertThat(gate().decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `the budget counts only what happened inside the hours`() = runTest {
        // Two opens at 09:00 and 10:00 (before the 14:00 window) do not
        // count; one at 14:30 does. Budget of two: still one left.
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 2).copy(windowStartMinute = 14 * 60, windowEndMinute = 17 * 60))
        val events = listOf(fg(300), bg(310), fg(360), bg(370), fg(630), bg(640))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Pause(0))

        val spent = events + listOf(fg(650), bg(655))
        assertThat(gate(events = spent).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `a spent windowed budget comes back at the end of the window`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 1).copy(windowStartMinute = 14 * 60, windowEndMinute = 17 * 60))
        val decision = gate(events = listOf(fg(630), bg(640))).decide(app) as LimitDecision.Blocked
        val expected = LocalDateTime.parse("2026-09-09T17:00:00").atZone(zone).toInstant().toEpochMilli()
        assertThat(decision.resetsAtMillis).isEqualTo(expected)
    }

    @Test
    fun `one app can have different budgets at different hours`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 5).copy(windowStartMinute = 9 * 60, windowEndMinute = 14 * 60))
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 1).copy(windowStartMinute = 14 * 60, windowEndMinute = 17 * 60))
        // Three opens this morning under the generous limit, none since 14:00.
        val events = listOf(fg(300), bg(310), fg(360), bg(370), fg(400), bg(410))
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Pause(0))
        assertThat(gate(events = events).limitFor(app)!!.dailyOpens).isEqualTo(1)
    }

    @Test
    fun `an active kill switch allows everything through`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 1))
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
        db.appLimitDao().upsert(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 1, dailyOpens = 1))
        assertThat(gate(events = emptyList()).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `summaryFor exposes usage for the dashboard`() = runTest {
        val summary = gate(events = listOf(fg(60), bg(80))).summaryFor(app)
        assertThat(summary.foregroundMillis).isEqualTo(20 * minute)
        assertThat(summary.opens).isEqualTo(1)
    }

    // --- Groups ---

    private val instagram = "com.instagram.android"

    @Test
    fun `apps in one group share the open budget`() = runTest {
        db.appLimitDao().upsert(AppLimit(name = "Social", packages = setOf(app, instagram), dailyOpens = 2))
        val events = listOf(fg(10), bg(15), fg(60, instagram), bg(65, instagram))
        assertThat(gate(events = events).decide(app)).isInstanceOf(LimitDecision.Blocked::class.java)
        assertThat(gate(events = events).decide(instagram)).isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `apps in one group share the session`() = runTest {
        // YouTube's session began at 648 and is over by 660; Instagram is a
        // fresh open of the group and pays the pause.
        db.appLimitDao().upsert(
            AppLimit(name = "Social", packages = setOf(app, instagram), sessionMinutes = 10, preOpenDelaySeconds = 30)
        )
        assertThat(gate(events = listOf(fg(648), bg(658))).decide(instagram)).isEqualTo(LimitDecision.Pause(30))

        // YouTube's session began at 655: switching to Instagram rejoins it.
        assertThat(gate(events = listOf(fg(655), bg(658))).decide(instagram)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `the user's example - two opens of ten minutes across two apps`() = runTest {
        db.appLimitDao().upsert(
            AppLimit(name = "Social", packages = setOf(app, instagram), dailyOpens = 2, sessionMinutes = 10)
        )
        // Open 1: ten minutes of YouTube. Open 2: five of Instagram, five of
        // YouTube. Both sessions spent; a third open is one too many.
        val events = listOf(
            fg(100), bg(110),
            fg(300, instagram), bg(305, instagram), fg(305), bg(310),
        )
        val decision = gate(events = events).decide(instagram)
        assertThat((decision as LimitDecision.Blocked).reason).isEqualTo(LimitReason.DAILY_OPENS)
    }

    @Test
    fun `an app outside the group is not limited by it`() = runTest {
        db.appLimitDao().upsert(AppLimit(name = "Social", packages = setOf(app), dailyOpens = 1))
        assertThat(gate(events = listOf(fg(10), bg(15))).decide(instagram)).isEqualTo(LimitDecision.Allow)
    }

    // --- Streak-mode bypass ---

    @Test
    fun `a limit walked through today is waived until the reset`() = runTest {
        val subject = limit(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 10))
        val settings = AnchorSettings(limitBypasses = setOf(Streak.bypassKey(subject, "2026-09-09")))
        assertThat(gate(events = listOf(fg(60), bg(120)), settings = settings).decide(app))
            .isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `yesterday's bypass does not carry over`() = runTest {
        val subject = limit(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 10))
        val settings = AnchorSettings(limitBypasses = setOf(Streak.bypassKey(subject, "2026-09-08")))
        assertThat(gate(events = listOf(fg(60), bg(120)), settings = settings).decide(app))
            .isInstanceOf(LimitDecision.Blocked::class.java)
    }

    @Test
    fun `a bypass also silences the session cap`() = runTest {
        val subject = limit(AppLimit(app, sessionMinutes = 10))
        val settings = AnchorSettings(limitBypasses = setOf(Streak.bypassKey(subject, "2026-09-09")))
        assertThat(gate(events = listOf(fg(655)), settings = settings).sessionRemainingMillis(app)).isNull()
        assertThat(gate(events = listOf(fg(655))).sessionRemainingMillis(app)).isEqualTo(5 * minute)
    }

    // --- Dashboard summaries ---

    @Test
    fun `summaries reads the event log once for every limit`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 3))
        db.appLimitDao().upsert(AppLimit(instagram, dailyOpens = 3))
        var reads = 0
        val gate = gate(source = object : UsageStatsSource {
            override suspend fun events(fromMillis: Long, toMillis: Long): List<UsageEvent> {
                reads++
                return listOf(fg(10), bg(15), fg(20, instagram), bg(25, instagram))
            }
        })
        val summaries = gate.summaries()
        assertThat(reads).isEqualTo(1)
        assertThat(summaries.values.map { it.opens }).containsExactly(1, 1)
    }

    @Test
    fun `dashboard usage breaks a group down per app and reads the log once`() = runTest {
        db.appLimitDao().upsert(AppLimit(name = "Social", packages = setOf(app, instagram), dailyOpens = 3, sessionMinutes = 30))
        var reads = 0
        val gate = gate(source = object : UsageStatsSource {
            override suspend fun events(fromMillis: Long, toMillis: Long): List<UsageEvent> {
                reads++
                return listOf(fg(10), bg(15), fg(15, instagram), bg(25, instagram))
            }
        })
        val usage = gate.dashboardUsage().single()
        assertThat(reads).isEqualTo(1)
        assertThat(usage.summary.opens).isEqualTo(1)
        assertThat(usage.summary.foregroundMillis).isEqualTo(15 * minute)
        assertThat(usage.perApp.getValue(app).foregroundMillis).isEqualTo(5 * minute)
        assertThat(usage.perApp.getValue(instagram).foregroundMillis).isEqualTo(10 * minute)
        assertThat(usage.status).isEqualTo(LimitStatus.Available)
    }

    @Test
    fun `dashboard usage reports a running session`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 3, sessionMinutes = 30))
        // 15:00 is 660 minutes after 04:00; the open began ten minutes ago.
        val usage = gate(events = listOf(fg(650))).dashboardUsage().single()
        assertThat(usage.status)
            .isEqualTo(LimitStatus.InSession(endsAtMillis = dayStartMillis + 680 * minute, inForeground = true))
    }
}
