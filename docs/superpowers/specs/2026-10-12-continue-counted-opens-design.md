# Continue-Counted Opens — Design

**Status:** approved in chat 2026-10-12, awaiting written-spec review
**Extends:** `docs/superpowers/specs/2026-09-09-usage-limits-design.md` §1.2–1.3
(reverses its "derived from the usage log" rule for opens only)

An open of a limited app is the user choosing to go in, not the app touching
the foreground. Today, launching a limited app shows it for a moment before the
pause screen covers it; the usage log records that moment as a session, so it
counts as an open, starts the session clock, and its BACKGROUND event starts a
cooldown — even when the user then closes the app from the pause.

From now on an open is recorded when the pause screen lets the user in, and
everything that hangs off "the open" keys off that record.

Out of scope: foreground minutes (TIME mode), "is the app in front" checks
(session-cap timer, bedroom wall), the Usage Access permission. These keep
using the usage log; the flash adds well under a second of minutes, which
does not matter.

---

## 1. Behaviour

### 1.1 What is an open

An **open** is one of:

- pressing **Continue** on the pause screen (`PauseActivity.continueToApp`),
- finishing a breathe and choosing to go in (`MeditationActivity.openAnyway`),

for an app covered by an active limit. Being let in is the open, whichever
button got you there.

Not an open:

- the app flashing in front before the pause covers it,
- closing the app from the pause, or walking away from it,
- returning inside a running session (a rejoin, which shows no pause),
- switching between members of a group inside a session,
- an evening-only pause (no limit): nothing is recorded.

Pressing Continue and then going home immediately still counts: the choice
was made.

### 1.2 What changes, field by field

| Thing | Before (usage log) | After |
|---|---|---|
| **Open** | Any foreground session, including the flash | A `limit_open` row |
| **Opens used** | Log sessions since the budget start, merged if under 60s apart | Rows for the limit's subject at or after the budget start (usage-day start, or the limit's window start if later) |
| **Session clock start** | First foreground event of the open (the flash) | `atMillis` of the latest row |
| **Rejoin** | Within the session of the flash, and only once the open is 15s old | Within `sessionMinutes` of the latest row, with no early lock since it and no pause owed |
| **Cooldown "last close"** | Last BACKGROUND event, flash included | Last log session end **after** the latest row; null if none |
| **Minutes (TIME mode)** | Usage log | Unchanged |
| **In front?** | Usage log | Unchanged |

Each Continue is one open. Without a session length, leaving and coming back
after the 60s pause grace (`PauseLedger.SATISFIED_WINDOW_MILLIS`) is paused
again and Continue is a new open. The old 60s coalescing of log sessions no
longer exists.

### 1.3 Inside an open

The gate is asked again on in-app window changes, not only on launches (the
service debounces repeats of one package for just 3s). The old code kept a
running open from counting against itself by discounting it when the app was
in front by the log. That signal is gone: a new launch's flash is also "in
front by the log", so discounting it would let a spent budget through.

Instead the app remembers, in memory, which limit the user is **inside**:

- **Entered** when a limited app reaches the front and is let through: while
  the pause is satisfied, or when the gate answers `Allow`.
- **Stepped away** when any other subject reaches the front (another app,
  the launcher, the in-call screen). Coming back within the **60s return
  grace** (`PauseLedger.RETURN_GRACE_MILLIS`), counted from the first step
  away, is still inside: no pause, no open. Hopping through several other
  apps does not restart the grace. Past it, the open is over and the next
  launch is a new one.
- Ignored windows (keyboard, notification shade, our own screens) are not
  stepping away. Switching between members of a group is not either: they
  share the subject.
- **Left at once**, with no grace, when a pause goes up for the subject (a
  session-cap pause included), when the session cap ends it, or on an early
  lock.

This replaces the old "two sessions under 60s apart are one open" rule, now
measured from leaving rather than read from the log.

**Only limits without a session length use it.** A limit with one already
defines the open by its session: returning inside the session is a rejoin,
and once it has elapsed the open is over. Letting "inside" apply there would
let a session run out while the user is away and then wave them back in for
a fresh one.

While inside, `LimitGate.decide` returns `Allow` unless the minute budget is
spent, exactly as for a rejoin. The open is not charged again and the pause
is not shown again. Process death forgets it; the next in-app change is then
judged as a launch. That is rare, and it is the same exposure as today's 60s
satisfied window.

### 1.4 Upgrade

Opens before this version are not in the table, so on the day of the upgrade
the open count restarts from zero. From the next reset it is exact. No
backfill from the usage log: that is the data this change stops trusting.

---

## 2. Components

### 2.1 New: `data/usage/LimitOpen.kt`

```kotlin
@Entity(
    tableName = "limit_open",
    indices = [Index(value = ["subject", "atMillis"])],
)
data class LimitOpen(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The limit's [AppLimit.subject], so a group's members share one count. */
    val subject: String,
    /** The member that was opened, for the dashboard's per-app breakdown. */
    val packageName: String,
    val atMillis: Long,
)

@Dao
interface LimitOpenDao {
    @Insert suspend fun insert(open: LimitOpen)

    @Query("SELECT * FROM limit_open WHERE subject = :subject AND atMillis >= :fromMillis ORDER BY atMillis ASC")
    suspend fun since(subject: String, fromMillis: Long): List<LimitOpen>

    @Query("DELETE FROM limit_open WHERE atMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
```

`AnchorDatabase` goes to version 5 with `MIGRATION_4_5` creating the table and
index; the DAO is provided from `AppModule` like `EarlyLockDao`.

### 2.2 Writing an open: `LimitGate.recordOpen`

```kotlin
/**
 * The pause let the user into [packageName]: that is the open. Does nothing
 * for a subject that is not a limit (an evening-only pause).
 */
suspend fun recordOpen(packageName: String, subject: String)
```

- Inserts `LimitOpen(subject, packageName, now)` when `subject` starts with
  `"limit:"`.
- Prunes `limit_open` and `early_lock` rows older than 48 hours. Early locks
  are currently never pruned (`EarlyLockDao.prune` has no caller); this
  gives it one.

Callers: `PauseActivity.continueToApp()` and `MeditationActivity.openAnyway()`.
Both call `finish()` straight after, which cancels `lifecycleScope`, so the
write runs on a process-lifetime scope instead: a new
`@ApplicationScope CoroutineScope` (`Dispatchers.IO + SupervisorJob()`)
provided from `AppModule` and injected into both activities.

### 2.3 Reading opens: `UsageCalculator.summarize`

New signature:

```kotlin
fun summarize(
    events: List<UsageEvent>,
    packageNames: Set<String>,
    windowStartMillis: Long,
    nowMillis: Long,
    opensMillis: List<Long>,
): AppUsageSummary
```

(The single-package overload follows suit.) Rules, each tested:

- `opens` = entries of `opensMillis` at or after `windowStartMillis`.
- `openUnits` = `opens` as a `Double` (kept so `BudgetSummary` is untouched).
- `lastOpenStartAtMillis` = the latest entry of `opensMillis`, or null.
- `lastForegroundEndAtMillis` = the end of the last completed log session
  that ends after `lastOpenStartAtMillis`; null when there is no open or no
  such session.
- `foregroundMillis`, `currentSessionStartAtMillis`: from the log, unchanged.

Removed: `OPEN_COALESCE_WINDOW_MILLIS`, the `sessionWindowMillis` and
`earlyLocksMillis` parameters, and from `AppUsageSummary` the fields
`lastOpenUnits`, `lastOpenEndAtMillis` and `currentOpenForegroundMillis`
(the last two are read only by tests). Session
building and group merging (`buildSessions`) stay as they are.
`UsageCalculator.rejoins` stays.

### 2.4 `LimitGate`

- Each `summarize` reads `limitOpenDao.since(limit.subject, queryFrom(dayStart))`
  alongside the events and early locks, and passes the times in. The 6h
  lookback already used for cooldowns covers a session or cooldown straddling
  the daily reset.
- `rejoinsOpen` drops the `MIN_ESTABLISHED_OPEN_MILLIS` check and the
  constant. The flash can no longer start an open, so there is nothing to
  guard against. The early-lock, owed-pause and unfinished-pause checks stay.
- Step 0 of `decide` treats `PauseLedger.isInside(subject, now)` like a
  rejoin, for a limit with no session length (§1.3).
- `spentBudget` compares `summary.openUnits >= dailyOpens` directly;
  `unitsBeforeThisOpen` goes. `afterSessionCap` drops its
  `copy(currentSessionStartAtMillis = null)`.
- `lockEarly` also leaves the subject.

### 2.4a `PauseLedger` and the service

```kotlin
const val RETURN_GRACE_MILLIS = 60_000L
fun enter(subject: String)
/** Inside, or stepped away less than the grace ago. */
fun isInside(subject: String, nowMillis: Long): Boolean
/** A subject reached the front: steps away from any other, or returns. */
fun onForeground(subject: String, nowMillis: Long)
/** Ends the open at once, no grace. */
fun leave(subject: String)
```

`SessionEndScreens.intentFor` calls `leave` when the session cap fires.

In `AnchorAccessibilityService`'s `EvaluateEvening` branch, right after the
subject is computed: `PauseLedger.onForeground(subject)`. The satisfied
short-circuit becomes `enter(subject)` then return, and a `Route.None`
outcome for a limited app also enters.
- `dashboardUsage`: each member's `perApp` summary gets the rows filtered to
  its `packageName`.

### 2.5 Unchanged code, shifted meaning

- `LimitStatus.sessionEndsAt` and `SessionCapMath.remainingMillis` already
  read `lastOpenStartAtMillis`, which now means "when the user was let in".
  `LimitStatus` drops its own `openUnits - lastOpenUnits` adjustment. An app
  in front with the time budget left reads as `InSession(null, true)`
  before the open count is checked: the dashboard is not the gate, and
  "spent" while you are using the last open would be misleading.
- `SessionCapWatcher` needs no change. The flash arms a full-length timer;
  when it fires it re-reads the remaining time, sees the newer open and
  re-arms to Continue + session length. If the user closed from the pause,
  nothing is in front and it does nothing.
- The pause screen's "N of M opens left" becomes accurate; it currently reads
  one short because the flash has already been counted.

---

## 3. Testing

Unit (JVM):

- `UsageCalculatorTest`, rewritten around `opensMillis`:
  - the bug: a 300ms log session and no open → 0 opens, no last close, no
    open start;
  - opens counted from the window start, earlier ones ignored;
  - last close only from a session ending after the latest open;
  - minutes and in-front unchanged by opens; group merging unchanged.
- `LimitGateTest`, with a fake `LimitOpenDao`:
  - `recordOpen` writes for a limit subject, not for a package subject;
  - closing from the pause (log flash, no row) → next launch still paused,
    no cooldown, opens unchanged;
  - `dailyOpens` reached → `Blocked(DAILY_OPENS)`; one under → `Pause`;
  - rejoin inside the session from the row, not after it; early lock ends it;
  - cooldown measured from a close after the row;
  - a limit window counts only rows inside it;
  - `recordOpen` prunes rows older than 48h;
  - inside the last open, an in-app re-check is `Allow`, not `Blocked`;
    after the grace has passed, the next launch is `Blocked(DAILY_OPENS)`;
  - a limit with a session length ignores "inside".
- `PauseLedgerTest`: enter; back within the grace is inside; past it is
  not, and coming back does not restore it; hopping between other apps
  keeps the first departure time; same subject is not a departure; a pause
  or an early lock leaves at once.
- `LimitStatusTest`, `BudgetSummaryTest`, `SessionCapWatcherTest` (which
  holds the `SessionCapMath` tests): updated to the new summary shape.

Verification: `./gradlew testDebugUnitTest lint`, then on the emulator:
launch a limited app, close it from the pause, and confirm the dashboard still
shows 0 opens and no cooldown; launch again, Continue, and confirm 1 open and
the session clock counting from that press.
