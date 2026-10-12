# Phone Location, State Lists, and Vacation Mode — Design

**Status:** approved in chat 2026-10-11, awaiting written-spec review
**Extends:** `AGENTS.md` §1–3 (location gating), `docs/superpowers/specs/2026-09-14-groups-streaks-evening-sit-design.md`

Three additions to how The Anchor decides "are you somewhere the protocol applies":

1. **Phone location source** — use the phone's own location against a saved
   home place instead of querying Home Assistant.
2. **State lists** — "At Home" matches any of a user-defined list of entity
   states, not only `home`.
3. **Vacation mode** — a second saved place; while on, the phone's location
   against that place decides "home", and Home Assistant is not asked.

Out of scope: several named places, automatic vacation detection, room-level
detection from GPS, any change to the kill switch.

---

## 1. Behaviour

### 1.1 Choosing the source

Every location-gated decision (`MorningGate`, `EveningGate`, `EveningSitGate`)
resolves presence in this order:

1. `vacationMode` is on **and** `vacationPlace != null`
   → phone location vs `vacationPlace`.
2. `locationSource == PHONE`
   → phone location vs `homePlace` (`homePlace == null` → `UNKNOWN`).
3. Otherwise → Home Assistant, exactly as today, with §1.3 applied.

Vacation mode with no vacation place saved falls through to rules 2–3, so
flipping the switch before setting a place changes nothing.

### 1.2 Phone presence

| Reading | Presence |
|---|---|
| Distance to place ≤ place radius | `IN_SCOPE` |
| Distance to place > place radius | `OUT_OF_SCOPE` |
| No location permission, no fix within the timeout, or reported accuracy > 500 m | `UNKNOWN` |

`IN_SCOPE`/`OUT_OF_SCOPE`/`UNKNOWN` then drive the gates exactly as they do
for Home Assistant today (morning: lock / skip / skip-unless-ask-anyway;
evening: strict / 5s delay / delay-unless-ask-anyway; sit: lock / skip /
skip-unless-ask-anyway). The per-phase `LocationMode` and room lists are
ignored for phone presence: a phone cannot tell rooms apart.

### 1.3 State lists (Home Assistant source)

`LocationMode.AT_HOME` keeps its enum name (it is persisted) but is labelled
**"Entity state"** in the UI. It matches when the entity's `state`, trimmed,
equals any entry in the phase's state list, case-insensitively.

- New settings `morningHomeStates` and `eveningHomeStates`, default `["home"]`,
  so existing configurations behave identically.
- An empty list after editing is stored as empty and treated as `["home"]` at
  evaluation time, so clearing the field cannot silently disable the gate.
- HA's "don't know" states (`unavailable`, `unknown`, `none`, empty) remain
  `UNKNOWN` even if the user lists them.
- `SPECIFIC_ROOMS` is unchanged.

### 1.4 Unchanged

- The kill switch always queries Home Assistant, in every source and in
  vacation mode, and is checked before presence.
- Fail-open: anything short of a confirmed in-scope reading does less blocking,
  unless "ask anyway" is on.

---

## 2. Settings

New fields on `AnchorSettings` (all persisted in `SettingsRepository`;
missing keys read as the defaults):

| Field | Type | Default |
|---|---|---|
| `locationSource` | `LocationSource { HOME_ASSISTANT, PHONE }` | `HOME_ASSISTANT` |
| `homePlace` | `SavedPlace?` | `null` |
| `vacationMode` | `Boolean` | `false` |
| `vacationPlace` | `SavedPlace?` | `null` |
| `morningHomeStates` | `List<String>` | `["home"]` |
| `eveningHomeStates` | `List<String>` | `["home"]` |

`SavedPlace(latitude: Double, longitude: Double, radiusMeters: Int)`, default
radius 150 m, clamped to 25–5000 m. Persisted as one string key per place
(`"lat,lon,radius"`); unparseable → `null`. State lists use the existing
comma-separated room-list helpers.

`enforceWithoutHomeAssistant` keeps its key but is relabelled
**"Ask even when location is unknown"**, since it now covers phone failures too.

### 2.1 Settings UI

A new **Location** section precedes the Home Assistant section:

- **Source** radio: Home Assistant / This phone.
- **Home place** (shown when source is This phone): coordinates or "Not set",
  a **Use current location** button, radius field.
- **Vacation mode** switch, and under it the vacation place with its own
  **Use current location** button and radius field.
- The ask-anyway switch moves here from the Home Assistant section.

"Use current location" requests foreground then background location permission
if missing, then takes one fresh fix (§3) and saves it; on failure it shows a
short inline error and saves nothing.

In the morning/evening location sections, "Entity state" mode shows a
**States that count** field. Those sections show a hint "Not used: location
comes from this phone" when rule 1 or 2 of §1.1 is in effect.

### 2.2 Dashboard

- A **Vacation mode** banner (same style as the override banner) while
  vacation mode is on with a place set.
- `PermissionState` gains `locationGranted` and `locationNeeded`; when needed
  (source is PHONE or vacation mode is on) and background location is not
  granted, "Location (all the time)" is listed as missing with the reason
  "Checks where you are from the background when the phone is the location source."

---

## 3. Reading the phone's location

`PhoneLocationSource` interface, `suspend fun current(): PhoneFix?`, with an
`AndroidPhoneLocationSource` implementation on platform `LocationManager`
(no Google Play Services dependency).

1. No `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` → `null`.
2. Take the freshest `getLastKnownLocation` across the fused, network and GPS
   providers; if it is ≤ 10 minutes old, use it.
3. Otherwise `getCurrentLocation` on the fused provider (falling back to
   network, then GPS, whichever is enabled), bounded by a 5 s timeout.
4. Any exception or timeout → `null`.

`PhoneFix(latitude, longitude, accuracyMeters, timeMillis)`.

Manifest adds `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`,
`ACCESS_BACKGROUND_LOCATION`. Background location is required because gates
run from the accessibility service and the morning alarm. The foreground
service type is unchanged.

---

## 4. Components

```
data/location/
  SavedPlace.kt            SavedPlace, parse/format for persistence
  Geo.kt                   PURE: haversine distanceMeters(a, b)
  PhoneLocationSource.kt   interface + PhoneFix
  AndroidPhoneLocationSource.kt
data/ha/LocationGate.kt    AT_HOME takes a state list
domain/PresenceResolver.kt PURE routing (§1.1, §1.2) + suspend resolve(settings, phase)
di/AppModule.kt            binds PhoneLocationSource
```

`PresenceResolver.resolve(settings, phase): Presence` replaces the
`LocationGate.evaluate(client.fetchDeviceTracker(...), ...)` call in all three
gates. It calls HA only when HA is the source and the phone only when the phone
is, so no decision pays for both. The phone-vs-place comparison is a pure
function `phonePresence(fix: PhoneFix?, place: SavedPlace?): Presence`.

---

## 5. Testing

Unit (JVM, existing Robolectric/fakes setup):

- `Geo`: known distance pairs within 0.5%.
- `phonePresence`: inside, on the edge, outside, null fix, null place, accuracy > 500 m.
- `LocationGate`: state list hit on any entry, case/whitespace, empty list
  → `home`, unknown states stay `UNKNOWN`, rooms mode unchanged.
- `PresenceResolver`: vacation beats phone beats HA; vacation without place
  falls through; HA not called when phone is the source and vice versa.
- `SettingsRepository`: round-trip of every new field; old prefs without the
  keys read as defaults; malformed place string → `null`.
- `MorningGate`, `EveningGate`, `EveningSitGate`: existing tests pass through
  the resolver; one new case each for phone-source in/out.
- `PermissionState`: location listed only when needed and not granted.

Manual (added to `docs/manual-verification.md`): granting "Allow all the time",
setting a place from the current location, morning lock firing at home on phone
source with HA unreachable, vacation mode at a second place, location services
off → treated as unknown.
