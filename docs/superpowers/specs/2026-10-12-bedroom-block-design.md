# Bedroom Block — Design

**Status:** approved in chat 2026-10-12, awaiting written-spec review
**Extends:** `AGENTS.md` §2 (Evening Anchor), `docs/superpowers/specs/2026-10-11-phone-location-and-vacation-design.md`

Two places, two rules. At home, opening a blocked app asks the evening
questions (unchanged). In the bedroom, during the bedroom hours, blocked apps
do not open at all, and answering the questions does not change that.

Out of scope: a separate app list for the bedroom, room detection from the
phone's own location, a dashboard card, any change to the questions themselves.

---

## 1. Behaviour

### 1.1 When the block applies

A blocked app is **bedroom-blocked** when all of these hold, checked in this
order (cheapest and local first, network last):

1. **Bedroom block** is switched on.
2. The package is in the evening `blockedPackages` list, and is not The Anchor.
3. The time is inside the bedroom hours (`bedroomStartMinute`–`bedroomEndMinute`,
   wrapping midnight like the other windows).
4. Vacation mode is not deciding presence (`presenceSource != VACATION`): on
   vacation you are not in this house's bedroom.
5. The kill switch does not disable blocking (`KillSwitch.blockingDisabled`,
   so "treat an outage as override" applies too).
6. Home Assistant confirms you are in one of the bedroom rooms: the device
   tracker read with `LocationGate.evaluate(result, SPECIFIC_ROOMS, bedroomRooms)`
   is `IN_SCOPE`. A room name matches inside `friendly_name` or the state,
   case-insensitively, exactly as Specific Rooms does today.

Anything else is **not blocked** and the app goes through today's rules
(limits, evening questions, pause) untouched.

### 1.2 Fail-open

The bedroom block only ever fires on a confirmed reading. Home Assistant not
configured, unreachable, or reporting `unknown`/`unavailable`, an empty room
list, or a room that does not match all mean "not blocked". The **Ask even when
location is unknown** switch does not apply here: it turns unknown into
"ask the questions", never into a wall.

### 1.3 Precedence

Bedroom block > usage-limit block > strict evening overlay > pause. In
particular it is decided **before** the usage limit, so streak mode's "Open
anyway" on a spent limit cannot open an app in the bedroom, and before the
pause ledger's shortcuts, so a pause sat through elsewhere does not let an app
in here.

### 1.4 The screen

`BedroomBlockedActivity`: full-screen, dark, the same style as the limit screen.

- Eyebrow "Bedroom", title "Not in the bedroom".
- Body: "<App name> stays closed in the bedroom until <end time>."
- One button, **Go to home screen**, which opens the launcher and finishes.
  Back does the same. There is no way through; the kill switch is the escape
  hatch, as for limits in strict mode.

### 1.5 Apps already open

While a blocked app is in front, a `BedroomWatcher` re-checks every
**2 minutes**. On each tick:

- If the app is no longer in front (by the usage log, as the session cap
  checks), the watcher stops.
- Otherwise it asks the same decision as §1.1; if blocked, it shows the
  bedroom screen for that app and stops.

The watcher is armed when a blocked app comes to the front while Bedroom block
is on (it does not need the bedroom hours yet: an app opened at 21:55 is
caught after 22:00). Like `SessionCapWatcher`, it is not cancelled by window
events (keyboard, shade, our own overlay); arming it for a different blocked
app replaces it; a lockdown cancels it.

---

## 2. Settings

New fields on `AnchorSettings`, persisted in `SettingsRepository` (missing keys
read as defaults):

| Field | Type | Default |
|---|---|---|
| `bedroomBlockEnabled` | `Boolean` | `false` |
| `bedroomRooms` | `List<String>` | `["Bedroom"]` |
| `bedroomStartMinute` | `Int` | `22 * 60` |
| `bedroomEndMinute` | `Int` | `7 * 60` |

The room list uses the existing comma-separated helpers. Changing the hours
does not re-arm any alarm: the block is only ever checked when an app opens or
the watcher ticks.

### 2.1 Settings UI

A **Bedroom block** card in the Home Assistant group, after "Evening location":

- **Bedroom block** switch, supporting text: "Blocked apps don't open at all in
  these rooms during these hours, even after the evening questions. Needs Home
  Assistant room detection; if it can't tell, apps open as usual."
- When on: **Rooms (comma-separated)** text field, **Starts** and **Ends**
  time rows (the existing `TimeRow`), and a hint "Uses your blocked apps list."
  When vacation mode is on, a hint "Off while vacation mode is on."

---

## 3. Components

```
domain/BedroomGate.kt         BedroomDecision { Block(endMinute), Allow(reason) }; BedroomGate.decide(pkg)
domain/BedroomWatcher.kt      the 2-minute re-check, scope + queries + callback, like SessionCapWatcher
ui/lock/BedroomBlockedActivity.kt
ui/settings/sections/BedroomSection.kt
service/AnchorAccessibilityService.kt   decide bedroom first; arm the watcher; launch the screen
data/settings/AnchorSettings.kt, SettingsRepository.kt   the four fields
```

`BedroomGate` takes `SettingsProvider`, `HomeAssistantClient`, `KillSwitch`,
`AnchorDate`, and never throws (the HA client already collapses failures to
`Unavailable`). `Allow` carries a `SkipReason`-style reason so tests can assert
why. `BedroomWatcher` takes its "still in front" and "decide" functions as
parameters so it is testable with virtual time and fakes, and uses `LimitGate`'s
`isInForeground` in production.

---

## 4. Testing

Unit (JVM, existing Robolectric/fakes setup):

- `BedroomGate`: blocks when every §1.1 condition holds; one test per condition
  that, when false, allows; HA unavailable / unknown state / empty rooms allow;
  "ask anyway" on still allows on unknown; kill switch checked before the
  tracker; no network call when disabled, outside hours, or not a blocked app;
  hours wrap midnight.
- `BedroomWatcher` (virtual time): fires after 2 minutes in the bedroom;
  stays silent while not in the bedroom and fires on the first tick after
  entering; stops when the app leaves the front; re-arming for another app
  replaces the timer; cancel stops it.
- Routing: bedroom block outranks a spent limit, the strict overlay and a pause.
- `SettingsRepository`: defaults and round-trip of the four fields.
- `BedroomCopy` (the screen text): app name and end time formatting.

Manual (added to `docs/manual-verification.md`): bedroom hours containing now,
tracker in the bedroom → app shows the bedroom screen, also after answering the
questions; tracker in the living room → the questions; HA stopped → questions or
pause, never the bedroom screen; app opened in the living room, tracker moved to
the bedroom → closed within 2 minutes; kill switch on → opens.
