package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class PauseLedgerTest {

    private val app = "com.google.android.youtube"
    private val now = 1_757_000_000_000L

    @Before fun setUp() = PauseLedger.reset()
    @After fun tearDown() = PauseLedger.reset()

    @Test
    fun `an app with no pause history is not satisfied`() {
        assertThat(PauseLedger.isSatisfied(app, now)).isFalse()
    }

    @Test
    fun `finishing the wait satisfies the app for the hand-off`() {
        PauseLedger.complete(app, now)

        assertThat(PauseLedger.isSatisfied(app, now + 1_000)).isTrue()
        assertThat(PauseLedger.isSatisfied(app, now + PauseLedger.SATISFIED_WINDOW_MILLIS - 1)).isTrue()
    }

    @Test
    fun `the satisfied window expires, so a later open is paused again`() {
        PauseLedger.complete(app, now)
        assertThat(PauseLedger.isSatisfied(app, now + PauseLedger.SATISFIED_WINDOW_MILLIS)).isFalse()
    }

    @Test
    fun `THE BYPASS - walking away from the pause never satisfies the app`() {
        // Leaving the pause screen and coming straight back must not get in.
        PauseLedger.abandon(app)

        assertThat(PauseLedger.isSatisfied(app, now)).isFalse()
        assertThat(PauseLedger.isSatisfied(app, now + 1_000)).isFalse()
    }

    @Test
    fun `abandoning after finishing takes the satisfaction away again`() {
        PauseLedger.complete(app, now)
        PauseLedger.abandon(app)
        assertThat(PauseLedger.isSatisfied(app, now + 1_000)).isFalse()
    }

    @Test
    fun `an abandonment is reported once, so the debounce is cleared only once`() {
        PauseLedger.abandon(app)

        assertThat(PauseLedger.consumeAbandonment(app)).isTrue()
        assertThat(PauseLedger.consumeAbandonment(app)).isFalse()
    }

    @Test
    fun `finishing clears a pending abandonment`() {
        PauseLedger.abandon(app)
        PauseLedger.complete(app, now)
        assertThat(PauseLedger.consumeAbandonment(app)).isFalse()
    }

    // --- An unfinished pause blocks a session rejoin ---

    @Test
    fun `an app with no pause has nothing unfinished`() {
        assertThat(PauseLedger.hasUnfinishedPause(app)).isFalse()
    }

    @Test
    fun `showing a pause leaves it unfinished`() {
        PauseLedger.begin(app)
        assertThat(PauseLedger.hasUnfinishedPause(app)).isTrue()
    }

    @Test
    fun `walking away leaves the pause still owed`() {
        PauseLedger.begin(app)
        PauseLedger.abandon(app)
        assertThat(PauseLedger.hasUnfinishedPause(app)).isTrue()
    }

    @Test
    fun `sitting through it settles the debt`() {
        PauseLedger.begin(app)
        PauseLedger.complete(app, now)
        assertThat(PauseLedger.hasUnfinishedPause(app)).isFalse()
    }

    @Test
    fun `beginning a pause drops any earlier satisfaction`() {
        PauseLedger.complete(app, now)
        PauseLedger.begin(app)
        assertThat(PauseLedger.isSatisfied(app, now + 1_000)).isFalse()
    }

    @Test
    fun `a blank package is ignored rather than tracked`() {
        PauseLedger.begin("")
        assertThat(PauseLedger.hasUnfinishedPause("")).isFalse()
    }

    @Test
    fun `the ledger is per package`() {
        PauseLedger.complete(app, now)

        assertThat(PauseLedger.isSatisfied("com.instagram.android", now + 1_000)).isFalse()
    }

    // --- Inside an open ---

    private val group = "limit:1"
    private val launcher = "com.android.launcher"
    private val grace = PauseLedger.RETURN_GRACE_MILLIS

    @Test
    fun `entering marks the subject inside`() {
        PauseLedger.enter(group)
        assertThat(PauseLedger.isInside(group, now)).isTrue()
        assertThat(PauseLedger.isInside("limit:2", now)).isFalse()
    }

    @Test
    fun `back within the grace is still inside`() {
        PauseLedger.enter(group)
        PauseLedger.onForeground(launcher, now)
        PauseLedger.onForeground(group, now + grace - 1)
        assertThat(PauseLedger.isInside(group, now + 10 * grace)).isTrue()
    }

    @Test
    fun `away past the grace is out, and coming back does not restore it`() {
        PauseLedger.enter(group)
        PauseLedger.onForeground(launcher, now)
        assertThat(PauseLedger.isInside(group, now + grace)).isFalse()
        PauseLedger.onForeground(group, now + grace)
        assertThat(PauseLedger.isInside(group, now + grace)).isFalse()
    }

    @Test
    fun `hopping through other apps does not restart the grace`() {
        PauseLedger.enter(group)
        PauseLedger.onForeground(launcher, now)
        PauseLedger.onForeground("com.android.chrome", now + grace / 2)
        assertThat(PauseLedger.isInside(group, now + grace)).isFalse()
    }

    @Test
    fun `the same subject reaching the front is not stepping away - a group switch`() {
        PauseLedger.enter(group)
        PauseLedger.onForeground(group, now)
        assertThat(PauseLedger.isInside(group, now + 10 * grace)).isTrue()
    }

    @Test
    fun `an early lock leaves at once`() {
        PauseLedger.enter(group)
        PauseLedger.ignoreAfterEarlyLock(group, now)
        assertThat(PauseLedger.isInside(group, now)).isFalse()
    }

    @Test
    fun `a pause going up leaves at once - the open it guarded is over`() {
        PauseLedger.enter(group)
        PauseLedger.begin(group)
        assertThat(PauseLedger.isInside(group, now)).isFalse()
    }

    @Test
    fun `leave only clears its own subject`() {
        PauseLedger.enter(group)
        PauseLedger.leave("limit:2")
        assertThat(PauseLedger.isInside(group, now)).isTrue()
        PauseLedger.leave(group)
        assertThat(PauseLedger.isInside(group, now)).isFalse()
    }

    @Test
    fun `an unlimited app let through does not take the slot - the service enters every app it lets through`() {
        // What the service does on a hop home and back: the launcher reaches
        // the front and is let through (Route.None), then the app returns.
        PauseLedger.enter(group)
        PauseLedger.onForeground(launcher, now)
        PauseLedger.enter(launcher)
        PauseLedger.onForeground(group, now + 5_000)
        assertThat(PauseLedger.isInside(group, now + 5_000)).isTrue()
    }

    @Test
    fun `stepping away without another limit in front starts the grace - the screen going off`() {
        PauseLedger.enter(group)
        PauseLedger.stepAway(now)
        assertThat(PauseLedger.isInside(group, now + grace - 1)).isTrue()
        assertThat(PauseLedger.isInside(group, now + grace)).isFalse()
    }

    @Test
    fun `stepping away again does not restart the grace`() {
        PauseLedger.enter(group)
        PauseLedger.stepAway(now)
        PauseLedger.stepAway(now + grace / 2)
        assertThat(PauseLedger.isInside(group, now + grace)).isFalse()
    }

    @Test
    fun `finishing a shown pause reports the first time only - a double tap is one open`() {
        PauseLedger.begin(group)
        assertThat(PauseLedger.complete(group, now)).isTrue()
        assertThat(PauseLedger.complete(group, now + 50)).isFalse()
    }

    @Test
    fun `finishing a pause that was never shown is not a first time`() {
        assertThat(PauseLedger.complete(group, now)).isFalse()
    }
}
