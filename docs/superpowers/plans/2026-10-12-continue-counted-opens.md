# Continue-Counted Opens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Count an open of a limited app only when the pause screen lets the user in, and run the session clock and cooldown from that moment, so the flash before the pause stops costing an open.

**Architecture:** A new Room table `limit_open` gets one row per Continue (or finished breathe). `UsageCalculator` takes those open times as input and keeps using the usage log only for foreground minutes, "in front" checks and the close after an open. An in-memory "inside" marker in `PauseLedger` replaces the old "running open doesn't count against itself" discount, which depended on the log.

**Tech Stack:** Kotlin, Room (KSP), Hilt, Coroutines, JUnit4 + Robolectric + Truth.

**Spec:** `docs/superpowers/specs/2026-10-12-continue-counted-opens-design.md`

## Global Constraints

- Build with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` and `./gradlew ... --no-daemon` (Gradle 9.8.1 / AGP 9.4.1).
- Database version goes 4 → 5 through a real migration; nothing is dropped.
- Fail open: no rows, no usage data or a revoked permission must never block anything.
- The emergency allowlist and `ForegroundAppDecider` order are untouched.
- Prune age for `limit_open` and `early_lock`: 48 hours.
- Match the surrounding comment style: KDoc that explains *why*, no narration.

## Review Focus

1. **Recents back into the app after a session-cap screen**: must not be waved through as "inside". The cap ends the open. Pinned in Task 4 (`SessionEndScreens` leaves; `begin` leaves; ledger test).
2. **Launcher flashing during the Continue hand-off**: the app must still be let in, and still be entered. Service wiring in Task 4 Step 5 (the satisfied short-circuit enters after `onForeground`); the service has no unit tests, so this is checked on the emulator in Task 5 Step 3.
3. **Upgrading a v4 install**: must open without a Room validation crash. Pinned in Task 1 (`MigrationsTest` 3→5 plus `limit_open` usable).
4. **In-app window changes during the last open (no session length)**: must not block. Pinned in Task 4 (`LimitGateTest` inside test).
5. **Closing from the pause, then relaunching within the cooldown length**: no cooldown, no open charged. Pinned in Task 3 (`LimitGateTest` flash tests).

---

### Task 1: The `limit_open` table

**Files:**
- Create: `app/src/main/java/com/anchor/data/usage/LimitOpen.kt`
- Modify: `app/src/main/java/com/anchor/data/db/AnchorDatabase.kt`
- Modify: `app/src/main/java/com/anchor/data/db/Migrations.kt`
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt` (DAO provider)
- Modify: `app/src/main/java/com/anchor/data/usage/AppLimit.kt` (subject prefix constant)
- Test: `app/src/test/java/com/anchor/data/usage/LimitOpenDaoTest.kt`, `app/src/test/java/com/anchor/data/db/MigrationsTest.kt`

**Interfaces:**
- Produces: `LimitOpen(id, subject, packageName, atMillis)`, `LimitOpenDao.insert(LimitOpen)`, `LimitOpenDao.since(subject: String, fromMillis: Long): List<LimitOpen>`, `LimitOpenDao.prune(beforeMillis: Long)`, `AnchorDatabase.limitOpenDao()`, `AppLimit.SUBJECT_PREFIX = "limit:"`.

- [ ] **Step 1: Write the failing DAO test**

`app/src/test/java/com/anchor/data/usage/LimitOpenDaoTest.kt`:

```kotlin
package com.anchor.data.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LimitOpenDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: LimitOpenDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.limitOpenDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `since returns one subject's opens from a time, oldest first`() = runTest {
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 30))
        dao.insert(LimitOpen(subject = "limit:1", packageName = "b", atMillis = 10))
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 5))
        dao.insert(LimitOpen(subject = "limit:2", packageName = "a", atMillis = 20))

        val opens = dao.since("limit:1", 10)

        assertThat(opens.map { it.atMillis }).containsExactly(10L, 30L).inOrder()
        assertThat(opens.map { it.packageName }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `prune drops only rows older than the cutoff`() = runTest {
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 5))
        dao.insert(LimitOpen(subject = "limit:1", packageName = "a", atMillis = 50))

        dao.prune(beforeMillis = 10)

        assertThat(dao.since("limit:1", 0).map { it.atMillis }).containsExactly(50L)
    }
}
```

- [ ] **Step 2: Add the migration test**

Append to `MigrationsTest`:

```kotlin
    @Test
    fun `opens can be recorded after the upgrade`() = runTest {
        createVersion3()
        val db = open()
        try {
            db.limitOpenDao().insert(
                com.anchor.data.usage.LimitOpen(subject = "limit:1", packageName = "com.y", atMillis = 7L)
            )
            assertThat(db.limitOpenDao().since("limit:1", 0).map { it.atMillis }).containsExactly(7L)
        } finally {
            db.close()
        }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.usage.LimitOpenDaoTest' --tests 'com.anchor.data.db.MigrationsTest' --no-daemon`
Expected: compilation FAIL, `Unresolved reference: LimitOpen` / `limitOpenDao`.

- [ ] **Step 4: Create the entity and DAO**

`app/src/main/java/com/anchor/data/usage/LimitOpen.kt`:

```kotlin
package com.anchor.data.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One open of a limited app: the moment the pause let the user in, by
 * Continue or by finishing a breathe. The usage log cannot tell this apart
 * from the app flashing in front before the pause covers it, so opens are
 * recorded here instead of derived from it.
 *
 * Keyed by the limit's [AppLimit.subject], so a group's members share one
 * count; [packageName] is kept for the dashboard's per-app breakdown.
 */
@Entity(
    tableName = "limit_open",
    indices = [Index(value = ["subject", "atMillis"])],
)
data class LimitOpen(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val packageName: String,
    val atMillis: Long,
)

@Dao
interface LimitOpenDao {

    @Insert
    suspend fun insert(open: LimitOpen)

    @Query("SELECT * FROM limit_open WHERE subject = :subject AND atMillis >= :fromMillis ORDER BY atMillis ASC")
    suspend fun since(subject: String, fromMillis: Long): List<LimitOpen>

    @Query("DELETE FROM limit_open WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
```

- [ ] **Step 5: Register it, migrate, provide it**

`AnchorDatabase.kt`: add `LimitOpen::class` to `entities`, set `version = 5`, add the import `com.anchor.data.usage.LimitOpen` / `LimitOpenDao` and `abstract fun limitOpenDao(): LimitOpenDao`.

`Migrations.kt`, add before `ALL`:

```kotlin
    /**
     * Opens are recorded when the pause lets the user in, not derived from
     * the usage log. A new table; nothing else is touched.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `limit_open` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`subject` TEXT NOT NULL, `packageName` TEXT NOT NULL, `atMillis` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_limit_open_subject_atMillis` ON `limit_open` (`subject`, `atMillis`)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5)
```

`AppModule.kt`, beside `provideEarlyLockDao`:

```kotlin
    @Provides fun provideLimitOpenDao(db: AnchorDatabase): LimitOpenDao = db.limitOpenDao()
```

`AppLimit.kt`: change the `subject` getter to `"$SUBJECT_PREFIX$id"` and add at the end of the class body:

```kotlin
    companion object {
        /** What every limit's [subject] starts with; a bare package name never does. */
        const val SUBJECT_PREFIX = "limit:"
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.usage.LimitOpenDaoTest' --tests 'com.anchor.data.db.MigrationsTest' --no-daemon`
Expected: PASS. A Room "Migration didn't properly handle" error means the migration SQL differs from the entity. Compare with `grep -rn limit_open app/build/generated/ksp/debug` and fix the SQL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anchor/data/usage/LimitOpen.kt app/src/main/java/com/anchor/data/db app/src/main/java/com/anchor/di/AppModule.kt app/src/main/java/com/anchor/data/usage/AppLimit.kt app/src/test/java/com/anchor/data
git commit -m "feat: a table for opens, one row per time the pause lets you in"
```

---

### Task 2: Record an open when the pause lets you in

**Files:**
- Create: `app/src/main/java/com/anchor/di/ApplicationScope.kt`
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt`
- Modify: `app/src/main/java/com/anchor/domain/LimitGate.kt` (constructor + `recordOpen`)
- Modify: `app/src/main/java/com/anchor/ui/lock/PauseActivity.kt`, `app/src/main/java/com/anchor/ui/lock/MeditationActivity.kt`
- Test: `app/src/test/java/com/anchor/domain/LimitGateTest.kt`

**Interfaces:**
- Consumes: `LimitOpenDao`, `LimitOpen`, `AppLimit.SUBJECT_PREFIX` (Task 1).
- Produces: `LimitGate(limits, earlyLockDao, limitOpenDao, usageStatsSource, killSwitch, anchorDate, settingsProvider)`, `suspend fun LimitGate.recordOpen(packageName: String, subject: String)`, `LimitGate.PRUNE_AFTER_MILLIS = 48h`, `@ApplicationScope CoroutineScope`.

- [ ] **Step 1: Write the failing test**

In `LimitGateTest`, add `limitOpenDao = db.limitOpenDao(),` to the `LimitGate(...)` call in `gate()`, add imports `com.anchor.data.usage.LimitOpen`, and add:

```kotlin
    // --- Recording opens ---

    @Test
    fun `recordOpen writes for a limit and ignores an evening-only pause`() = runTest {
        val subject = limit(AppLimit(app, dailyOpens = 3))
        val gate = gate()

        gate.recordOpen(app, subject)
        gate.recordOpen(app, app)

        assertThat(db.limitOpenDao().since(subject, 0).map { it.atMillis }).containsExactly(clock.millis())
        assertThat(db.limitOpenDao().since(app, 0)).isEmpty()
    }

    @Test
    fun `recordOpen prunes opens and early locks older than two days`() = runTest {
        val subject = limit(AppLimit(app, dailyOpens = 3))
        val old = clock.millis() - LimitGate.PRUNE_AFTER_MILLIS - 1
        db.limitOpenDao().insert(LimitOpen(subject = subject, packageName = app, atMillis = old))
        db.earlyLockDao().insert(EarlyLock(subject = subject, atMillis = old))

        gate().recordOpen(app, subject)

        assertThat(db.limitOpenDao().since(subject, 0).map { it.atMillis }).containsExactly(clock.millis())
        assertThat(db.earlyLockDao().since(subject, 0)).isEmpty()
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.LimitGateTest' --no-daemon`
Expected: compilation FAIL, no parameter `limitOpenDao` / `recordOpen`.

- [ ] **Step 3: Implement in `LimitGate`**

Constructor: add `private val limitOpenDao: LimitOpenDao,` after `earlyLockDao`. Import `com.anchor.data.usage.LimitOpen`, `com.anchor.data.usage.LimitOpenDao`. Add after `lockEarly`:

```kotlin
    /**
     * The pause let the user into [packageName]: that is the open. Nothing is
     * recorded for a subject that is not a limit (an evening-only pause).
     * Old opens and early locks are pruned here, the one place both grow.
     */
    suspend fun recordOpen(packageName: String, subject: String) {
        if (!subject.startsWith(AppLimit.SUBJECT_PREFIX)) return
        val now = anchorDate.nowMillis()
        limitOpenDao.insert(LimitOpen(subject = subject, packageName = packageName, atMillis = now))
        val cutoff = now - PRUNE_AFTER_MILLIS
        limitOpenDao.prune(cutoff)
        earlyLockDao.prune(cutoff)
    }

    companion object {
        /** Comfortably past the 6h lookback any decision reads. */
        const val PRUNE_AFTER_MILLIS = 48L * 60 * 60 * 1000
    }
```

- [ ] **Step 4: Add the process-lifetime scope**

`app/src/main/java/com/anchor/di/ApplicationScope.kt`:

```kotlin
package com.anchor.di

import javax.inject.Qualifier

/**
 * A scope that lives as long as the process. For writes that must outlive
 * the screen that starts them: the pause records an open and finishes in
 * the same breath, which would cancel anything on its lifecycleScope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
```

`AppModule.kt`, in the Settings or Usage section:

```kotlin
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```

- [ ] **Step 5: Call it from both screens**

`PauseActivity`: add

```kotlin
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
```

(imports `com.anchor.di.ApplicationScope`, `kotlinx.coroutines.CoroutineScope`) and in `continueToApp()` after `PauseLedger.complete(...)`:

```kotlin
        appScope.launch { limitGate.recordOpen(blockedPackage, subject) }
```

`MeditationActivity`: add `@Inject lateinit var limitGate: LimitGate` and the same `appScope` field (imports `com.anchor.domain.LimitGate`, `com.anchor.di.ApplicationScope`, `kotlinx.coroutines.CoroutineScope`). In `openAnyway()`, inside `subject?.let { ... }` after `PauseLedger.complete(...)`:

```kotlin
            val pkg = blockedPackage
            if (pkg != null) appScope.launch { limitGate.recordOpen(pkg, it) }
```

- [ ] **Step 6: Run tests and compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.LimitGateTest' --no-daemon`
Expected: PASS (all existing tests too: nothing reads the rows yet).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anchor/di app/src/main/java/com/anchor/domain/LimitGate.kt app/src/main/java/com/anchor/ui/lock/PauseActivity.kt app/src/main/java/com/anchor/ui/lock/MeditationActivity.kt app/src/test/java/com/anchor/domain/LimitGateTest.kt
git commit -m "feat: record an open when Continue or a finished breathe lets you in"
```

---

### Task 3: Count opens from the table, not the usage log

This task changes the shape of `AppUsageSummary`, so the calculator, the gate, the status and every test that builds a summary change together. It is one commit.

**Files:**
- Modify: `app/src/main/java/com/anchor/data/usage/UsageEvent.kt` (`AppUsageSummary`)
- Modify: `app/src/main/java/com/anchor/data/usage/UsageCalculator.kt`
- Modify: `app/src/main/java/com/anchor/domain/LimitGate.kt`
- Modify: `app/src/main/java/com/anchor/domain/LimitStatus.kt`
- Test: `UsageCalculatorTest.kt` (rewrite), `LimitGateTest.kt`, `LimitStatusTest.kt`, `SessionCapWatcherTest.kt`

**Interfaces:**
- Consumes: `LimitOpenDao.since` (Task 1), `LimitGate` constructor (Task 2).
- Produces: `UsageCalculator.summarize(events, packageNames: Set<String>, windowStartMillis, nowMillis, opensMillis: List<Long>)` and the same with `packageName: String`; `AppUsageSummary(foregroundMillis, opens, openUnits, lastForegroundEndAtMillis, currentSessionStartAtMillis, lastOpenStartAtMillis)`.

- [ ] **Step 1: Rewrite `UsageCalculatorTest`**

Replace the whole file with:

```kotlin
package com.anchor.data.usage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageCalculatorTest {

    private val app = "com.google.android.youtube"
    private val instagram = "com.instagram.android"
    private val minute = 60_000L

    /** Day start at t=0 for readability; "now" supplied per test. */
    private val windowStart = 0L

    private fun fg(atMinutes: Long, pkg: String = app) = UsageEvent(pkg, UsageEvent.Type.FOREGROUND, atMinutes * minute)

    private fun bg(atMinutes: Long, pkg: String = app) = UsageEvent(pkg, UsageEvent.Type.BACKGROUND, atMinutes * minute)

    private fun summarize(events: List<UsageEvent>, nowMinutes: Long, opensAtMinutes: List<Long> = emptyList()) =
        UsageCalculator.summarize(events, app, windowStart, nowMinutes * minute, opensAtMinutes.map { it * minute })

    @Test
    fun `nothing at all means no usage`() {
        val s = summarize(emptyList(), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(0)
        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastForegroundEndAtMillis).isNull()
        assertThat(s.currentSessionStartAtMillis).isNull()
        assertThat(s.lastOpenStartAtMillis).isNull()
    }

    // --- The bug this replaces ---

    @Test
    fun `THE BUG - a flash closed from the pause is no open, no close, no session`() {
        // Launched at 10, covered by the pause 300ms later, closed from the pause.
        val flash = listOf(fg(10), UsageEvent(app, UsageEvent.Type.BACKGROUND, 10 * minute + 300))
        val s = summarize(flash, nowMinutes = 11)

        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastForegroundEndAtMillis).isNull()
        assertThat(s.lastOpenStartAtMillis).isNull()
        assertThat(s.foregroundMillis).isEqualTo(300)
    }

    @Test
    fun `the flash before Continue does not count as the close`() {
        val continuedAt = 10 * minute + 5_000
        val events = listOf(
            fg(10), UsageEvent(app, UsageEvent.Type.BACKGROUND, 10 * minute + 300),
            UsageEvent(app, UsageEvent.Type.FOREGROUND, continuedAt + 200), bg(20),
        )
        val s = UsageCalculator.summarize(events, app, windowStart, 30 * minute, listOf(continuedAt))

        assertThat(s.opens).isEqualTo(1)
        assertThat(s.lastOpenStartAtMillis).isEqualTo(continuedAt)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(20 * minute)
    }

    // --- Opens ---

    @Test
    fun `opens are the recorded opens from the window start`() {
        val s = summarize(emptyList(), nowMinutes = 60, opensAtMinutes = listOf(-30, 10, 20))

        assertThat(s.opens).isEqualTo(2)
        assertThat(s.openUnits).isEqualTo(2.0)
        assertThat(s.lastOpenStartAtMillis).isEqualTo(20 * minute)
    }

    @Test
    fun `an open before the window is not counted but still anchors the close`() {
        val s = summarize(listOf(fg(-40), bg(-30)), nowMinutes = 60, opensAtMinutes = listOf(-40))

        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastOpenStartAtMillis).isEqualTo(-40 * minute)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(-30 * minute)
    }

    @Test
    fun `sessions without opens are minutes, never opens`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30), bg(35)), nowMinutes = 60)

        assertThat(s.opens).isEqualTo(0)
        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
    }

    // --- The close after the open ---

    @Test
    fun `the close is the last session end after the latest open`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30), bg(35)), nowMinutes = 60, opensAtMinutes = listOf(10))
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(35 * minute)
    }

    @Test
    fun `a session that ended before the latest open is not its close`() {
        val s = summarize(listOf(fg(10), bg(20)), nowMinutes = 60, opensAtMinutes = listOf(30))
        assertThat(s.lastForegroundEndAtMillis).isNull()
    }

    @Test
    fun `a running session does not overwrite the previous close`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30)), nowMinutes = 40, opensAtMinutes = listOf(10))

        assertThat(s.lastForegroundEndAtMillis).isEqualTo(20 * minute)
        assertThat(s.currentSessionStartAtMillis).isEqualTo(30 * minute)
    }

    // --- Minutes and in-front, from the log as before ---

    @Test
    fun `one closed session counts its duration`() {
        val s = summarize(listOf(fg(10), bg(25)), nowMinutes = 60)
        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
        assertThat(s.currentSessionStartAtMillis).isNull()
    }

    @Test
    fun `a running session accrues time up to now`() {
        val s = summarize(listOf(fg(10)), nowMinutes = 40)
        assertThat(s.foregroundMillis).isEqualTo(30 * minute)
        assertThat(s.currentSessionStartAtMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a session spanning the window start is clamped to it`() {
        assertThat(summarize(listOf(fg(-20), bg(10)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a session entirely before the window contributes no time`() {
        assertThat(summarize(listOf(fg(-40), bg(-30)), nowMinutes = 60).foregroundMillis).isEqualTo(0)
    }

    @Test
    fun `a duplicate FOREGROUND event does not start a second session`() {
        assertThat(summarize(listOf(fg(10), fg(12), bg(20)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a BACKGROUND with no matching FOREGROUND is ignored`() {
        assertThat(summarize(listOf(bg(5), fg(10), bg(20)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `events for other packages are ignored entirely`() {
        val s = summarize(listOf(fg(10), fg(11, instagram), bg(12, instagram), bg(20)), nowMinutes = 60)
        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `unsorted input is handled`() {
        assertThat(summarize(listOf(bg(20), fg(10)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    // --- Groups ---

    @Test
    fun `a group's apps are summed as one`() {
        val s = UsageCalculator.summarize(
            events = listOf(fg(10), bg(20), fg(40, instagram), bg(45, instagram)),
            packageNames = setOf(app, instagram), windowStartMillis = windowStart, nowMillis = 60 * minute,
            opensMillis = listOf(10 * minute),
        )

        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
        assertThat(s.opens).isEqualTo(1)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(45 * minute)
    }

    @Test
    fun `a hand-over logged out of order does not double count`() {
        // Instagram's FOREGROUND lands a second before YouTube's BACKGROUND.
        val s = UsageCalculator.summarize(
            events = listOf(
                fg(10),
                UsageEvent(instagram, UsageEvent.Type.FOREGROUND, 13 * minute - 1_000),
                bg(13),
                bg(16, instagram),
            ),
            packageNames = setOf(app, instagram), windowStartMillis = windowStart, nowMillis = 60 * minute,
            opensMillis = emptyList(),
        )

        assertThat(s.foregroundMillis).isEqualTo(6 * minute)
    }
}
```

- [ ] **Step 2: Update `LimitStatusTest` and `SessionCapWatcherTest` to the new shape**

- In `LimitStatusTest`, delete every `lastOpenUnits = 1.0,` argument and the `lastOpenEndAtMillis = now - minute,` argument in ``a session left is still a session until it elapses``. The expected values stay the same.
- In `SessionCapWatcherTest`, delete `currentOpenForegroundMillis = minute,` and both `lastOpenEndAtMillis = …` arguments. The expected values stay the same.

- [ ] **Step 3: Rewrite the open-dependent `LimitGateTest` cases**

Add helper below `limit(...)`:

```kotlin
    /** The pause let the user into [pkg], [minutesAfterDayStart] after the day start. */
    private suspend fun opened(subject: String, minutesAfterDayStart: Long, pkg: String = app) =
        db.limitOpenDao().insert(
            LimitOpen(subject = subject, packageName = pkg, atMillis = dayStartMillis + minutesAfterDayStart * minute)
        )
```

Then apply these changes. Each one turns `db.appLimitDao().upsert(X)` into `val s = limit(X)` where a subject is needed, and adds the `opened(...)` rows shown. Assertions are unchanged unless stated.

| Test | Change |
|---|---|
| `allowed while under the open count` | `opened(s, 10); opened(s, 60)` |
| `blocked past the open count` | `opened(s, 10); opened(s, 60); opened(s, 120)` |
| `at exactly the open count the next launch is blocked` | `opened(s, 10); opened(s, 60)` |
| `the running open never counts against itself` | **delete** (replaced in Task 4) |
| `in time mode the open budget is ignored` | `opened(s, 10); opened(s, 60)` |
| `blocked during the cooldown after closing` | `opened(s, 600)` |
| `the cooldown block reports when the app becomes available` | `opened(s, 600)` |
| `allowed once the cooldown has elapsed` | `opened(s, 560)` |
| `a fresh launch after an old open pays the cooldown and open` | `opened(s, 640)` |
| `once the pause is served, the return no longer costs an open` | limit `dailyOpens = 2`; `opened(subject, 100); opened(subject, 655)` |
| `a launch blip is not an established open` | rename `a launch flash is not an open`; body unchanged (no rows) |
| `an open must be running a while before a return rejoins it` | **replace** with the test below |
| `a pause owed from a previous process still blocks the rejoin` | limit `dailyOpens = 2`; `opened(subject, 100); opened(subject, 655)` |
| `returning inside the session window skips the cooldown` | `opened(s, 655)` |
| `returning inside the session window is not charged an open` | limit `dailyOpens = 2`; `opened(s, 100); opened(s, 655)` |
| `a rejoin is not paused again - the pause was served on the way in` | `opened(s, 655)` |
| `once the session has elapsed the next open is paused again` | `opened(s, 650)` |
| `the user's example - two minutes used, back after the session, budget intact` | `opened(s, 650)` |
| `time away does not count against the minutes budget` | `opened(s, 652)` |
| `a rejoin with no pause configured just opens` | `opened(s, 655)` |
| `returning inside the session window still respects the time budget` | `opened(s, 100); opened(s, 655)` |
| `after the session window the cooldown applies again` | `opened(s, 640)` |
| `an early lock ends the session window so a return is a new open` | `opened(subject, 655)` |
| `an early lock starts the cooldown` | `opened(subject, 600)` |
| `the return after an early lock is a whole new open` | `opened(subject, 100); opened(subject, 110)` |
| `a spent daily budget is reported ahead of a cooldown` | first limit via `val s = limit(...)`; before the second `gate(...)` add `opened(s, 600)` |
| `a cooldown with budget left is still a cooldown` | `opened(s, 600)` |
| `after the last open's session, the cap screen says the day is spent` | `opened(s, 100); opened(s, 650)` |
| `after a session with opens left, the cap screen promises the cooldown` | `opened(s, 650)` |
| `continuing after the session is a new open` | `opened(s, 650); opened(s, 660)` |
| `the budget counts only what happened inside the hours` | `opened(s, 300); opened(s, 360); opened(s, 630)`; before `spent`, add `opened(s, 650)` |
| `a spent windowed budget comes back at the end of the window` | `opened(s, 630)` |
| `one app can have different budgets at different hours` | morning limit `val s = limit(...)`; `opened(s, 300); opened(s, 360); opened(s, 400)` |
| `summaryFor exposes usage for the dashboard` | first line `val s = limit(AppLimit(app, dailyOpens = 3)); opened(s, 60)` |
| `apps in one group share the open budget` | `opened(s, 10); opened(s, 60, instagram)` |
| `apps in one group share the session` | `opened(s, 648)` before the first assertion; `opened(s, 655)` before the second |
| `the user's example - two opens of ten minutes across two apps` | `opened(s, 100); opened(s, 300, instagram)` |
| `a bypass also silences the session cap` | `opened(subject, 655)` |
| `summaries reads the event log once for every limit` | `val a = limit(AppLimit(app, dailyOpens = 3)); val b = limit(AppLimit(instagram, dailyOpens = 3)); opened(a, 10); opened(b, 20, instagram)` |
| `dashboard usage breaks a group down per app and reads the log once` | `opened(s, 10)`; add `assertThat(usage.perApp.getValue(app).opens).isEqualTo(1)` and `assertThat(usage.perApp.getValue(instagram).opens).isEqualTo(0)` |
| `dashboard usage reports a running session` | `opened(s, 650)` |

Replacement for the deleted "running a while" test, plus the new flash tests:

```kotlin
    @Test
    fun `a return seconds after Continue rejoins - there is no minimum age`() = runTest {
        val s = limit(AppLimit(app, sessionMinutes = 10, dailyOpens = 2))
        val justNow = dayStartMillis + 660 * minute
        opened(s, 100)
        db.limitOpenDao().insert(LimitOpen(subject = s, packageName = app, atMillis = justNow - 10_000))
        val events = listOf(
            fg(100), bg(110),
            UsageEvent(app, UsageEvent.Type.FOREGROUND, justNow - 9_800),
            UsageEvent(app, UsageEvent.Type.BACKGROUND, justNow - 500),
        )
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)
    }

    @Test
    fun `THE BUG - closing from the pause costs no open`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, dailyOpens = 1))
        val flashAt = dayStartMillis + 650 * minute
        val flash = listOf(
            UsageEvent(app, UsageEvent.Type.FOREGROUND, flashAt),
            UsageEvent(app, UsageEvent.Type.BACKGROUND, flashAt + 300),
        )
        val gate = gate(events = flash)
        assertThat(gate.summaryFor(app).opens).isEqualTo(0)
        assertThat(gate.decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `THE BUG - closing from the pause starts no cooldown`() = runTest {
        db.appLimitDao().upsert(AppLimit(app, cooldownMinutes = 30))
        val flashAt = dayStartMillis + 655 * minute
        val flash = listOf(
            UsageEvent(app, UsageEvent.Type.FOREGROUND, flashAt),
            UsageEvent(app, UsageEvent.Type.BACKGROUND, flashAt + 300),
        )
        assertThat(gate(events = flash).decide(app)).isEqualTo(LimitDecision.Pause(0))
    }

    @Test
    fun `a recorded open counts at once`() = runTest {
        val s = limit(AppLimit(app, dailyOpens = 1))
        gate().recordOpen(app, s)
        val decision = gate().decide(app) as LimitDecision.Blocked
        assertThat(decision.reason).isEqualTo(LimitReason.DAILY_OPENS)
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.usage.UsageCalculatorTest' --tests 'com.anchor.domain.*' --no-daemon`
Expected: compilation FAIL (`opensMillis` parameter missing, removed fields still referenced in main).

- [ ] **Step 5: Shrink `AppUsageSummary`**

In `UsageEvent.kt` replace the KDoc and class with:

```kotlin
/**
 * What a limit's apps did within a usage-day window. Opens come from the
 * recorded opens; time and "in front" come from the usage log.
 *
 * @param foregroundMillis time in the foreground within the window
 * @param opens recorded opens within the window
 * @param openUnits opens as charged against the budget; every open costs one
 * @param lastForegroundEndAtMillis end of the last completed session after
 *   the latest open, considering opens before the window too: a cooldown is
 *   a rolling gap and must survive the daily reset. Null when nothing was
 *   left since the latest open, so a flash closed from the pause starts no
 *   cooldown.
 * @param currentSessionStartAtMillis non-null when an app is foreground now
 * @param lastOpenStartAtMillis when the latest open was recorded; the
 *   session clock runs from here
 */
data class AppUsageSummary(
    val foregroundMillis: Long = 0,
    val opens: Int = 0,
    val openUnits: Double = 0.0,
    val lastForegroundEndAtMillis: Long? = null,
    val currentSessionStartAtMillis: Long? = null,
    val lastOpenStartAtMillis: Long? = null,
)
```

- [ ] **Step 6: Rewrite `UsageCalculator.summarize`**

Replace the class KDoc, `OPEN_COALESCE_WINDOW_MILLIS`, both `summarize` overloads (keep `rejoins`, `Session`, `buildSessions`, `buildSessionsFor` as they are) with:

```kotlin
/**
 * Derives usage from the raw event log and the recorded opens. Pure by
 * design. Opens are recorded, not derived: the log cannot tell an open from
 * the app flashing in front before the pause covers it. Time and "in front"
 * still come from the log, where the flash adds well under a second.
 */
object UsageCalculator {

    private data class Session(val start: Long, val end: Long?)

    /** Single-app convenience. */
    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
        opensMillis: List<Long>,
    ): AppUsageSummary = summarize(events, setOf(packageName), windowStartMillis, nowMillis, opensMillis)

    // rejoins(...) stays here, unchanged.

    /**
     * @param packageNames the apps sharing one limit; their sessions are
     *   merged and counted as one.
     * @param opensMillis when the pause let the user in, from before the
     *   window too, so a session or cooldown straddling the reset is seen.
     */
    fun summarize(
        events: List<UsageEvent>,
        packageNames: Set<String>,
        windowStartMillis: Long,
        nowMillis: Long,
        opensMillis: List<Long>,
    ): AppUsageSummary {
        val sessions = buildSessions(events, packageNames, nowMillis)

        // Time: each session clipped to the window and to now.
        val foregroundMillis = sessions.sumOf { session ->
            val end = minOf(session.end ?: nowMillis, nowMillis)
            (end - maxOf(session.start, windowStartMillis)).coerceAtLeast(0)
        }

        val opens = opensMillis.count { it >= windowStartMillis }
        val lastOpen = opensMillis.maxOrNull()

        // Only a departure after the open is a close; the flash before it is not.
        val lastClose = lastOpen?.let { open ->
            sessions.lastOrNull { it.end != null && it.end > open }?.end
        }

        return AppUsageSummary(
            foregroundMillis = foregroundMillis,
            opens = opens,
            openUnits = opens.toDouble(),
            lastForegroundEndAtMillis = lastClose,
            currentSessionStartAtMillis = sessions.lastOrNull()?.takeIf { it.end == null }?.start,
            lastOpenStartAtMillis = lastOpen,
        )
    }
```

- [ ] **Step 7: Feed the rows through `LimitGate`**

In `LimitGate.kt`:

1. Delete `MIN_ESTABLISHED_OPEN_MILLIS` and its KDoc. In `rejoinsOpen`, delete the line `if (now - openStart < MIN_ESTABLISHED_OPEN_MILLIS) return false` and replace the second paragraph of the KDoc's first sentence ("it has run long enough … already be logged),") with nothing, so it reads "…the open began less than a session ago, it was not ended early on purpose, and its pause was actually served."
2. `spentBudget`: `if (summary.openUnits >= maxOpens) return …`. Update its KDoc to: "A spent open count or time budget, whichever applies. Opens are only the recorded ones, so the launch being decided has not counted yet." Delete `unitsBeforeThisOpen`.
3. `afterSessionCap`: `spentBudget(limit, summary, resetsAt(limit, settings))?.let { return it }`, and delete the "Counting the open just ended" comment.
4. Both private `summarize` functions:

```kotlin
    private suspend fun summarize(limit: AppLimit, settings: AnchorSettings, nowMillis: Long): Usage {
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        val from = UsageEventMapping.queryFrom(dayStart)
        return summarize(
            limit, nowMillis, dayStart,
            usageStatsSource.events(from, nowMillis),
            earlyLockDao.since(limit.subject, from),
            limitOpenDao.since(limit.subject, from),
        )
    }

    private fun summarize(
        limit: AppLimit,
        nowMillis: Long,
        dayStart: Long,
        events: List<UsageEvent>,
        earlyLocks: List<Long>,
        opens: List<LimitOpen>,
    ): Usage = Usage(
        summary = UsageCalculator.summarize(
            events = events,
            packageNames = limit.packages,
            windowStartMillis = budgetStartMillis(limit, dayStart),
            nowMillis = nowMillis,
            opensMillis = opens.map { it.atMillis },
        ),
        lastEarlyLock = earlyLocks.lastOrNull(),
    )
```

5. `summaries()`: in the `associateWith`, use `summarize(limit, now, dayStart, events, earlyLockDao.since(limit.subject, from), limitOpenDao.since(limit.subject, from)).summary`.
6. `dashboardUsage()`: read `val opens = limitOpenDao.since(limit.subject, from)` beside `earlyLocks`, pass it to `summarize(limit, now, dayStart, events, earlyLocks, opens)`, and build `perApp` with `UsageCalculator.summarize(events, pkg, budgetStart, now, opens.filter { it.packageName == pkg }.map { it.atMillis })`.

- [ ] **Step 8: `LimitStatus` without the discount**

Replace the block from `val sessionEnd = …` through `if (inFront) return InSession(endsAtMillis = null, inForeground = true)` with:

```kotlin
            val sessionEnd = sessionEndsAt(limit, summary, lastEarlyLock, pauseOwed, nowMillis)
            if (sessionEnd != null) {
                return if (timeSpent) Spent(resetsAtMillis) else InSession(sessionEnd, inFront)
            }
            if (timeSpent) return Spent(resetsAtMillis)

            // In front with time left is in use, even on the last open: the
            // dashboard reports, it does not block.
            if (inFront) return InSession(endsAtMillis = null, inForeground = true)

            val opensSpent = limit.effectiveDailyOpens?.let { summary.openUnits >= it } ?: false
            if (opensSpent) return Spent(resetsAtMillis)
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.usage.UsageCalculatorTest' --tests 'com.anchor.domain.*' --no-daemon`
Expected: PASS. Then `./gradlew :app:compileDebugKotlin --no-daemon` and fix any other caller of the removed fields; grep `lastOpenUnits|lastOpenEndAtMillis|currentOpenForegroundMillis|OPEN_COALESCE|MIN_ESTABLISHED` must be empty.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/anchor/data/usage app/src/main/java/com/anchor/domain app/src/test/java/com/anchor
git commit -m "feat: count opens from the pause, not the usage log

The flash of the app before the pause covers it was logged as a session,
so it cost an open, started the session clock and, when closed from the
pause, a cooldown. Opens, the session start and the close after an open
now come from the recorded opens."
```

---

### Task 4: Inside an open

**Files:**
- Modify: `app/src/main/java/com/anchor/domain/PauseLedger.kt`
- Modify: `app/src/main/java/com/anchor/domain/LimitGate.kt` (`decide` step 0)
- Modify: `app/src/main/java/com/anchor/service/AnchorAccessibilityService.kt`
- Modify: `app/src/main/java/com/anchor/ui/lock/SessionEndScreens.kt`
- Test: `app/src/test/java/com/anchor/domain/PauseLedgerTest.kt`, `app/src/test/java/com/anchor/domain/LimitGateTest.kt`

**Interfaces:**
- Consumes: `LimitGate.decide`, `PauseLedger` (existing).
- Produces: `PauseLedger.enter(subject)`, `PauseLedger.isInside(subject): Boolean`, `PauseLedger.onForeground(subject)`, `PauseLedger.leave(subject)`.

- [ ] **Step 1: Write the failing ledger tests**

Append to `PauseLedgerTest`:

```kotlin
    // --- Inside an open ---

    private val group = "limit:1"

    @Test
    fun `entering marks the subject inside`() {
        PauseLedger.enter(group)
        assertThat(PauseLedger.isInside(group)).isTrue()
        assertThat(PauseLedger.isInside("limit:2")).isFalse()
    }

    @Test
    fun `another subject reaching the front leaves`() {
        PauseLedger.enter(group)
        PauseLedger.onForeground("com.android.launcher")
        assertThat(PauseLedger.isInside(group)).isFalse()
    }

    @Test
    fun `the same subject reaching the front stays inside - a group switch`() {
        PauseLedger.enter(group)
        PauseLedger.onForeground(group)
        assertThat(PauseLedger.isInside(group)).isTrue()
    }

    @Test
    fun `an early lock leaves`() {
        PauseLedger.enter(group)
        PauseLedger.ignoreAfterEarlyLock(group, now)
        assertThat(PauseLedger.isInside(group)).isFalse()
    }

    @Test
    fun `a pause going up leaves - the open it guarded is over`() {
        PauseLedger.enter(group)
        PauseLedger.begin(group)
        assertThat(PauseLedger.isInside(group)).isFalse()
    }

    @Test
    fun `leave only clears its own subject`() {
        PauseLedger.enter(group)
        PauseLedger.leave("limit:2")
        assertThat(PauseLedger.isInside(group)).isTrue()
        PauseLedger.leave(group)
        assertThat(PauseLedger.isInside(group)).isFalse()
    }
```

And to `LimitGateTest` (replacing the deleted running-open test):

```kotlin
    @Test
    fun `inside the last open, an in-app re-check lets you stay`() = runTest {
        val s = limit(AppLimit(app, dailyOpens = 2))
        opened(s, 10)
        opened(s, 600)
        val events = listOf(fg(10), bg(15), fg(600))
        PauseLedger.enter(s)
        assertThat(gate(events = events).decide(app)).isEqualTo(LimitDecision.Allow)

        // Left for the launcher: the next launch is a new open, and there are none left.
        PauseLedger.onForeground("com.android.launcher")
        val decision = gate(events = events + bg(605)).decide(app) as LimitDecision.Blocked
        assertThat(decision.reason).isEqualTo(LimitReason.DAILY_OPENS)
    }

    @Test
    fun `inside still respects a spent minute budget`() = runTest {
        val s = limit(AppLimit(app, limitMode = LimitMode.TIME, dailyMinutes = 30))
        opened(s, 600)
        PauseLedger.enter(s)
        val decision = gate(events = listOf(fg(600))).decide(app) as LimitDecision.Blocked
        assertThat(decision.reason).isEqualTo(LimitReason.DAILY_TIME)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.PauseLedgerTest' --tests 'com.anchor.domain.LimitGateTest' --no-daemon`
Expected: compilation FAIL, unresolved `enter` / `isInside` / `onForeground` / `leave`.

- [ ] **Step 3: Implement in `PauseLedger`**

Add the import `java.util.concurrent.atomic.AtomicReference` and, after `abandoned`:

```kotlin
    /**
     * The limit the user is inside right now: let in, and not left since.
     * The gate is asked again on in-app window changes, and the usage log
     * cannot tell those from a fresh launch's flash; this can. Only one
     * subject can be in front at a time, so one slot is enough.
     */
    private val inside = AtomicReference<String?>(null)

    /** A limited app reached the front and was let through. */
    fun enter(subject: String) {
        if (subject.isNotEmpty()) inside.set(subject)
    }

    fun isInside(subject: String): Boolean = subject.isNotEmpty() && inside.get() == subject

    /**
     * [subject] reached the front. Anything else is a departure; the same
     * subject (a group-mate) is not.
     */
    fun onForeground(subject: String) {
        inside.getAndUpdate { if (it == subject) it else null }
    }

    /** The open in [subject] is over: a session cap, a pause, an early lock. */
    fun leave(subject: String) {
        inside.compareAndSet(subject, null)
    }
```

In `ignoreAfterEarlyLock`, after `satisfiedUntil.remove(subject)`, add `leave(subject)`. In `begin`, after `satisfiedUntil.remove(subject)`, add `leave(subject)`. In `reset()`, add `inside.set(null)`.

- [ ] **Step 4: Treat inside like a rejoin in `LimitGate.decide`**

Change step 0's condition and comment:

```kotlin
        // 0. Inside the open, or coming back inside its session, is the same
        //    open: it costs no second open, serves no cooldown and, since the
        //    pause was served on the way in, is not paused again. Only a
        //    pause that was walked away from is still owed.
        if (PauseLedger.isInside(limit.subject) ||
            rejoinsOpen(limit, summary, now, pauseOwed, usage.lastEarlyLock)
        ) {
```

- [ ] **Step 5: Wire the service and the session end**

`AnchorAccessibilityService`, `EvaluateEvening` branch, right after `val subject = PauseSubject.of(target, limit)`:

```kotlin
                    // Anything else in front ends the open the user was inside.
                    PauseLedger.onForeground(subject)
```

Replace `if (PauseLedger.isSatisfied(subject, now)) return@launch` with:

```kotlin
                    if (PauseLedger.isSatisfied(subject, now)) {
                        PauseLedger.enter(subject)
                        return@launch
                    }
```

and change `is Route.None -> Unit` to:

```kotlin
                        is Route.None -> PauseLedger.enter(subject)
```

`SessionEndScreens.intentFor`, after computing `subject`:

```kotlin
        // The session is over; returning through recents must not count as inside.
        PauseLedger.leave(subject)
```

(import `com.anchor.domain.PauseLedger`).

- [ ] **Step 6: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.*' --tests 'com.anchor.service.*' --no-daemon`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anchor/domain/PauseLedger.kt app/src/main/java/com/anchor/domain/LimitGate.kt app/src/main/java/com/anchor/service/AnchorAccessibilityService.kt app/src/main/java/com/anchor/ui/lock/SessionEndScreens.kt app/src/test/java/com/anchor/domain
git commit -m "feat: an in-app re-check inside an open is not a new launch"
```

---

### Task 5: Verify end to end

- [ ] **Step 1: Full unit tests and lint**

Run: `./gradlew testDebugUnitTest lint --no-daemon`
Expected: BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 2: Upgrade path on the emulator**

```bash
scripts/emulator.sh up
git switch --detach origin/main && ./gradlew :app:assembleDebug --no-daemon && adb install -r app/build/outputs/apk/debug/app-debug.apk
git switch feat/continue-counted-opens && ./gradlew :app:assembleDebug --no-daemon && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.anchor/.ui.MainActivity && sleep 3 && adb logcat -d | grep -E "FATAL|IllegalStateException.*Migration" || echo "no crash"
```

Expected: `no crash`. The dashboard opens.

- [ ] **Step 3: The bug, by hand**

On the emulator, with a limit on an installed app (e.g. Chrome: 2 opens, 5 min session, 10 min cooldown):
1. Launch it, then press **Close** on the pause. The dashboard shows 0 opens and no cooldown.
2. Launch it, press **Continue**. The dashboard shows 1 open, and the session ends 5 minutes after the press.
3. Note any step that cannot be checked on the emulator in `docs/manual-verification.md`.

- [ ] **Step 4: Update the original spec's pointer**

In `docs/superpowers/specs/2026-09-09-usage-limits-design.md` §1.2, add under the heading:

```markdown
> **Superseded for opens** by `2026-10-12-continue-counted-opens-design.md`:
> opens, the session start and the close after an open are now recorded when
> the pause lets the user in. Minutes and "in front" still come from the log.
```

Commit:

```bash
git add docs
git commit -m "docs: point the usage-limits spec at continue-counted opens"
```
