# Groups, streaks, the evening sit, and session rejoin — design

Date: 2026-09-14. Source: the "Thing I want to change/bugs" list in
`docs/requirements-prompt.md`.

## 1. Session rejoin without a second pause (bug 1, item 5)

**Problem.** After sitting through the pre-open pause and tapping Continue,
leaving and coming back inside the session shows the pause again.

**Rule.** A *session* is the time budget of one open. Its clock counts
foreground time only; time spent outside the app does not count. Returning to
an app whose session still has time left, and which was left for less than the
session length, is the *same open*: no pause, no second open charged, no
cooldown. The pause returns only when the session is over: its foreground time
is spent, the app was left for longer than the session length, or it was
locked early.

A pause that was shown and walked away from is still owed and is shown again
with a fresh countdown, exactly as before. Apps with no session length keep
the old behaviour: every entry after the one-minute hand-off is paused.

**Data.** `UsageCalculator` merges sessions into opens using the new rule and
reports `currentOpenForegroundMillis`, `lastOpenEndAtMillis` and
`lastOpenUnits`. `SessionCapMath.remainingMillis` counts from foreground time.
The session-cap watcher therefore also stops counting while you are out of the
app.

**Opens counting.** The running open never counts against itself. A launch is
blocked by the open count when the *closed* opens already reach the budget.
Previously the budget was exceeded by one because of a "not yet logged" guess.

## 2. Limit by opens or by time (item 5)

Each limit has a **mode**: *Opens* or *Time*. Only the chosen daily budget is
enforced; the other field is hidden in Settings and ignored by the gate.
Session length, cooldown and the pre-open pause are available in either mode.

## 3. Groups (item 6)

Every limit is a **group**: a name plus one or more apps that share one
budget. A single limited app is a group of one. Usage of every member is
summed; switching between members inside a session is the same open; the
session-cap timer keeps running across members; the pause screen and the
blocked screen name the group.

Ledgers (pauses owed, early locks, bypasses) are keyed by the group id rather
than the package, so a pause owed for YouTube is also owed for Instagram when
they are in the same group.

Schema: `app_limit` becomes `(id, name, packages, enabled, limitMode,
dailyMinutes, dailyOpens, cooldownMinutes, sessionMinutes,
preOpenDelaySeconds)`. `early_lock.packageName` becomes `subject`. Database
version 4; the existing destructive-migration policy means limits must be
re-entered once.

## 4. Streaks (item 7)

A setting **When a limit is reached**: *Block the app* (default, today's
behaviour) or *Let me through, but end my streak*.

The streak is the number of consecutive usage days without a break, counted
from a stored start day. In streak mode the blocked screen adds **Open anyway
and end the streak**. Confirming records a break (the streak starts again
tomorrow) and bypasses that group's limits for the rest of the usage day. The
dashboard shows the streak in the limits card while streak mode is on.

## 5. Evening sit (item 2)

A setting **Sit before the evening opens up**: off by default, with a minute
count (default 5). When on, during the evening window, when the evening
location rule says in scope, and the day's recorded breathing is under the
requirement, the phone locks like the morning lockdown: a full-screen guided
sit that comes straight back if escaped. The morning allowlist and the
emergency allowlist apply. Finishing enough breathing ends the lock. Sits
done earlier in the day count. The kill switch skips it. Triggered by an
alarm at the evening start and re-checked once a minute while the phone is
in use.

## 6. Rotation (item 4)

The pause, breathing, blocked and question screens handle configuration
changes themselves so rotating does not recreate them. The pause also ignores
a configuration-change stop, so rotating never counts as walking away.

## 7. Evening question after a session (item 3)

Confirmed by reading the route: the limit gate runs first, and any answer
other than *Blocked* consults the evening gate, so after a capped session the
next open still asks. Covered by routing tests; no change beyond the rejoin
rule.

## 8. Performance (item 8)

- Accessibility service subscribes to window-state changes only, without
  view ids or interactive-window retrieval, which it never used.
- Limits are cached in memory; the per-event path no longer queries Room.
- The dashboard reads the usage-event log once for all groups instead of once
  per app.
- The floating button window is hardware accelerated.

## 9. Additions (same day)

- **Budget before cooldown.** A spent daily budget is reported ahead of a
  cooldown, and the session-cap screen consults the budget with the capped
  open counted, so the user is never told to wait twice.
- **Picker hides limited apps.** An app is offered only if no other limit
  covers it at an overlapping time.
- **Schedules.** A limit may carry hours (`windowStartMinute`,
  `windowEndMinute`). Outside them it is not consulted; inside, its budget
  counts usage from the later of the day start and the window start, and a
  spent budget resets at the window end or daily reset, whichever is sooner.
  `LimitGate.limitFor` picks the limit in force now.
- **Unprompted evening questions.** Setting `eveningPromptOnItsOwn`. The
  evening alarm and the minute re-check show the questions when in scope and
  tonight is not done, independent of any app.

## 10. Revisions (same day, later)

- **Sessions are wall clock**, from the open's start, superseding section 1's
  foreground-time rule. `UsageCalculator.rejoins(openAge, window)`. Ending a
  session in the foreground shows the pause (`LimitGate.afterSessionCap`
  returns `Pause` unless the budget or a cooldown blocks); Continue is a new
  open. Touching sessions of one package are no longer merged, so the pause's
  hand-off reads as a new open.
- **Early lock grace.** `PauseLedger.ignoreAfterEarlyLock` makes the service
  ignore the locked subject for five seconds, so the app's reappearance while
  the sheet closes does not show the pause.
- **Migrations.** `Migrations.MIGRATION_3_4` rebuilds `app_limit` and
  `early_lock`; destructive fallback only from versions 1 and 2. Covered by
  `MigrationsTest`.

## Out of scope

- Migrating existing limit rows (destructive migration is the project's
  policy).
- A per-group streak; the streak is global.
