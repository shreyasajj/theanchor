# The Anchor — what I want this app to do

I have an Android app called **The Anchor** in this repository. Read the code, then
check it against the requirements below. These are the goals, not a description of
the current code. Where the code and this document disagree, this document wins.
The problems I have seen on my phone are listed at the very end.

---

## 1. What the app is

A personal, self-hosted Android app that enforces a daily mindfulness and
screen-time protocol on my own phone.

- Kotlin, Jetpack Compose, MVVM. Android 13 (API 33) and up.
- Sideloaded onto one phone. Not published to the Play Store.
- No account, no server of its own, no telemetry. The only network calls go to my
  Home Assistant and my Joplin, and both are optional.
- Dark, quiet, minimal design. Every piece of text must be readable on the dark
  background.
- Scrolling must feel smooth. A release build must be installable without my own
  signing key.

**Guiding rule: fail open.** When the app cannot be sure of something, it blocks
less, never more. The one deliberate exception is the setting in section 4.

**Safety rule: the phone must always be usable in an emergency.** The dialer, the
in-call screen, messaging, the emergency dialer, system UI and system Settings are
never blocked or interrupted by anything in this app.

---

## 2. Morning Anchor — the morning check-in

**When it applies**
- During a morning window, 05:00 to 12:00 by default, configurable.
- Only if today's morning check-in has not been completed.
- Only if the location rule says I am in scope (section 5).
- Not if the remote kill switch is on (section 6).

**How it is triggered**
- When the morning window opens.
- Also re-checked while I use the phone during the window, at most once a minute,
  so missing the exact moment does not skip the day.
- The dashboard has an **Answer now** button to do it by hand at any time.

**What it does**
- A full-screen screen I cannot dismiss without answering. Back does nothing. Home
  and recents bring it straight back. It cannot be swiped out of recents.
- The allowlist in section 1 still works while it is up. I can also add my own
  apps to the morning allowlist in Settings.
- Default questions: "What is my mission today?" and "What am I currently
  avoiding?" Both are editable, and I can add, remove and reorder questions.
- All questions need a non-blank answer before I can continue.
- Answers are saved to the local database and exported to Markdown (section 9).
- After I answer, it does not come back until the next morning.

---

## 3. Evening Anchor — the evening check-in

**When it applies**
- When I open an app on my **blocked apps** list during the evening window,
  20:00 to 05:00 by default, configurable.
- The window may cross midnight. 01:00 belongs to the evening that started the
  day before, so one night is one unit.
- Not if tonight's check-in is already done, or the kill switch is on.
- Setting **Ask the evening questions on their own** (off by default). When on,
  the questions also appear by themselves: when the evening window opens (an
  alarm) and I am in scope, and re-checked once a minute while I use the phone
  until answered. "In scope" means Home Assistant says so, or location is
  unknown and section 4's setting is on. A confirmed "not home" never asks. So a
  spent limit on a blocked app cannot keep the questions from being asked.

**What it does**
- If the location rule says I am in scope: a full-screen screen with three
  questions before the app opens.
  - "One moment I led: (What decision did I make without seeking approval?)"
  - "One moment I softened: (When did I express a feeling or show genuine appreciation?)"
  - "One moment I faked it: (When did I act to get approval rather than express truth?)"
  - All editable, like the morning questions.
- If I am confirmed away, or location is unknown: a 5-second pause instead.
- Answering once lifts the evening block for the rest of that night.
- Answers are saved and exported to Markdown.
- The dashboard has an **Answer now** button for the evening too.

---

## 4. Setting: ask even without Home Assistant

A toggle in Settings.

- **Off (default):** when Home Assistant is not set up or cannot be reached, the
  morning check-in is skipped and the evening shows the 5-second pause.
- **On:** when location is unknown, the morning and evening questions are still
  shown.
- Either way, a confirmed "not home" reading always skips.

---

## 5. Home Assistant location

- Settings: Home Assistant URL, long-lived access token, device tracker entity.
- Calls `GET {URL}/api/states/{entity}` with a bearer token.
- Short timeouts. A slow or dead Home Assistant must never hang a decision.
- Separate location mode for morning and for evening:
  - **At home:** in scope when the tracker's state is `home`.
  - **Specific rooms:** in scope when the tracker's `friendly_name` (or its state)
    contains any room from a comma-separated list, case-insensitive.


---

## 6. Remote kill switch

- Optional. Settings: an `input_boolean` entity and the state that disables
  blocking (for example `on`).
- Checked at the moment of **every** block, never cached, so flipping it in Home
  Assistant takes effect immediately.
- When active, all blocking is skipped: morning, evening and usage limits.
- When active, a status notification and a dashboard banner say so.
- Option **Treat an outage as override**, off by default. When on, an unreachable
  Home Assistant counts as the override. Settings explains that this is a
  one-gesture bypass.

---

## 7. Usage limits (groups, budgets, sessions)

A **limit** is a named group of one or more apps that share one budget. A single
limited app is a group of one. An app with no limit is untouched. Limits are
independent of the evening window.

| Control | Meaning |
|---|---|
| Limit by | **Opens per day** or **Minutes per day**. Only the chosen one is enforced; the other field is hidden and ignored. |
| Opens per day | Number of launches allowed (in Opens mode). 0 shuts the app for the whole window. |
| Minutes per day | Total foreground time allowed (in Time mode) |
| Session | How long, by the clock, until I am asked again whether I want the app. Runs from the moment I go in, whether or not I stay. |
| Cooldown | Minimum gap after closing before reopening |
| Pre-open pause | A forced wait before the app opens |
| Hours | Optional. The limit is in force only between these times (may cross midnight); all day otherwise. |

**Groups**
- Every member's usage is summed. Ten minutes in YouTube is ten minutes for the
  group. Switching between members inside a session is the same open.
- The session timer keeps running across members.
- The pause and blocked screens name the group (its name, or its apps).
- Example: YouTube and Instagram in one group, 2 opens, 10-minute sessions.
  Ten minutes of YouTube is one open. Five of Instagram and five of YouTube is
  the second. A third launch of either is blocked.

**Schedules (hours)**
- A limit with hours does nothing outside them, and its budget counts only
  what happens inside them. A spent budget comes back at the end of the hours
  or at the daily reset, whichever is sooner.
- The same app may be in several limits as long as their hours never overlap:
  two opens 14:00–17:00 and one open 17:00–21:00. Settings enforces this: the app
  picker hides any app that another limit already covers at an overlapping time.
  A new limit is all-day until its hours are set, so to give an app a second
  schedule: create the limit, set its hours, then add the app.

**Rules**
- Usage is **derived** from Android's usage statistics at decision time, not from
  counters the app keeps.
- The day resets at a configurable time, 04:00 by default, so a late night does
  not use up tomorrow's budget.
- A cooldown survives the daily reset.
- A spent limit blocks the app until it resets, with a screen saying why and when
  it is available again. The block screen offers breathing (section 10) or going
  home. In streak mode (section 7b) it also offers a way through.
- A spent daily budget is reported **before** a cooldown. I must never sit out a
  cooldown only to be told the day is spent. Likewise, when the session cap
  fires on the day's last open, that screen already says the day is spent.
- The open count blocks when the opens already **finished** reach the budget:
  with 2 opens per day, the third launch is blocked. The open in progress never
  counts against itself.
- Order of precedence: emergency allowlist first, then usage limits, then the
  evening check-in. A spent budget outranks the evening questions.
- One launch never shows two screens back to back. If the evening pause and a
  pre-open pause both apply, show one pause at the longer length.
- If usage access is revoked, nothing is blocked by a limit.
- Limits obey the kill switch.

**Sessions: the clock until the next question**
- A session runs by the **wall clock** from the moment I go in (Continue on the
  pause, or the open itself when there is no pause), whether or not I stay in
  the app.
- Inside the session, coming back is the **same open**: no second open charged,
  no cooldown, and **no pause**, because the pause was served on the way in.
- When the session elapses **while I am in the app**, I am pulled out and asked
  again: the pre-open pause appears (a countdown at its configured length, or
  the plain question if none is set). Continue is a **new open**. If a new open
  cannot happen (budget spent, or a cooldown), that screen says so instead.
  This must happen with the keyboard open, the notification shade down, or
  the floating button on screen; none of those mean I left the app. Only
  actually leaving it (another app, a phone call, home) means the question
  waits for the next open.
- When the session has elapsed while I was away, the next open is asked again
  the same way and is a new open.
- Only the **minutes budget** counts foreground time. Example: a 20-minute
  budget and a 5-minute session. I use the app for 2 minutes after Continue and
  close it. Reopening after 5 minutes shows the pause again, and I still have
  18 minutes left for the day.
- An early lock (section 11) is the session ending by hand: the same screen
  appears at once, and Continue is a whole new open, charged in full.
- An app with no session length gets the pause on every entry after the
  one-minute hand-off.
- **Every open of a limited app is asked about first.** There is no silent
  open: with no pause length set, the question is asked with no countdown.
- A pause I walked away from is still owed: coming back restarts it in full.

## 7b. Streaks

Setting **When a limit is reached**:
- **Block the app** (default): the blocked screen is a wall.
- **Let me through, but end my streak**: the blocked screen adds "Open anyway and
  end a N-day streak". Taking it waives that limit until the next daily reset and
  the streak restarts tomorrow.

The streak is the number of consecutive usage days on which I never walked
through a limit. It is shown on the dashboard's limits card while streak mode is
on.

---

## 8. The pre-open pause screen

Shown before every open of a limited app (a rejoin inside a session is not
an open). With a pause length it is a countdown; without one it is a plain
question, "Open [app]?", with Continue available at once.

**Shows**
- The app's name and, when there is a pause length, a countdown.
- What the open will cost, for whichever limits are set:
  - minutes left today
  - opens left today
  - minutes left in this session

**Three ways out**
- **Continue**: available once the countdown reaches zero, or at once when
  there is none. Opens the app.
- **Close the app**: goes home. Nothing is credited or charged; the app stays shut.
- **Breathe instead**: opens guided breathing (section 10).

**It cannot be walked around**
- If I leave the pause without tapping Continue (home, back, switching apps,
  closing the app), the pause is still owed.
- When I open the app again, the pause shows again with a **full, fresh
  countdown**, every single time, not just the first time.
- This must still hold if the Anchor's own process has restarted in between.
- Only sitting through the countdown and tapping Continue, or finishing a breathing
  session, lets me into the app.
- After Continue, the app opens and is not paused again for the rest of that
  session (section 7). Once the session is over, the next open is paused again.
- Rotating the phone does not restart the countdown, and does not count as
  walking away. The same holds for the breathing, blocked and question screens.

---

## 9. Markdown export and notes

- After every check-in, write one file per day, `YYYY-MM-DD.md`, into a folder I
  pick with the system folder picker. Morning and evening merge into the same file.
  Text I have written into that file myself is preserved.
- Plain format:

```markdown
# Daily Anchor - 2026-09-09

## Morning
- **Mission:** [answer]
- **Avoiding:** [answer]

## Evening
- **Led:** [answer]
- **Softened:** [answer]
- **Faked:** [answer]
```

- Questions I added myself appear with their own prompt as the label.
- Optional **Obsidian** format: the same content plus YAML frontmatter (`date`,
  `tags: [anchor]`) and a `Previous: [[yesterday]]` link under the title.
- Optional **Joplin**: API URL and token in Settings; each check-in is also posted
  as a note with `POST /notes`. If that fails, fall back to the local file silently.
- Standard Notes: no integration; Settings says to use plain Markdown and its
  importer.
- A check-in is always saved to the database even if exporting fails.

---

## 10. Guided breathing

- Offered from the pause screen, from a spent-limit screen, and from the dashboard
  (**Sit now**).
- Choose 1, 3, 5 or 10 minutes.
- Pattern: breathe in 4 seconds, hold 2, breathe out 6.
- A circle grows on the in-breath, holds, and shrinks on the out-breath, with the
  phase name and a per-phase countdown that never shows zero.
- A small haptic tick at each change of phase, so it works with eyes closed.
- **That's enough** stops early and still records the time sat.
- When it ends: **I'm good** goes home, or **Open [app] anyway** opens the app.
  Finishing a session counts as serving the pause.
- Sessions are recorded, including which app I chose not to open.
- The dashboard shows today's breathing minutes and number of sessions.

## 10b. Evening sit (optional lockdown)

Setting **Sit before the evening opens up**, off by default, with a minute count
(default 5).

- When on, during the evening window, when the evening location rule (section 5)
  says I am in scope, and today's recorded breathing is under the requirement,
  the phone is locked like the morning: a full-screen guided sit that comes
  straight back if escaped. Back does nothing.
- Sits done earlier in the day count, and short sits add up. "That's enough"
  still records what was sat; the lock ends as soon as the day's total reaches
  the requirement.
- The emergency allowlist and the morning allowlist still work.
- Location unknown: asks only if section 4's setting is on. Confirmed away: skips.
  The kill switch skips it.
- Triggered by an alarm when the evening window opens, and re-checked once a
  minute while I use the phone during the window.

---

## 11. Ending a session early (floating lock button)

- A small floating button, drawn by the app itself (not Android's system
  accessibility shortcut).
- Shown **only** while an app with an active limit is in the foreground.
- Hidden on the home screen, in apps with no limit, and while the Anchor's own
  screens are showing.
- **Stays put** while I am in the limited app. It must not blink out and back when
  the keyboard, a toast, a dialog or the notification shade appears.
- Fades to nearly transparent after a few seconds idle so it does not sit over
  video; brightens when touched.
- Can be dragged anywhere; its position is remembered.
- Tapping it **always** asks for confirmation: "Lock [app] now?" with **Not yet**
  and **Lock it**.
- Confirming ends that app's session, exactly as if its time had run out:
  any cooldown starts immediately and the pause screen appears at once (or
  the blocked screen, if a new open cannot happen). Continue from there is a
  whole new open, charged in full; Close goes home, and the next open is
  asked again.
- A Settings switch turns the button off entirely.

---

## 12. Dashboard

- Today's date as the header, and a settings button.
- **Setup** card listing anything not yet granted, each with a one-line reason and
  a Grant button: accessibility service, display over other apps, usage access,
  notifications, export folder.
- **Override** banner when the kill switch is active.
- **Today**: morning, evening and breathing, each done or pending, with
  **Answer now** / **Sit now**.
- **Today's limits**: one row per limit (group), with its hours if any, minutes
  or opens used of the budget, and a progress bar that turns red when spent.
  "Waived today" when walked through in streak mode. In streak mode the card
  shows the current streak.
  - Each row carries the limit's **state** right now, following the same
    order the next launch would: waived, off hours, in use / in session,
    spent, cooling down, or available. A session and the day's budget are
    different things: a session can have minutes left while the day is spent,
    and the other way round.
  - Tapping a row drops down its details: the time left in the running
    session (or the cooldown, or when a spent budget is back), counting down
    by the second, the session and cooldown lengths, and, for a group, one
    line per app with its own minutes and opens.
- **Live**: the dashboard refreshes every time it comes to the foreground and
  re-reads usage every five seconds while it is in front, so a session or a
  cooldown ending is visible without restarting the app. Home Assistant is
  not polled on that cadence, only on each return to the foreground.
- **Today** also shows the evening sit requirement and whether it is met.
- **Last seven days**: morning and evening completion for each day.
- **Status**: Home Assistant configured, kill switch state, number of blocked apps,
  export folder chosen.
- A persistent, quiet status notification keeps enforcement alive.

---

## 13. Settings

- **Schedule:** morning start and end, evening start and end, usage-limit reset
  time.
- **Blocked in the evening:** pick apps from installed apps, with search.
- **Morning allowlist:** pick apps, with search.
- **Evening:** ask the evening questions on their own; evening sit on/off and
  minutes.
- **App limits:** add a limit (pick one or more apps), name it, change its apps,
  set its hours, choose Opens or Time, set the controls (empty means no limit),
  remove it; floating lock button on/off. The app picker hides apps another
  limit covers at an overlapping time.
- **When a limit is reached:** block, or let through and end the streak.
- **Questions:** morning and evening lists; add, edit, reorder, delete. Editing a
  question keeps its past answers attached.
- **Home Assistant:** URL, token, device tracker, "Ask even without Home
  Assistant"; morning and evening location mode and rooms.
- **Remote kill switch:** on/off, entity, override state, treat outage as override.
- **Notes:** export folder, plain or Obsidian, Joplin URL and token.
- Changes save as I type without the cursor jumping.
- Changing the morning start, the evening start, or the evening settings re-arms
  the alarms.

---

## 14. Background behaviour

- An accessibility service watches which app is in front and routes every change
  through the rules above. It subscribes to window-state changes only and reads
  only the package name, never screen content or view ids.
- The session timer is not stopped by window events for system UI, the
  keyboard, toasts or the app's own floating button. When it fires it checks
  the usage log: if the app (or a group member) is still in front the
  question is shown; if a newer open began it re-arms for that one; otherwise
  it does nothing and the next open is judged by the rules.
- Limits are kept in memory and refreshed as they change, so the per-event path
  never queries the database. The dashboard reads the usage log once for all
  limits.
- A foreground service keeps it alive and shows the status notification.
- The morning and evening alarms and the service come back after a reboot.
- The floating button's window is hardware accelerated.

---

## 15. Project and tooling

- JVM unit tests for all the decision logic, runnable with no device.
- The database **migrates** on a schema change; it must never be recreated.
  An update never loses the questions, the daily log or the recorded sits.
  A migration test proves the previous version's data comes through.
- `README.md` explains what the app does and how to build and install it.
- A GitHub Action on every push to `main` runs the tests, builds the APKs, and
  publishes a GitHub release with the installable APK attached.
- `scripts/emulator.sh` boots a headless emulator, installs the debug build with
  permissions granted, and screenshots every screen. It must never install to or
  touch a real phone that is plugged in.

---

## Thing I want to change/bugs

(Empty. Add new items here.)

---

## Changed on 2026-09-14 (later)

- **The session cap did not pull me out.** Its timer was cancelled by window
  events for the keyboard, the shade and the app's own floating button, and
  only re-armed on the next open. It now ignores those and checks the usage
  log when it fires (section 14).
- **A limit with no pause length opened silently.** Every open is now asked
  about; with no pause length the question has no countdown (sections 7, 8).
- **The lock button sent me home and the next open showed no pause.** It now
  shows the pause screen at once, like a session ending; Continue is a whole
  new open charged in full. The half-open charge is gone (sections 7, 11).

- **Dashboard shows each limit's state and session time.** A row says
  whether the limit is in use, in session, spent, cooling down, waived, off
  hours or available, and drops down to the countdown and, for a group, the
  per-app split (section 12).
- **Dashboard did not update until the app was restarted.** It now refreshes
  on every return to the foreground and re-reads usage every five seconds
  while in front (section 12).

## Changed on 2026-09-14

Everything below came from the previous bug list and is now described in the
sections above. Design notes: `docs/superpowers/specs/2026-09-14-groups-streaks-evening-sit-design.md`.

- **Pause shown again inside a session.** Returning inside a session is the same
  open and is not paused (section 7, "Sessions").
- **Evening sit lockdown**, configurable (section 10b).
- **Evening questions after a session ends.** Confirmed by the routing order: any
  limit answer other than a hard block consults the evening gate. Also added
  "Ask the evening questions on their own" (section 3) so a spent limit cannot
  bury them.
- **Rotation restarting the countdown.** The lock screens keep their state across
  rotation (section 8).
- **Limit by opens or by time**, with a session length in either mode, counting
  foreground time only (section 7).
- **Groups** sharing one budget and one session (section 7).
- **Streaks** with a configurable block-or-let-through (section 7b).
- **Lag and tearing.** The accessibility service subscribes to less, limits are
  cached, the dashboard reads usage once, and the floating button is hardware
  accelerated (section 14). Tearing could not be reproduced on the emulator; the
  overlay change is the most likely fix.
- **Cooldown screen followed by "last open" screen.** A spent budget is now
  reported before a cooldown, and the session-cap screen says the day is spent
  when it is (section 7, "Rules").
- **Questions lost on update.** The database recreated itself on every schema
  change. It now migrates, so the questions, the daily log and the recorded
  sits survive updates (section 15). The limits table is rebuilt once by this
  update; limits have to be entered again this one time.
- **Early lock dropped me into the pause.** The app's momentary reappearance
  while the lock sheet closes is ignored; locking goes straight home
  (section 7, "Sessions").
- **Session = clock until the next question.** Sessions are wall clock from
  Continue, and ending one pulls me out into the pause; continuing is a new
  open (section 7, "Sessions"). Replaces the foreground-time rule from earlier
  today.
- **Limited apps offered again in the picker.** Hidden unless free at those hours
  (section 7, "Schedules").
- **Schedules**: different budgets at different hours (section 7, "Schedules").

Note: the limits table changed shape and is rebuilt by this update, so limits
have to be entered again once. Nothing else is lost.

