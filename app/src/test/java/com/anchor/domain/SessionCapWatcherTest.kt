package com.anchor.domain

import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppUsageSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.ZoneOffset

class SessionCapWatcherTest {

    private val minute = 60_000L
    private val now = 1_757_000_000_000L

    // --- SessionCapMath ---

    @Test
    fun `a session just started gets the full cap`() {
        val summary = AppUsageSummary(currentSessionStartAtMillis = now, lastOpenStartAtMillis = now)
        assertThat(SessionCapMath.remainingMillis(summary, 10, now)).isEqualTo(10 * minute)
    }

    @Test
    fun `a session already running gets the remainder, by the clock`() {
        val summary = AppUsageSummary(
            currentSessionStartAtMillis = now - minute,
            lastOpenStartAtMillis = now - 4 * minute,
            currentOpenForegroundMillis = minute,
        )
        assertThat(SessionCapMath.remainingMillis(summary, 10, now)).isEqualTo(6 * minute)
    }

    @Test
    fun `nothing recorded means a fresh session`() {
        assertThat(SessionCapMath.remainingMillis(AppUsageSummary(), 10, now)).isEqualTo(10 * minute)
    }

    @Test
    fun `a return inside the session keeps counting from the open's start`() {
        val summary = AppUsageSummary(lastOpenStartAtMillis = now - 6 * minute, lastOpenEndAtMillis = now - 2 * minute)
        assertThat(SessionCapMath.remainingMillis(summary, 10, now)).isEqualTo(4 * minute)
    }

    @Test
    fun `a return after the session starts fresh`() {
        val summary = AppUsageSummary(lastOpenStartAtMillis = now - 15 * minute, lastOpenEndAtMillis = now - 14 * minute)
        assertThat(SessionCapMath.remainingMillis(summary, 10, now)).isEqualTo(10 * minute)
    }

    // --- SessionCapWatcher ---

    private val youtube = "com.google.android.youtube"
    private val instagram = "com.instagram.android"
    private val group = AppLimit(id = 1, name = "Social", packages = setOf(youtube, instagram), dailyOpens = 3, sessionMinutes = 10)

    private class Fake(private val limits: List<AppLimit>) : SessionCapQueries {
        var remaining = mutableMapOf<String, Long?>()
        var inFront = setOf<String>()
        override suspend fun limitFor(packageName: String) = limits.firstOrNull { packageName in it }
        override suspend fun sessionRemainingMillis(packageName: String) = remaining[packageName]
        override suspend fun isInForeground(packageName: String): Boolean {
            val members = limitFor(packageName)?.packages ?: setOf(packageName)
            return inFront.any { it in members }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runWatcher(
        fake: Fake,
        block: suspend kotlinx.coroutines.test.TestScope.(SessionCapWatcher, MutableList<String>) -> Unit,
    ) = runTest {
        val fired = mutableListOf<String>()
        val watcher = SessionCapWatcher(
            scope = this,
            queries = fake,
            anchorDate = AnchorDate(Clock.fixed(java.time.Instant.ofEpochMilli(now), ZoneOffset.UTC)),
            onCapReached = { pkg, _ -> fired += pkg },
        )
        block(watcher, fired)
        watcher.cancel()
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `fires at the cap while the app is in front`() {
        val fake = Fake(listOf(group)).apply { remaining[youtube] = 10 * minute; inFront = setOf(youtube) }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(10 * minute - 1); runCurrent()
            assertThat(fired).isEmpty()
            advanceTimeBy(2); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `events for unlimited packages do not stop the timer`() {
        val fake = Fake(listOf(group)).apply { remaining[youtube] = 10 * minute; inFront = setOf(youtube) }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(minute); runCurrent()
            watcher.onForegroundApp("com.anchor")
            watcher.onForegroundApp("com.android.systemui")
            watcher.onForegroundApp("com.google.android.inputmethod.latin")
            advanceTimeBy(9 * minute + 1); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `does not fire when the app is no longer in front`() {
        val fake = Fake(listOf(group)).apply { remaining[youtube] = 10 * minute; inFront = setOf(youtube) }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            fake.inFront = emptySet()
            advanceTimeBy(10 * minute + 1); runCurrent()
            assertThat(fired).isEmpty()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `re-arms for a newer open instead of firing for the old one`() {
        val fake = Fake(listOf(group)).apply { remaining[youtube] = 10 * minute; inFront = setOf(youtube) }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            // Locked early after two minutes and reopened at eight: the new open has 8 left at the old cap.
            fake.remaining[youtube] = 8 * minute
            advanceTimeBy(10 * minute + 1); runCurrent()
            assertThat(fired).isEmpty()
            // Eight minutes on, that open has run its course too.
            fake.remaining[youtube] = 10 * minute
            advanceTimeBy(8 * minute); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `members of a group share one timer`() {
        val fake = Fake(listOf(group)).apply {
            remaining[youtube] = 10 * minute; remaining[instagram] = 10 * minute; inFront = setOf(instagram)
        }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            advanceTimeBy(6 * minute); runCurrent()
            fake.remaining[instagram] = 4 * minute
            watcher.onForegroundApp(instagram)
            advanceTimeBy(4 * minute + 1); runCurrent()
            assertThat(fired).containsExactly(youtube)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancel stops it`() {
        val fake = Fake(listOf(group)).apply { remaining[youtube] = 10 * minute; inFront = setOf(youtube) }
        runWatcher(fake) { watcher, fired ->
            watcher.onForegroundApp(youtube)
            watcher.cancel()
            advanceTimeBy(11 * minute); runCurrent()
            assertThat(fired).isEmpty()
        }
    }
}
