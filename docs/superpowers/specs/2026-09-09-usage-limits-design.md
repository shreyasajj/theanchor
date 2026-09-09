# Usage Limits, Note Formats, and Kill-Switch Failure Mode — Design

**Status:** approved 2026-09-09
**Extends:** `AGENTS.md` (original spec), `docs/superpowers/plans/2026-09-09-the-anchor.md` (Tasks 1–24)
**Implemented by:** Tasks 25–34 of that plan

Three additions, in descending order of size:

1. **Usage limits** — a ScreenZen-style per-app budget subsystem (new).
2. **Note formats** — Obsidian-flavoured Markdown alongside the existing plain and Joplin outputs.
3. **Kill-switch failure mode** — make "Home Assistant is unreachable" a configurable override trigger.

---

## 1. Usage Limits

### 1.1 Mechanics

Five independent, optional, per-app controls. Any subset may be configured for
any app; an app with none configured is unaffected.

| Mechanic | Field | Meaning | Enforced |
|---|---|---|---|
| Daily time budget | `dailyMinutes` | Total foreground minutes allowed per day | At open, and mid-session |
| Daily open count | `dailyOpens` | Number of launches allowed per day | At open |
| Cooldown | `cooldownMinutes` | Minimum gap after closing before reopening | At open |
| Session cap | `sessionMinutes` | Maximum length of any single session | Mid-session, by timer |
| Pre-open pause | `preOpenDelaySeconds` | Forced wait on a blank screen before the app opens | At open |

`null` means "no limit" for the first four. `preOpenDelaySeconds = 0` means no
pause. All five are independent of the Evening Anchor and apply 24 hours a day.

### 1.2 Derived state, not counters

**Decision: the app stores no usage counters.** At each decision it queries
`UsageStatsManager.queryEvents()` over the current day-window and derives
elapsed time, open count, and last-close time from the raw event list.

Rationale: counters drift. They are lost when the process is killed, double-count
when the accessibility service restarts mid-session, and go stale across reboot.
Derived state cannot. It also makes the entire computation a pure function over a
list of events, so every mechanic is unit-testable with no device and no clock.

The single exception is the **session cap**, which must interrupt an app already
in use and therefore cannot be answered by a query. It is a coroutine timer owned
by the accessibility service, cancelled whenever the foreground app changes.

### 1.3 Components

```
data/usage/
  AppLimit.kt            entity: the five fields above, keyed by packageName
  AppLimitDao.kt
  UsageEvent.kt          package-agnostic event model + AppUsageSummary
  UsageCalculator.kt     PURE: List<UsageEvent> -> AppUsageSummary
  UsageStatsSource.kt    interface + AndroidUsageStatsSource (UsageStatsManager)
domain/
  LimitGate.kt           LimitDecision, LimitReason
  SessionCapWatcher.kt   the mid-session timer
ui/lock/
  PauseActivity.kt       generalises SimpleDelayActivity to N seconds
  LimitBlockedActivity.kt
```

#### `UsageCalculator` (pure)

```kotlin
data class UsageEvent(
    val packageName: String,
    val type: Type,
    val timestampMillis: Long,
) { enum class Type { FOREGROUND, BACKGROUND } }

data class AppUsageSummary(
    val foregroundMillis: Long,
    val opens: Int,
    val lastForegroundEndAtMillis: Long?,
    /** Non-null when the app is in the foreground right now. */
    val currentSessionStartAtMillis: Long?,
)

object UsageCalculator {
    const val OPEN_COALESCE_WINDOW_MILLIS = 60_000L
    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
    ): AppUsageSummary
}
```

Rules, each of which gets a test:

- Events are filtered to `packageName` and sorted by timestamp defensively.
- `FOREGROUND` opens a session; a second `FOREGROUND` with no intervening
  `BACKGROUND` is ignored, not treated as a new session.
- A session already open at `windowStartMillis` is clamped to that boundary — its
  time before the reset belongs to the previous day.
- A session still open at the end accrues time up to `nowMillis` and sets
  `currentSessionStartAtMillis`.
- `foregroundMillis` is the sum of session durations within the window.
- **An "open" is a session start where the gap since the previous session's end
  is at least `OPEN_COALESCE_WINDOW_MILLIS` (60s), or where there is no previous
  session.** Without this, pulling down the notification shade and dismissing it
  would count as a fresh open.
- `lastForegroundEndAtMillis` is the end of the last *completed* session, or null.

#### `LimitGate`

```kotlin
sealed interface LimitDecision {
    data object Allow : LimitDecision
    data class Pause(val seconds: Int) : LimitDecision
    data class Blocked(val reason: LimitReason, val resetsAtMillis: Long) : LimitDecision
}
enum class LimitReason { DAILY_TIME, DAILY_OPENS, COOLDOWN, SESSION_CAP }
```

`suspend fun decide(packageName: String): LimitDecision`, evaluated in order:

1. No `AppLimit` row, or `enabled = false` → `Allow`.
2. Kill switch active → `Allow`. Limits obey the remote override like every
   other blocking path.
3. Summarize usage over the current day-window.
4. `cooldownMinutes` set, a previous session exists, and
   `now - lastForegroundEndAt < cooldown` → `Blocked(COOLDOWN, lastEnd + cooldown)`.
5. `dailyOpens` set and `opens > dailyOpens` → `Blocked(DAILY_OPENS, nextReset)`.
6. `dailyMinutes` set and `foregroundMillis >= dailyMinutes * 60_000` →
   `Blocked(DAILY_TIME, nextReset)`.
7. `preOpenDelaySeconds > 0` → `Pause(preOpenDelaySeconds)`.
8. Otherwise `Allow`.

**Documented off-by-at-most-one.** By the time the accessibility service reacts
to a launch, `UsageStatsManager` has usually — but not always — already logged
that launch's `ACTIVITY_RESUMED`. The open-count comparison is therefore
`opens > dailyOpens` rather than `>=`. When the event has not yet landed the user
gets at most one extra open, and the following open blocks. The error direction is
deliberate: it errs toward allowing, consistent with the fail-open rule that
governs the rest of the app.

#### Session cap

When a limited app with `sessionMinutes` set enters the foreground, the
accessibility service starts a timer for
`sessionMinutes * 60_000 - elapsedInCurrentSession`. If it expires while that app
is still foreground, `LimitBlockedActivity` is launched with
`LimitReason.SESSION_CAP`. Any foreground-app change cancels the timer.

### 1.4 The customizable day boundary

A new setting, `dayResetMinute`, default **04:00**, defines when usage limits
reset — not midnight, so a late night does not silently consume the next day's
budget. It reuses the day-anchoring shape already written for the evening window.

`AnchorDate` gains three methods, each tested:

```kotlin
fun usageDay(dayResetMinute: Int): String
fun usageDayStartMillis(dayResetMinute: Int): Long
fun nextUsageResetMillis(dayResetMinute: Int): Long
```

`nextUsageResetMillis` is what `LimitDecision.Blocked` reports to the user as
"available again at…".

### 1.5 Interaction with the existing gates

The accessibility service's routing becomes, in order:

1. `ForegroundAppDecider` — morning lockdown and the emergency allowlist.
   **Unchanged, and still first**: the dialer is never interrupted by a limit.
2. `LimitGate` — a hard limit outranks the evening ritual. There is no sense
   answering three reflective questions to reach an app whose budget is spent.
3. `EveningGate` — strict overlay, simple delay, or allow.
4. Pause, if either gate asked for one.

**Pause coalescing:** if `EveningGate` returns `SimpleDelay` (5s) and the app also
carries a 30s pre-open pause, the user sees **one** screen for
`max(5, 30) = 30` seconds — never two interstitials in a row.

`SimpleDelayActivity` is replaced by `PauseActivity`, which takes the duration as
an intent extra. The evening fail-open path passes 5; the pre-open pause passes
its configured value.

### 1.6 Settings

A new "App limits" section: pick an app, then set the five fields. Apps with any
limit configured are listed with a one-line summary (`YouTube — 30 min/day,
5 opens, 30s pause`). `dayResetMinute` is a single time field in the Schedule
section.

---

## 2. Note Formats

`MarkdownRenderer` gains a `NoteFormat` parameter:

```kotlin
enum class NoteFormat { PLAIN, OBSIDIAN }
```

- **`PLAIN`** (default) — byte-for-byte the format in `AGENTS.md` §5. Unchanged.
- **`OBSIDIAN`** — YAML frontmatter plus a link to the previous day, matching
  daily-note conventions:

```markdown
---
date: 2026-09-09
tags: [anchor]
---

# Daily Anchor - 2026-09-09

Previous: [[2026-09-08]]

## Morning
- **Mission:** …
```

The previous-day link sits directly under the title, not at the end of the file,
so that appending an `## Evening` section later cannot strand it below the
content. `MarkdownRenderer.mergeInto` already preserves unrelated content, which
covers the frontmatter block; a test pins that behaviour.

**Joplin** is unchanged — the existing REST push (`POST /notes`) with silent
fallback to the local file.

**Standard Notes** gets no API integration. Their sync is end-to-end encrypted
with no local REST endpoint comparable to Joplin's, so there is nothing honest to
build. The folder export already produces files their importer accepts; Settings
says so in one line rather than offering a toggle that would not work.

**Obsidian** needs no integration beyond the format: point the existing folder
picker at the vault's daily-notes directory.

---

## 3. Kill-Switch Failure Mode

New setting `killSwitchFailOpenOnOutage: Boolean`, default `false` — today's
behaviour, where an unreachable Home Assistant does **not** count as an override.

`KillSwitch.isBlockingDisabled` takes the settings snapshot so the caller cannot
forget to consider the flag:

```kotlin
fun isBlockingDisabled(status: OverrideStatus, settings: AnchorSettings): Boolean =
    status == OverrideStatus.ACTIVE ||
        (status == OverrideStatus.UNKNOWN && settings.killSwitchFailOpenOnOutage)
```

The trade-off is stated in Settings next to the switch: turning it on means
losing network access disables all blocking, which is a one-gesture bypass.

---

## 4. Settings Added

| Field | Default | Section |
|---|---|---|
| `dayResetMinute: Int` | `4 * 60` (04:00) | Schedule |
| `killSwitchFailOpenOnOutage: Boolean` | `false` | Remote kill switch |
| `noteFormat: NoteFormat` | `PLAIN` | Markdown export |

Per-app limits live in the `app_limit` Room table, not in DataStore, because they
are a growing keyed collection rather than a fixed set of scalars.

---

## 5. Testing

Everything above is JVM-unit-testable except the two new Activities and the
`UsageStatsManager` query itself.

- `UsageCalculator` carries the heaviest suite: empty input, currently-foreground,
  a session spanning the reset boundary, duplicate `FOREGROUND` events, rapid
  pause/resume coalescing, other packages ignored, unsorted input.
- `LimitGate` mirrors the `EveningGate` test shape: one test per mechanic, one per
  ordering rule, plus kill-switch interaction.
- `AnchorDate` day-boundary tests extend the existing evening-anchor tests.
- `MarkdownRenderer` gains Obsidian format tests including a merge-preserves-
  frontmatter case.
- `KillSwitch` gains fail-open-flag tests on both settings.
- `AndroidUsageStatsSource` is verified by the manual device checklist, not a
  unit test; the interface seam keeps it out of every other test.

## 6. Out of Scope

- Per-app schedules (different limits on weekends).
- Global device-wide time budgets across all apps.
- Usage history charts. The dashboard shows today's numbers for limited apps
  only; anything richer belongs in whatever reads the Markdown files.
- Any Standard Notes API client, per §2.
