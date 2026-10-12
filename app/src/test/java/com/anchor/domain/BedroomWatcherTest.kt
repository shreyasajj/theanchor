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
