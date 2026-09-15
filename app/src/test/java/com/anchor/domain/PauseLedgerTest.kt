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
}
