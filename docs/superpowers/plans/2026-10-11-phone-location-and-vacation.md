# Phone Location, State Lists, and Vacation Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let presence come from the phone's own location (against a saved home place or, in vacation mode, a vacation place), and let Home Assistant's "At Home" mode match a list of states.

**Architecture:** A new pure `data/location` package (places, distance, phone-vs-place presence) plus an `AndroidPhoneLocationSource` behind an interface. A new `PresenceResolver` picks the one source that decides presence and replaces the direct Home Assistant call in `MorningGate`, `EveningGate` and `EveningSitGate`. Settings, the settings UI and the dashboard gain the new fields.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore Preferences, platform `LocationManager` (no Play Services), JUnit4 + Robolectric + Truth.

**Spec:** `docs/superpowers/specs/2026-10-11-phone-location-and-vacation-design.md`

## Global Constraints

- minSdk 33, compileSdk/targetSdk 35. No new Gradle dependencies; no Google Play Services.
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` before every Gradle command, and pass `--no-daemon`.
- Fail-open: anything short of a confirmed in-scope reading does less blocking, unless "ask anyway" (`enforceWithoutHomeAssistant`) is on.
- The kill switch always queries Home Assistant, in every source and in vacation mode, and is checked before presence.
- Nothing location-related may throw out of a gate: every failure collapses to `Presence.UNKNOWN`.
- Default radius 150 m, clamped to 25–5000 m. Cached fix max age 10 minutes. Fresh-fix timeout 5 s. Accuracy worse than 500 m → unknown.
- Persisted enum names (`LocationMode.AT_HOME`) and existing preference keys (`enforce_without_ha`) must not be renamed.
- Match the surrounding code: KDoc on public types explaining *why*, backtick test names, Truth assertions.

## Review Focus

1. **Vacation switch on, no vacation place saved** → behaves exactly as before (Task 4 resolver test, Task 2 settings test).
2. **"States that count" field cleared to empty** → still means `home`, never "nothing matches" (Task 3 test).
3. **Phone source chosen but no home place / no permission / no fix** → `UNKNOWN`, fail-open, no Home Assistant location call, no crash (Task 4 and Task 5 tests).
4. **Radius typed as text, 0, or 99999** → ignored / clamped to 25 / clamped to 5000 (Task 7 tests).
5. **Coarse fix (accuracy > 500 m, cell-only indoors)** → presence unknown, and "Use current location" refuses to save it (Task 1 and Task 7 tests).

---

### Task 1: Location primitives

**Files:**
- Create: `app/src/main/java/com/anchor/data/location/SavedPlace.kt`
- Create: `app/src/main/java/com/anchor/data/location/Geo.kt`
- Create: `app/src/main/java/com/anchor/data/location/PhoneLocationSource.kt`
- Create: `app/src/main/java/com/anchor/data/location/PhonePresence.kt`
- Test: `app/src/test/java/com/anchor/data/location/SavedPlaceTest.kt`
- Test: `app/src/test/java/com/anchor/data/location/GeoTest.kt`
- Test: `app/src/test/java/com/anchor/data/location/PhonePresenceTest.kt`

**Interfaces:**
- Consumes: `com.anchor.data.ha.Presence` (existing enum `IN_SCOPE, OUT_OF_SCOPE, UNKNOWN`).
- Produces:
  - `data class SavedPlace(latitude: Double, longitude: Double, radiusMeters: Int = 150)` with `fun format(): String`, and companion `DEFAULT_RADIUS_METERS = 150`, `MIN_RADIUS_METERS = 25`, `MAX_RADIUS_METERS = 5_000`, `fun clampRadius(meters: Int): Int`, `fun parse(raw: String?): SavedPlace?`.
  - `object Geo { fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double }`
  - `data class PhoneFix(latitude: Double, longitude: Double, accuracyMeters: Float?, timeMillis: Long)`
  - `interface PhoneLocationSource { suspend fun current(): PhoneFix? }`
  - `object PhonePresence { const val MAX_ACCURACY_METERS = 500f; fun isUsable(fix: PhoneFix): Boolean; fun evaluate(fix: PhoneFix?, place: SavedPlace?): Presence }`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/anchor/data/location/SavedPlaceTest.kt`:

```kotlin
package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedPlaceTest {

    @Test
    fun `format and parse round-trip, including negative coordinates`() {
        val place = SavedPlace(-33.8688, -151.2093, 75)
        assertThat(SavedPlace.parse(place.format())).isEqualTo(place)
    }

    @Test
    fun `parse rejects malformed input`() {
        assertThat(SavedPlace.parse(null)).isNull()
        assertThat(SavedPlace.parse("")).isNull()
        assertThat(SavedPlace.parse("1.0,2.0")).isNull()
        assertThat(SavedPlace.parse("a,b,c")).isNull()
        assertThat(SavedPlace.parse("1.0,2.0,wide")).isNull()
        assertThat(SavedPlace.parse("NaN,2.0,150")).isNull()
    }

    @Test
    fun `parse rejects coordinates off the globe`() {
        assertThat(SavedPlace.parse("91.0,0.0,150")).isNull()
        assertThat(SavedPlace.parse("0.0,181.0,150")).isNull()
    }

    @Test
    fun `parse clamps an out-of-range radius`() {
        assertThat(SavedPlace.parse("1.0,2.0,0")?.radiusMeters).isEqualTo(25)
        assertThat(SavedPlace.parse("1.0,2.0,99999")?.radiusMeters).isEqualTo(5_000)
    }

    @Test
    fun `clampRadius keeps the radius between 25 and 5000 metres`() {
        assertThat(SavedPlace.clampRadius(10)).isEqualTo(25)
        assertThat(SavedPlace.clampRadius(150)).isEqualTo(150)
        assertThat(SavedPlace.clampRadius(10_000)).isEqualTo(5_000)
    }
}
```

`app/src/test/java/com/anchor/data/location/GeoTest.kt`:

```kotlin
package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {

    @Test
    fun `the same point is zero metres away`() {
        assertThat(Geo.distanceMeters(37.7749, -122.4194, 37.7749, -122.4194)).isWithin(0.001).of(0.0)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertThat(Geo.distanceMeters(0.0, 0.0, 1.0, 0.0)).isWithin(111_195 * 0.005).of(111_195.0)
    }

    @Test
    fun `house-scale distances are accurate`() {
        // 0.001 degrees of latitude is about 111 m.
        assertThat(Geo.distanceMeters(37.7749, -122.4194, 37.7759, -122.4194)).isWithin(1.0).of(111.2)
    }

    @Test
    fun `long distances are within half a percent`() {
        // Big Ben to the Statue of Liberty, about 5575 km.
        val d = Geo.distanceMeters(51.5007, -0.1246, 40.6892, -74.0445)
        assertThat(d).isWithin(5_574_840 * 0.005).of(5_574_840.0)
    }
}
```

`app/src/test/java/com/anchor/data/location/PhonePresenceTest.kt`:

```kotlin
package com.anchor.data.location

import com.anchor.data.ha.Presence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhonePresenceTest {

    private val home = SavedPlace(37.7749, -122.4194, 150)

    private fun fix(lat: Double, lon: Double = -122.4194, accuracy: Float? = 20f) =
        PhoneFix(lat, lon, accuracy, timeMillis = 0L)

    @Test
    fun `at the place is in scope`() {
        assertThat(PhonePresence.evaluate(fix(37.7749), home)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `just inside the radius is in scope`() {
        // 0.00134 degrees of latitude is about 149 m.
        assertThat(PhonePresence.evaluate(fix(37.7749 + 0.00134), home)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `just outside the radius is out of scope`() {
        // 0.001358 degrees of latitude is about 151 m.
        assertThat(PhonePresence.evaluate(fix(37.7749 + 0.001358), home)).isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `no fix is unknown`() {
        assertThat(PhonePresence.evaluate(null, home)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `no place is unknown`() {
        assertThat(PhonePresence.evaluate(fix(37.7749), null)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `a fix worse than 500 m is unknown, even at the place`() {
        assertThat(PhonePresence.evaluate(fix(37.7749, accuracy = 600f), home)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `a fix with no reported accuracy is used`() {
        assertThat(PhonePresence.evaluate(fix(37.7749, accuracy = null), home)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `isUsable draws the line at 500 m`() {
        assertThat(PhonePresence.isUsable(fix(0.0, accuracy = 500f))).isTrue()
        assertThat(PhonePresence.isUsable(fix(0.0, accuracy = 500.1f))).isFalse()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.location.*' --no-daemon`
Expected: compilation FAILS with unresolved references `SavedPlace`, `Geo`, `PhoneFix`, `PhonePresence`.

- [ ] **Step 3: Write the implementation**

`app/src/main/java/com/anchor/data/location/SavedPlace.kt`:

```kotlin
package com.anchor.data.location

/**
 * A point on the map and how far from it still counts as "there". Persisted
 * as one "lat,lon,radius" string so a place is written and read atomically.
 */
data class SavedPlace(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    fun format(): String = "$latitude,$longitude,$radiusMeters"

    companion object {
        const val DEFAULT_RADIUS_METERS = 150
        const val MIN_RADIUS_METERS = 25
        const val MAX_RADIUS_METERS = 5_000

        fun clampRadius(meters: Int): Int = meters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)

        /** Null for anything malformed or off the globe; never throws. */
        fun parse(raw: String?): SavedPlace? {
            val parts = raw?.split(',')?.map { it.trim() } ?: return null
            if (parts.size != 3) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            val radius = parts[2].toIntOrNull() ?: return null
            // NaN fails both range checks, so it is rejected here too.
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return SavedPlace(lat, lon, clampRadius(radius))
        }
    }
}
```

`app/src/main/java/com/anchor/data/location/Geo.kt`:

```kotlin
package com.anchor.data.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Mean Earth radius (IUGG). */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * Great-circle distance by the haversine formula. Plain Kotlin rather
     * than Location.distanceBetween so it runs in a JVM unit test; at house
     * scale the spherical error is far below GPS noise.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
```

`app/src/main/java/com/anchor/data/location/PhoneLocationSource.kt`:

```kotlin
package com.anchor.data.location

/** One reading from the phone. [accuracyMeters] is null when the provider gave none. */
data class PhoneFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeMillis: Long,
)

/** The seam that keeps LocationManager out of every unit test. */
interface PhoneLocationSource {
    /** Never throws; null when there is no permission, no provider, or no fix in time. */
    suspend fun current(): PhoneFix?
}
```

`app/src/main/java/com/anchor/data/location/PhonePresence.kt`:

```kotlin
package com.anchor.data.location

import com.anchor.data.ha.Presence

/** The phone's answer to "are you at [SavedPlace]", in the same terms as LocationGate. */
object PhonePresence {

    /** Worse than this and the reading cannot tell the house from the next street. */
    const val MAX_ACCURACY_METERS = 500f

    fun isUsable(fix: PhoneFix): Boolean = (fix.accuracyMeters ?: 0f) <= MAX_ACCURACY_METERS

    /** Fail-open: no fix, no place, or a vague fix is UNKNOWN, never IN_SCOPE. */
    fun evaluate(fix: PhoneFix?, place: SavedPlace?): Presence {
        if (fix == null || place == null || !isUsable(fix)) return Presence.UNKNOWN
        val distance = Geo.distanceMeters(fix.latitude, fix.longitude, place.latitude, place.longitude)
        return if (distance <= place.radiusMeters) Presence.IN_SCOPE else Presence.OUT_OF_SCOPE
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.location.*' --no-daemon`
Expected: PASS (all tests in SavedPlaceTest, GeoTest, PhonePresenceTest).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anchor/data/location app/src/test/java/com/anchor/data/location
git commit -m "feat: add saved places, distance and phone presence"
```

---

### Task 2: Location settings and persistence

**Files:**
- Modify: `app/src/main/java/com/anchor/data/settings/AnchorSettings.kt`
- Modify: `app/src/main/java/com/anchor/data/settings/SettingsRepository.kt`
- Test: `app/src/test/java/com/anchor/data/settings/SettingsRepositoryTest.kt`
- Test (create): `app/src/test/java/com/anchor/data/settings/PresenceSourceTest.kt`

**Interfaces:**
- Consumes: `SavedPlace`, `SavedPlace.parse`, `SavedPlace.format` (Task 1).
- Produces (in `com.anchor.data.settings`):
  - `enum class LocationSource { HOME_ASSISTANT, PHONE }`
  - `enum class PresenceSource { HOME_ASSISTANT, PHONE_HOME, VACATION }`
  - `val DEFAULT_HOME_STATES: List<String> = listOf("home")`
  - `AnchorSettings` fields `locationSource: LocationSource`, `homePlace: SavedPlace?`, `vacationMode: Boolean`, `vacationPlace: SavedPlace?`, `morningHomeStates: List<String>`, `eveningHomeStates: List<String>`
  - `AnchorSettings.presenceSource: PresenceSource` (computed), `AnchorSettings.usesPhoneLocation: Boolean` (computed)

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/anchor/data/settings/PresenceSourceTest.kt`:

```kotlin
package com.anchor.data.settings

import com.anchor.data.location.SavedPlace
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresenceSourceTest {

    private val place = SavedPlace(1.0, 2.0)

    @Test
    fun `defaults to Home Assistant`() {
        assertThat(AnchorSettings().presenceSource).isEqualTo(PresenceSource.HOME_ASSISTANT)
    }

    @Test
    fun `phone source uses the home place`() {
        assertThat(AnchorSettings(locationSource = LocationSource.PHONE).presenceSource)
            .isEqualTo(PresenceSource.PHONE_HOME)
    }

    @Test
    fun `vacation with a place beats both other sources`() {
        val onHa = AnchorSettings(vacationMode = true, vacationPlace = place)
        val onPhone = onHa.copy(locationSource = LocationSource.PHONE)
        assertThat(onHa.presenceSource).isEqualTo(PresenceSource.VACATION)
        assertThat(onPhone.presenceSource).isEqualTo(PresenceSource.VACATION)
    }

    @Test
    fun `vacation without a place changes nothing`() {
        assertThat(AnchorSettings(vacationMode = true).presenceSource).isEqualTo(PresenceSource.HOME_ASSISTANT)
        assertThat(AnchorSettings(vacationMode = true, locationSource = LocationSource.PHONE).presenceSource)
            .isEqualTo(PresenceSource.PHONE_HOME)
    }

    @Test
    fun `phone location is needed for the phone source or vacation mode`() {
        assertThat(AnchorSettings().usesPhoneLocation).isFalse()
        assertThat(AnchorSettings(locationSource = LocationSource.PHONE).usesPhoneLocation).isTrue()
        assertThat(AnchorSettings(vacationMode = true).usesPhoneLocation).isTrue()
    }
}
```

Append to `SettingsRepositoryTest` (add imports `com.anchor.data.location.SavedPlace`):

```kotlin
    @Test
    fun `location settings have the documented defaults`() = runTest {
        val s = repo.current()
        assertThat(s.locationSource).isEqualTo(LocationSource.HOME_ASSISTANT)
        assertThat(s.homePlace).isNull()
        assertThat(s.vacationMode).isFalse()
        assertThat(s.vacationPlace).isNull()
        assertThat(s.morningHomeStates).containsExactly("home")
        assertThat(s.eveningHomeStates).containsExactly("home")
    }

    @Test
    fun `location settings round-trip`() = runTest {
        repo.update {
            it.copy(
                locationSource = LocationSource.PHONE,
                homePlace = SavedPlace(37.7749, -122.4194, 200),
                vacationMode = true,
                vacationPlace = SavedPlace(-33.8688, 151.2093, 75),
                morningHomeStates = listOf("home", "Bedroom"),
                eveningHomeStates = listOf("Office"),
            )
        }
        val s = repo.current()
        assertThat(s.locationSource).isEqualTo(LocationSource.PHONE)
        assertThat(s.homePlace).isEqualTo(SavedPlace(37.7749, -122.4194, 200))
        assertThat(s.vacationMode).isTrue()
        assertThat(s.vacationPlace).isEqualTo(SavedPlace(-33.8688, 151.2093, 75))
        assertThat(s.morningHomeStates).containsExactly("home", "Bedroom").inOrder()
        assertThat(s.eveningHomeStates).containsExactly("Office")
    }

    @Test
    fun `clearing a place removes it`() = runTest {
        repo.update { it.copy(homePlace = SavedPlace(1.0, 2.0)) }
        repo.update { it.copy(homePlace = null) }
        assertThat(repo.current().homePlace).isNull()
    }

    @Test
    fun `an emptied state list is stored as empty, not reset to the default`() = runTest {
        repo.update { it.copy(morningHomeStates = emptyList()) }
        assertThat(repo.current().morningHomeStates).isEmpty()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.settings.*' --no-daemon`
Expected: compilation FAILS with unresolved references `LocationSource`, `PresenceSource`, `homePlace`, etc.

- [ ] **Step 3: Write the implementation**

In `AnchorSettings.kt`, add the import `import com.anchor.data.location.SavedPlace`, and below `enum class LocationMode { AT_HOME, SPECIFIC_ROOMS }` add:

```kotlin
/** Where presence comes from when vacation mode is not deciding it. */
enum class LocationSource { HOME_ASSISTANT, PHONE }

/** Which reading actually decides presence right now (spec §1.1). */
enum class PresenceSource { HOME_ASSISTANT, PHONE_HOME, VACATION }

/** What "At Home" mode has always meant; also what an emptied list falls back to. */
val DEFAULT_HOME_STATES: List<String> = listOf("home")
```

In the data class, directly after `eveningAllowedRooms`, add:

```kotlin
    /** Entity states that count as in scope in AT_HOME mode, matched case-insensitively. */
    val morningHomeStates: List<String> = DEFAULT_HOME_STATES,
    val eveningHomeStates: List<String> = DEFAULT_HOME_STATES,

    // --- Phone location ---
    val locationSource: LocationSource = LocationSource.HOME_ASSISTANT,
    /** The geofence used when [locationSource] is PHONE. */
    val homePlace: SavedPlace? = null,
    /**
     * While on with a [vacationPlace], the phone's location against that
     * place decides presence and Home Assistant is not asked. On without a
     * place changes nothing.
     */
    val vacationMode: Boolean = false,
    val vacationPlace: SavedPlace? = null,
```

Replace the KDoc on `enforceWithoutHomeAssistant` with:

```kotlin
    /**
     * When the location cannot be read (Home Assistant not configured or
     * unreachable, or no usable phone fix), still ask the questions. Off by
     * default, which is the fail-open behaviour: no reading means no lock and
     * a 5s pause. A confirmed "away" reading always skips, whatever this says.
     * The name predates the phone source and is kept for the stored key.
     */
```

Next to `homeAssistantConfigured`, add:

```kotlin
    val presenceSource: PresenceSource
        get() = when {
            vacationMode && vacationPlace != null -> PresenceSource.VACATION
            locationSource == LocationSource.PHONE -> PresenceSource.PHONE_HOME
            else -> PresenceSource.HOME_ASSISTANT
        }

    /** The current setup reads the phone's location, so it needs the permission. */
    val usesPhoneLocation: Boolean
        get() = locationSource == LocationSource.PHONE || vacationMode
```

In `SettingsRepository.kt`, add the import `import com.anchor.data.location.SavedPlace`. Add to `Keys` after `EVENING_ROOMS`:

```kotlin
        val MORNING_HOME_STATES = stringPreferencesKey("morning_home_states")
        val EVENING_HOME_STATES = stringPreferencesKey("evening_home_states")
        val LOCATION_SOURCE = stringPreferencesKey("location_source")
        val HOME_PLACE = stringPreferencesKey("home_place")
        val VACATION_MODE = booleanPreferencesKey("vacation_mode")
        val VACATION_PLACE = stringPreferencesKey("vacation_place")
```

In `update`, after `prefs[Keys.EVENING_ROOMS] = ...`:

```kotlin
            prefs[Keys.MORNING_HOME_STATES] = formatRoomList(next.morningHomeStates)
            prefs[Keys.EVENING_HOME_STATES] = formatRoomList(next.eveningHomeStates)
            prefs[Keys.LOCATION_SOURCE] = next.locationSource.name
            val home = next.homePlace
            if (home == null) prefs.remove(Keys.HOME_PLACE) else prefs[Keys.HOME_PLACE] = home.format()
            prefs[Keys.VACATION_MODE] = next.vacationMode
            val vacation = next.vacationPlace
            if (vacation == null) prefs.remove(Keys.VACATION_PLACE) else prefs[Keys.VACATION_PLACE] = vacation.format()
```

In `toSettings()`, after `eveningAllowedRooms = ...`:

```kotlin
            // A missing key means "never edited" and gets the default; a
            // stored empty string is a deliberate empty list.
            morningHomeStates = this[Keys.MORNING_HOME_STATES]?.let(::parseRoomList) ?: d.morningHomeStates,
            eveningHomeStates = this[Keys.EVENING_HOME_STATES]?.let(::parseRoomList) ?: d.eveningHomeStates,
            locationSource = this[Keys.LOCATION_SOURCE]?.toLocationSource() ?: d.locationSource,
            homePlace = SavedPlace.parse(this[Keys.HOME_PLACE]),
            vacationMode = this[Keys.VACATION_MODE] ?: d.vacationMode,
            vacationPlace = SavedPlace.parse(this[Keys.VACATION_PLACE]),
```

Next to `toLocationMode()`:

```kotlin
    private fun String.toLocationSource(): LocationSource =
        runCatching { LocationSource.valueOf(this) }.getOrDefault(LocationSource.HOME_ASSISTANT)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.settings.*' --no-daemon`
Expected: PASS, including the pre-existing SettingsRepositoryTest cases.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anchor/data/settings app/src/test/java/com/anchor/data/settings
git commit -m "feat: persist location source, places, vacation mode and state lists"
```

---

### Task 3: State lists in LocationGate

**Files:**
- Modify: `app/src/main/java/com/anchor/data/ha/LocationGate.kt`
- Test: `app/src/test/java/com/anchor/data/ha/LocationGateTest.kt`

**Interfaces:**
- Consumes: `DEFAULT_HOME_STATES` (Task 2).
- Produces: `LocationGate.evaluate(result: HaResult, mode: LocationMode, allowedRooms: List<String>, homeStates: List<String> = DEFAULT_HOME_STATES): Presence`

- [ ] **Step 1: Write the failing tests**

Append to `LocationGateTest` (inside the class, after the existing AT_HOME tests):

```kotlin
    // --- AT_HOME with a state list ---

    @Test
    fun `any listed state is in scope`() {
        val states = listOf("home", "Bedroom", "Office")
        assertThat(LocationGate.evaluate(ok("Office"), LocationMode.AT_HOME, emptyList(), states))
            .isEqualTo(Presence.IN_SCOPE)
        assertThat(LocationGate.evaluate(ok("home"), LocationMode.AT_HOME, emptyList(), states))
            .isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `an unlisted state is out of scope`() {
        assertThat(LocationGate.evaluate(ok("Kitchen"), LocationMode.AT_HOME, emptyList(), listOf("Bedroom")))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `listing states replaces home rather than adding to it`() {
        assertThat(LocationGate.evaluate(ok("home"), LocationMode.AT_HOME, emptyList(), listOf("Bedroom")))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `state matching ignores case and surrounding spaces`() {
        assertThat(LocationGate.evaluate(ok(" bedroom "), LocationMode.AT_HOME, emptyList(), listOf("  BEDROOM")))
            .isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `state matching is exact, not a substring`() {
        assertThat(LocationGate.evaluate(ok("Bedroom 2"), LocationMode.AT_HOME, emptyList(), listOf("Bedroom")))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `an emptied or blank list still means home`() {
        assertThat(LocationGate.evaluate(ok("home"), LocationMode.AT_HOME, emptyList(), emptyList()))
            .isEqualTo(Presence.IN_SCOPE)
        assertThat(LocationGate.evaluate(ok("home"), LocationMode.AT_HOME, emptyList(), listOf(" ", "")))
            .isEqualTo(Presence.IN_SCOPE)
        assertThat(LocationGate.evaluate(ok("not_home"), LocationMode.AT_HOME, emptyList(), emptyList()))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `HA's don't-know states stay UNKNOWN even when listed`() {
        val states = listOf("unknown", "unavailable")
        assertThat(LocationGate.evaluate(ok("unknown"), LocationMode.AT_HOME, emptyList(), states))
            .isEqualTo(Presence.UNKNOWN)
        assertThat(LocationGate.evaluate(ok("unavailable"), LocationMode.AT_HOME, emptyList(), states))
            .isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `specific rooms mode ignores the state list`() {
        val result = LocationGate.evaluate(
            ok("home", "Kitchen Sensor"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom"),
            listOf("home"),
        )
        assertThat(result).isEqualTo(Presence.OUT_OF_SCOPE)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.ha.LocationGateTest' --no-daemon`
Expected: compilation FAILS: "Too many arguments for public final fun evaluate".

- [ ] **Step 3: Write the implementation**

In `LocationGate.kt`, add the import `import com.anchor.data.settings.DEFAULT_HOME_STATES`, change the signature and the AT_HOME branch:

```kotlin
    fun evaluate(
        result: HaResult,
        mode: LocationMode,
        allowedRooms: List<String>,
        homeStates: List<String> = DEFAULT_HOME_STATES,
    ): Presence {
```

```kotlin
            LocationMode.AT_HOME -> {
                // An emptied list means "home", never "nothing matches":
                // clearing the field must not quietly switch the gate off.
                val wanted = homeStates.map { it.trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { DEFAULT_HOME_STATES }
                val current = state.state.trim()
                if (wanted.any { it.equals(current, ignoreCase = true) }) Presence.IN_SCOPE
                else Presence.OUT_OF_SCOPE
            }
```

Update the `Presence` KDoc's first lines only if they mention "home" specifically; they do not, so leave them.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.data.ha.LocationGateTest' --no-daemon`
Expected: PASS (old and new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anchor/data/ha/LocationGate.kt app/src/test/java/com/anchor/data/ha/LocationGateTest.kt
git commit -m "feat: let At Home match a list of entity states"
```

---

### Task 4: PresenceResolver, the Android location source, and the permission

**Files:**
- Create: `app/src/main/java/com/anchor/domain/PresenceResolver.kt`
- Create: `app/src/main/java/com/anchor/data/location/AndroidPhoneLocationSource.kt`
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create (test helper): `app/src/test/java/com/anchor/data/location/FakePhoneLocationSource.kt`
- Test: `app/src/test/java/com/anchor/domain/PresenceResolverTest.kt`

**Interfaces:**
- Consumes: `PhoneLocationSource`, `PhoneFix`, `PhonePresence`, `SavedPlace` (Task 1); `AnchorSettings.presenceSource`, `PresenceSource`, `morningHomeStates`, `eveningHomeStates` (Task 2); `LocationGate.evaluate(..., homeStates)` (Task 3); existing `HomeAssistantClient.fetchDeviceTracker(settings)`, `com.anchor.data.db.Phase { MORNING, EVENING }`, test `FakeHaClient`.
- Produces:
  - `@Singleton class PresenceResolver @Inject constructor(client: HomeAssistantClient, phone: PhoneLocationSource)` with `suspend fun resolve(settings: AnchorSettings, phase: Phase): Presence`
  - `@Singleton class AndroidPhoneLocationSource @Inject constructor(@ApplicationContext context: Context, clock: Clock) : PhoneLocationSource`
  - Hilt binding `PhoneLocationSource` → `AndroidPhoneLocationSource`
  - Test helpers `class FakePhoneLocationSource(var fix: PhoneFix? = null) : PhoneLocationSource` with `var calls: Int`, and `fun fixAt(lat: Double, lon: Double, accuracy: Float? = 20f): PhoneFix`

- [ ] **Step 1: Write the test helper and the failing tests**

`app/src/test/java/com/anchor/data/location/FakePhoneLocationSource.kt`:

```kotlin
package com.anchor.data.location

/** A PhoneLocationSource that returns whatever [fix] holds and counts calls. */
class FakePhoneLocationSource(var fix: PhoneFix? = null) : PhoneLocationSource {
    var calls = 0
        private set

    override suspend fun current(): PhoneFix? {
        calls++
        return fix
    }
}

fun fixAt(lat: Double, lon: Double, accuracy: Float? = 20f) = PhoneFix(lat, lon, accuracy, timeMillis = 0L)
```

`app/src/test/java/com/anchor/domain/PresenceResolverTest.kt`:

```kotlin
package com.anchor.domain

import com.anchor.data.db.Phase
import com.anchor.data.ha.FakeHaClient
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.Presence
import com.anchor.data.location.FakePhoneLocationSource
import com.anchor.data.location.SavedPlace
import com.anchor.data.location.fixAt
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.LocationSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PresenceResolverTest {

    private val home = SavedPlace(37.7749, -122.4194, 150)
    private val cabin = SavedPlace(39.0968, -120.0324, 150)
    private val atHome = fixAt(37.7749, -122.4194)
    private val atCabin = fixAt(39.0968, -120.0324)

    private val ha = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
    )

    private fun haSays(state: String) = FakeHaClient(HaResult.Ok(HaStateDto("device_tracker.pixel", state)))

    @Test
    fun `Home Assistant source asks HA and never the phone`() = runTest {
        val client = haSays("home")
        val phone = FakePhoneLocationSource(atHome)
        val result = PresenceResolver(client, phone).resolve(ha, Phase.MORNING)
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
        assertThat(client.calls).containsExactly("device_tracker.pixel")
        assertThat(phone.calls).isEqualTo(0)
    }

    @Test
    fun `Home Assistant source uses the phase's state list`() = runTest {
        val settings = ha.copy(morningHomeStates = listOf("Bedroom"), eveningHomeStates = listOf("Office"))
        val resolver = PresenceResolver(haSays("Office"), FakePhoneLocationSource())
        assertThat(resolver.resolve(settings, Phase.MORNING)).isEqualTo(Presence.OUT_OF_SCOPE)
        assertThat(resolver.resolve(settings, Phase.EVENING)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `Home Assistant source uses the phase's mode and rooms`() = runTest {
        val settings = ha.copy(
            eveningLocationMode = LocationMode.SPECIFIC_ROOMS,
            eveningAllowedRooms = listOf("Bedroom"),
        )
        val resolver = PresenceResolver(haSays("Bedroom"), FakePhoneLocationSource())
        assertThat(resolver.resolve(settings, Phase.EVENING)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `phone source compares the phone with the home place and never asks HA`() = runTest {
        val client = haSays("not_home")
        val settings = ha.copy(locationSource = LocationSource.PHONE, homePlace = home)
        val inside = PresenceResolver(client, FakePhoneLocationSource(atHome)).resolve(settings, Phase.MORNING)
        val outside = PresenceResolver(client, FakePhoneLocationSource(atCabin)).resolve(settings, Phase.EVENING)
        assertThat(inside).isEqualTo(Presence.IN_SCOPE)
        assertThat(outside).isEqualTo(Presence.OUT_OF_SCOPE)
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `phone source with no home place is unknown and does not wake the GPS`() = runTest {
        val phone = FakePhoneLocationSource(atHome)
        val settings = AnchorSettings(locationSource = LocationSource.PHONE)
        assertThat(PresenceResolver(haSays("home"), phone).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.UNKNOWN)
        assertThat(phone.calls).isEqualTo(0)
    }

    @Test
    fun `phone source with no fix is unknown`() = runTest {
        val settings = AnchorSettings(locationSource = LocationSource.PHONE, homePlace = home)
        assertThat(PresenceResolver(haSays("home"), FakePhoneLocationSource(null)).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `vacation mode uses the vacation place, not home and not HA`() = runTest {
        val client = haSays("home")
        val settings = ha.copy(
            locationSource = LocationSource.PHONE,
            homePlace = home,
            vacationMode = true,
            vacationPlace = cabin,
        )
        assertThat(PresenceResolver(client, FakePhoneLocationSource(atCabin)).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.IN_SCOPE)
        assertThat(PresenceResolver(client, FakePhoneLocationSource(atHome)).resolve(settings, Phase.MORNING))
            .isEqualTo(Presence.OUT_OF_SCOPE)
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `vacation mode without a place falls through to Home Assistant`() = runTest {
        val client = haSays("home")
        val phone = FakePhoneLocationSource(atCabin)
        val settings = ha.copy(vacationMode = true)
        assertThat(PresenceResolver(client, phone).resolve(settings, Phase.MORNING)).isEqualTo(Presence.IN_SCOPE)
        assertThat(phone.calls).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.PresenceResolverTest' --no-daemon`
Expected: compilation FAILS: unresolved reference `PresenceResolver`.

- [ ] **Step 3: Write the resolver**

`app/src/main/java/com/anchor/domain/PresenceResolver.kt`:

```kotlin
package com.anchor.domain

import com.anchor.data.db.Phase
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.location.PhoneLocationSource
import com.anchor.data.location.PhonePresence
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.PresenceSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "are you somewhere the protocol applies" for every gate. Only the
 * one source that decides (see [AnchorSettings.presenceSource]) is asked, so
 * no decision waits on both the network and the GPS. Never throws: both
 * sources already collapse every failure to [Presence.UNKNOWN].
 */
@Singleton
class PresenceResolver @Inject constructor(
    private val client: HomeAssistantClient,
    private val phone: PhoneLocationSource,
) {
    suspend fun resolve(settings: AnchorSettings, phase: Phase): Presence =
        when (settings.presenceSource) {
            PresenceSource.VACATION -> phoneAt(settings.vacationPlace)
            PresenceSource.PHONE_HOME -> phoneAt(settings.homePlace)
            PresenceSource.HOME_ASSISTANT -> {
                val result = client.fetchDeviceTracker(settings)
                when (phase) {
                    Phase.MORNING -> LocationGate.evaluate(
                        result,
                        settings.morningLocationMode,
                        settings.morningAllowedRooms,
                        settings.morningHomeStates,
                    )
                    Phase.EVENING -> LocationGate.evaluate(
                        result,
                        settings.eveningLocationMode,
                        settings.eveningAllowedRooms,
                        settings.eveningHomeStates,
                    )
                }
            }
        }

    /** No place saved means there is nothing to compare with; skip the GPS entirely. */
    private suspend fun phoneAt(place: SavedPlace?): Presence =
        if (place == null) Presence.UNKNOWN else PhonePresence.evaluate(phone.current(), place)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.PresenceResolverTest' --no-daemon`
Expected: PASS (8 tests).

- [ ] **Step 5: Write the Android source, binding and permissions**

`app/src/main/java/com/anchor/data/location/AndroidPhoneLocationSource.kt`:

```kotlin
package com.anchor.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Platform LocationManager only, no Play Services, so it also works on a
 * de-Googled phone. A cached fix younger than [MAX_CACHED_AGE_MILLIS]
 * answers instantly; otherwise one fresh fix is requested and abandoned
 * after [FRESH_FIX_TIMEOUT_MILLIS], the same budget as a Home Assistant call.
 */
@Singleton
class AndroidPhoneLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : PhoneLocationSource {

    override suspend fun current(): PhoneFix? = try {
        if (!hasPermission()) null else read()?.toFix()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Same rule as the HA client: a location problem must never crash the
        // accessibility service, and every failure has the same safe answer.
        null
    }

    private fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission") // hasPermission() is checked before every read
    private suspend fun read(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = manager.getProviders(true)

        val cached = enabled.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (cached != null && clock.millis() - cached.time <= MAX_CACHED_AGE_MILLIS) return cached

        val provider = PROVIDER_PREFERENCE.firstOrNull { it in enabled } ?: return null
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    private fun Location.toFix() = PhoneFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        timeMillis = time,
    )

    companion object {
        const val MAX_CACHED_AGE_MILLIS = 10L * 60 * 1000
        const val FRESH_FIX_TIMEOUT_MILLIS = 5_000L

        /** Fused is quickest indoors; GPS last because it may never fix inside a house. */
        private val PROVIDER_PREFERENCE = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
```

In `AppModule.kt`, add imports `com.anchor.data.location.AndroidPhoneLocationSource` and `com.anchor.data.location.PhoneLocationSource`, and after `provideUsageStatsSource`:

```kotlin
    // --- Location ---

    @Provides
    @Singleton
    fun providePhoneLocationSource(impl: AndroidPhoneLocationSource): PhoneLocationSource = impl
```

In `AndroidManifest.xml`, after the `ACCESS_NETWORK_STATE` line:

```xml
    <!-- Phone location source and vacation mode. Background location is
         required: gates run from the accessibility service and the alarm. -->
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

- [ ] **Step 6: Verify it compiles, Hilt graph included**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anchor/domain/PresenceResolver.kt app/src/main/java/com/anchor/data/location/AndroidPhoneLocationSource.kt app/src/main/java/com/anchor/di/AppModule.kt app/src/main/AndroidManifest.xml app/src/test/java/com/anchor/data/location/FakePhoneLocationSource.kt app/src/test/java/com/anchor/domain/PresenceResolverTest.kt
git commit -m "feat: resolve presence from Home Assistant, the phone, or a vacation place"
```

---

### Task 5: Route the three gates through PresenceResolver

**Files:**
- Modify: `app/src/main/java/com/anchor/domain/MorningGate.kt`
- Modify: `app/src/main/java/com/anchor/domain/EveningGate.kt`
- Modify: `app/src/main/java/com/anchor/domain/EveningSitGate.kt`
- Test: `app/src/test/java/com/anchor/domain/MorningGateTest.kt`
- Test: `app/src/test/java/com/anchor/domain/EveningGateTest.kt`
- Test: `app/src/test/java/com/anchor/domain/EveningSitGateTest.kt`

**Interfaces:**
- Consumes: `PresenceResolver.resolve(settings, phase)` (Task 4), `FakePhoneLocationSource`, `fixAt` (Task 4), `LocationSource`, `SavedPlace`.
- Produces: each gate's constructor parameter `client: HomeAssistantClient` is replaced by `presence: PresenceResolver`. No other signature changes.

- [ ] **Step 1: Update the test helpers and add the failing tests**

In all three test files add imports:

```kotlin
import com.anchor.data.location.FakePhoneLocationSource
import com.anchor.data.location.SavedPlace
import com.anchor.data.location.fixAt
import com.anchor.data.settings.LocationSource
```

`MorningGateTest`: replace the `gate(...)` helper with:

```kotlin
    private fun gate(
        instant: Instant = morningInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
        phone: FakePhoneLocationSource = FakePhoneLocationSource(),
    ): Pair<MorningGate, FakeHaClient> {
        val client = FakeHaClient(haStates)
        val clock = Clock.fixed(instant, zone)
        val gate = MorningGate(
            settingsProvider = { settings },
            dailyLogDao = db.dailyLogDao(),
            presence = PresenceResolver(client, phone),
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
        )
        return gate to client
    }
```

and append:

```kotlin
    // --- Phone location and vacation mode ---

    private val home = SavedPlace(37.7749, -122.4194, 150)
    private val cabin = SavedPlace(39.0968, -120.0324, 150)

    @Test
    fun `phone source locks at the home place without asking HA for location`() = runTest {
        val (g, client) = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(fixAt(37.7749, -122.4194)),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `phone source skips away from the home place`() = runTest {
        val (g, _) = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(fixAt(39.0968, -120.0324)),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `FAIL-OPEN - phone source with no fix skips as unknown`() = runTest {
        val (g, _) = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(null),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN))
    }

    @Test
    fun `vacation mode locks at the vacation place even when HA says not_home`() = runTest {
        val (g, _) = gate(
            settings = configured().copy(vacationMode = true, vacationPlace = cabin),
            haStates = mapOf("device_tracker.pixel" to state("not_home")),
            phone = FakePhoneLocationSource(fixAt(39.0968, -120.0324)),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `the kill switch still applies in vacation mode`() = runTest {
        val settings = configured().copy(
            vacationMode = true,
            vacationPlace = cabin,
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, _) = gate(
            settings = settings,
            haStates = mapOf("input_boolean.anchor_override" to state("on")),
            phone = FakePhoneLocationSource(fixAt(39.0968, -120.0324)),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `a custom state list locks in a listed room`() = runTest {
        val (g, _) = gate(
            settings = configured().copy(morningHomeStates = listOf("Bedroom", "Office")),
            haStates = mapOf("device_tracker.pixel" to state("Office")),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }
```

`EveningGateTest`: replace the `gate(...)` helper with:

```kotlin
    private fun gate(
        instant: Instant = nightInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
        phone: FakePhoneLocationSource = FakePhoneLocationSource(),
    ): EveningGate {
        val client = FakeHaClient(haStates)
        val clock = Clock.fixed(instant, zone)
        return EveningGate(
            settingsProvider = { settings },
            dailyLogDao = db.dailyLogDao(),
            presence = PresenceResolver(client, phone),
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
        )
    }
```

and append:

```kotlin
    // --- Phone location ---

    private val home = SavedPlace(37.7749, -122.4194, 150)

    @Test
    fun `phone source is strict at the home place`() = runTest {
        val g = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(fixAt(37.7749, -122.4194)),
        )
        assertThat(g.decide(youtube)).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `phone source is a simple delay away from home`() = runTest {
        val g = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(fixAt(39.0968, -120.0324)),
        )
        assertThat(g.decide(youtube)).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `FAIL-OPEN - phone source with no fix is a simple delay`() = runTest {
        val g = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(null),
        )
        assertThat(g.decide(youtube)).isEqualTo(EveningDecision.SimpleDelay)
    }
```

`EveningSitGateTest`: replace the `gate(...)` helper with:

```kotlin
    private fun gate(
        instant: Instant = eveningInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
        phone: FakePhoneLocationSource = FakePhoneLocationSource(),
    ): EveningSitGate {
        val client = FakeHaClient(haStates)
        return EveningSitGate(
            settingsProvider = { settings },
            meditationDao = db.meditationSessionDao(),
            presence = PresenceResolver(client, phone),
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(Clock.fixed(instant, zone)),
        )
    }
```

and append:

```kotlin
    // --- Phone location ---

    private val home = SavedPlace(37.7749, -122.4194, 150)

    @Test
    fun `phone source holds the sit at the home place`() = runTest {
        val g = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(fixAt(37.7749, -122.4194)),
        )
        assertThat(g.decide()).isInstanceOf(SitDecision.Lock::class.java)
    }

    @Test
    fun `phone source skips the sit away from home`() = runTest {
        val g = gate(
            settings = configured().copy(locationSource = LocationSource.PHONE, homePlace = home),
            phone = FakePhoneLocationSource(fixAt(39.0968, -120.0324)),
        )
        assertThat(g.decide()).isEqualTo(SitDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }
```

If any of these test files' existing `configured()` helper does not already enable what the gate needs (the evening one must put `youtube` in `blockedPackages`, the sit one must set `eveningSitRequired = true`), it already does: the pre-existing "in scope" tests in each file rely on it. Do not change `configured()`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.domain.*Gate*' --no-daemon`
Expected: compilation FAILS: "No parameter with name 'presence' found".

- [ ] **Step 3: Change the gates**

In each of `MorningGate.kt`, `EveningGate.kt`, `EveningSitGate.kt`:

1. Remove the imports `com.anchor.data.ha.HomeAssistantClient` and `com.anchor.data.ha.LocationGate`; add `import com.anchor.data.db.Phase`. Keep `com.anchor.data.ha.Presence`.
2. Replace the constructor parameter `private val client: HomeAssistantClient,` with `private val presence: PresenceResolver,`.
3. Replace the `LocationGate.evaluate(...)` call.

`MorningGate.kt`, replace:

```kotlin
        // Fail-open: anything short of a confirmed in-scope reading skips.
        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.morningLocationMode,
            allowedRooms = settings.morningAllowedRooms,
        )
        return when (presence) {
```

with:

```kotlin
        // Fail-open: anything short of a confirmed in-scope reading skips.
        return when (presence.resolve(settings, Phase.MORNING)) {
```

`EveningGate.kt`, replace:

```kotlin
        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.eveningLocationMode,
            allowedRooms = settings.eveningAllowedRooms,
        )
        return when (presence) {
```

with:

```kotlin
        return when (presence.resolve(settings, Phase.EVENING)) {
```

`EveningSitGate.kt`, replace:

```kotlin
        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.eveningLocationMode,
            allowedRooms = settings.eveningAllowedRooms,
        )
        val lock = SitDecision.Lock(EveningSit.requiredSeconds(settings), sat)
        return when (presence) {
```

with:

```kotlin
        val lock = SitDecision.Lock(EveningSit.requiredSeconds(settings), sat)
        return when (presence.resolve(settings, Phase.EVENING)) {
```

Hilt needs no change: `PresenceResolver` has an `@Inject` constructor.

- [ ] **Step 4: Run the whole suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --no-daemon`
Expected: PASS, every pre-existing test included. If another test or production file constructs one of the three gates with `client =`, update it the same way (search: `grep -rn "client = client" app/src`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anchor/domain app/src/test/java/com/anchor/domain
git commit -m "feat: gates ask PresenceResolver, so phone and vacation locations apply"
```

---

### Task 6: Location permission on the dashboard, and the vacation banner

**Files:**
- Modify: `app/src/main/java/com/anchor/ui/home/PermissionState.kt`
- Modify: `app/src/main/java/com/anchor/ui/home/DashboardViewModel.kt`
- Modify: `app/src/main/java/com/anchor/ui/home/DashboardScreen.kt`
- Modify: `app/src/main/java/com/anchor/ui/MainActivity.kt`
- Test: `app/src/test/java/com/anchor/ui/home/PermissionStateTest.kt`

**Interfaces:**
- Consumes: `AnchorSettings.usesPhoneLocation`, `AnchorSettings.presenceSource`, `PresenceSource.VACATION` (Task 2).
- Produces:
  - `PermissionState(..., locationNeeded: Boolean = false, locationGranted: Boolean = true)`, constant `PermissionState.LOCATION = "Location (all the time)"`
  - `DashboardViewModel` constructor param `readPermissions: (AnchorSettings) -> PermissionState`
  - In `MainActivity`: `private fun granted(permission: String): Boolean`, `private fun hasForegroundLocation(): Boolean`, and file-level `private val FOREGROUND_LOCATION: Array<String>` (Task 8 reuses all three)

- [ ] **Step 1: Write the failing tests**

Append to `PermissionStateTest`:

```kotlin
    @Test
    fun `location is not listed when the setup does not use it`() {
        assertThat(all.copy(locationNeeded = false, locationGranted = false).missing)
            .doesNotContain(PermissionState.LOCATION)
    }

    @Test
    fun `location is listed when needed and not granted all the time`() {
        val state = all.copy(locationNeeded = true, locationGranted = false)
        assertThat(state.missing).contains("Location (all the time)")
        assertThat(state.isFullyConfigured).isFalse()
    }

    @Test
    fun `location does not gate basic enforcement`() {
        assertThat(all.copy(locationNeeded = true, locationGranted = false).canEnforce).isTrue()
    }

    @Test
    fun `location has a reason`() {
        assertThat(PermissionState.reason(PermissionState.LOCATION)).isNotEmpty()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.ui.home.PermissionStateTest' --no-daemon`
Expected: compilation FAILS: no parameter `locationNeeded`, unresolved `LOCATION`.

- [ ] **Step 3: Implement PermissionState**

Add to the constructor after `exportFolderChosen`:

```kotlin
    /** The setup reads the phone's location (phone source or vacation mode). */
    val locationNeeded: Boolean = false,
    /** "Allow all the time": gates run from the background. */
    val locationGranted: Boolean = true,
```

In `missing`, between NOTIFICATIONS and EXPORT_FOLDER:

```kotlin
            if (locationNeeded && !locationGranted) add(LOCATION)
```

In the companion:

```kotlin
        const val LOCATION = "Location (all the time)"
```

and in `reason`:

```kotlin
            LOCATION -> "Checks where you are from the background when the phone is the location source."
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.ui.home.PermissionStateTest' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Wire the dashboard**

`DashboardViewModel.kt`: change the constructor parameter to

```kotlin
    private val readPermissions: (AnchorSettings) -> PermissionState,
```

and in `refresh()` change `permissions = readPermissions(settings.exportTreeUri),` to `permissions = readPermissions(settings),`.

`DashboardScreen.kt`: add the import `com.anchor.data.settings.PresenceSource`; after the override banner line add

```kotlin
        if (state.settings.presenceSource == PresenceSource.VACATION) item(key = "vacation") { VacationBanner() }
```

and below `OverrideBanner`:

```kotlin
@Composable
private fun VacationBanner() {
    AnchorCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    ) {
        Eyebrow("Vacation mode", color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(6.dp))
        Text(
            "Home is your vacation place, checked with this phone's location. Turn it off in Settings when you're back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
```

`MainActivity.kt`:

1. Add imports `android.Manifest` and `com.anchor.data.settings.AnchorSettings`.
2. Below `DASHBOARD_TICK_MILLIS`:

```kotlin
private val FOREGROUND_LOCATION = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
```

3. In the `DashboardViewModel` factory: `readPermissions = { settings -> readPermissions(settings) },`
4. In `setContent`, after `notificationPermission`:

```kotlin
                // Android grants "all the time" only as a second step after
                // foreground location, and shows it as a settings page.
                val backgroundLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { dashboardViewModel.refresh() }

                val foregroundLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted.values.any { it }) {
                        backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        dashboardViewModel.refresh()
                    }
                }
```

5. In `onFixPermission`, add a branch:

```kotlin
                                    PermissionState.LOCATION ->
                                        if (hasForegroundLocation()) {
                                            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                        } else {
                                            foregroundLocationPermission.launch(FOREGROUND_LOCATION)
                                        }
```

6. Replace `readPermissions(exportTreeUri: String?)` with:

```kotlin
    private fun readPermissions(settings: AnchorSettings) = PermissionState(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        overlayGranted = Settings.canDrawOverlays(this),
        usageStatsGranted = hasUsageStatsPermission(),
        notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
        exportFolderChosen = settings.exportTreeUri != null && runCatching {
            contentResolver.persistedUriPermissions.any {
                it.uri.toString() == settings.exportTreeUri && it.isWritePermission
            }
        }.getOrDefault(false),
        locationNeeded = settings.usesPhoneLocation,
        locationGranted = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasForegroundLocation(): Boolean = FOREGROUND_LOCATION.any { granted(it) }
```

If `android.Manifest.permission.POST_NOTIFICATIONS` is referenced elsewhere in the file with the fully qualified name, leave those references as they are.

- [ ] **Step 6: Build and test**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anchor/ui/home app/src/main/java/com/anchor/ui/MainActivity.kt app/src/test/java/com/anchor/ui/home/PermissionStateTest.kt
git commit -m "feat: ask for background location when needed; show a vacation banner"
```

---

### Task 7: SettingsViewModel — capture places, radius, state lists

**Files:**
- Modify: `app/src/main/java/com/anchor/ui/settings/SettingsViewModel.kt`
- Test: `app/src/test/java/com/anchor/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `PhoneFix`, `PhonePresence.isUsable`, `SavedPlace` (Task 1); `homePlace`, `vacationPlace`, `morningHomeStates`, `eveningHomeStates` (Task 2); existing `parseRoomList`, `Phase`.
- Produces (in `com.anchor.ui.settings`):
  - `enum class PlaceTarget { HOME, VACATION }`
  - `SettingsViewModel` constructor param `currentFix: suspend () -> PhoneFix? = { null }` (last parameter)
  - `val placeMessage: StateFlow<String?>`
  - `fun capturePlace(target: PlaceTarget)`, `fun setPlaceRadius(target: PlaceTarget, raw: String)`, `fun reportLocationDenied()`, `fun setHomeStates(phase: Phase, raw: String)`
  - companion constants `CAPTURE_FAILED`, `PERMISSION_DENIED`

- [ ] **Step 1: Write the failing tests**

In `SettingsViewModelTest`, add imports `com.anchor.data.location.PhoneFix`, `com.anchor.data.location.SavedPlace`, and replace the `viewModel()` helper with:

```kotlin
    private fun viewModel(fix: PhoneFix? = null) = SettingsViewModel(
        settingsRepository = repo,
        questionDao = db.customQuestionDao(),
        appLimitDao = db.appLimitDao(),
        onScheduleChanged = { rescheduleCount++ },
        currentFix = { fix },
    )
```

Append:

```kotlin
    // --- Places ---

    private val here = PhoneFix(37.7749, -122.4194, accuracyMeters = 15f, timeMillis = 0L)

    @Test
    fun `capturing home saves the fix with the default radius`() = runTest {
        viewModel(here).capturePlace(PlaceTarget.HOME)
        awaitUntil { repo.current().homePlace != null }
        assertThat(repo.current().homePlace).isEqualTo(SavedPlace(37.7749, -122.4194, 150))
    }

    @Test
    fun `capturing again keeps the radius already chosen`() = runTest {
        repo.update { it.copy(vacationPlace = SavedPlace(0.0, 0.0, 400)) }
        viewModel(here).capturePlace(PlaceTarget.VACATION)
        awaitUntil { repo.current().vacationPlace?.latitude == 37.7749 }
        assertThat(repo.current().vacationPlace?.radiusMeters).isEqualTo(400)
    }

    @Test
    fun `no fix saves nothing and explains why`() = runTest {
        val vm = viewModel(null)
        vm.capturePlace(PlaceTarget.HOME)
        awaitUntil { vm.placeMessage.value != null }
        assertThat(vm.placeMessage.value).isEqualTo(SettingsViewModel.CAPTURE_FAILED)
        assertThat(repo.current().homePlace).isNull()
    }

    @Test
    fun `a vague fix is refused`() = runTest {
        val vm = viewModel(here.copy(accuracyMeters = 900f))
        vm.capturePlace(PlaceTarget.HOME)
        awaitUntil { vm.placeMessage.value != null }
        assertThat(repo.current().homePlace).isNull()
    }

    @Test
    fun `a successful capture clears an earlier message`() = runTest {
        val vm = viewModel(here)
        vm.reportLocationDenied()
        assertThat(vm.placeMessage.value).isEqualTo(SettingsViewModel.PERMISSION_DENIED)
        vm.capturePlace(PlaceTarget.HOME)
        awaitUntil { vm.placeMessage.value == null && repo.current().homePlace != null }
    }

    @Test
    fun `radius is clamped`() = runTest {
        repo.update { it.copy(homePlace = SavedPlace(1.0, 2.0, 150)) }
        val vm = viewModel()
        vm.setPlaceRadius(PlaceTarget.HOME, "0")
        awaitUntil { repo.current().homePlace?.radiusMeters == 25 }
        vm.setPlaceRadius(PlaceTarget.HOME, "99999")
        awaitUntil { repo.current().homePlace?.radiusMeters == 5_000 }
    }

    @Test
    fun `a radius that is not a whole number is ignored`() = runTest {
        repo.update { it.copy(homePlace = SavedPlace(1.0, 2.0, 150)) }
        val vm = viewModel()
        vm.setPlaceRadius(PlaceTarget.HOME, "wide")
        vm.setPlaceRadius(PlaceTarget.HOME, "")
        vm.setPlaceRadius(PlaceTarget.HOME, "300")
        awaitUntil { repo.current().homePlace?.radiusMeters == 300 }
    }

    @Test
    fun `a radius before any place is set does nothing`() = runTest {
        val vm = viewModel()
        vm.setPlaceRadius(PlaceTarget.VACATION, "300")
        vm.updateSettings { it.copy(joplinToken = "marker") }
        awaitUntil { repo.current().joplinToken == "marker" }
        assertThat(repo.current().vacationPlace).isNull()
    }

    // --- State lists ---

    @Test
    fun `state lists are parsed per phase`() = runTest {
        val vm = viewModel()
        vm.setHomeStates(Phase.MORNING, "home, Bedroom ,, Office")
        vm.setHomeStates(Phase.EVENING, "Living Room")
        awaitUntil { repo.current().eveningHomeStates == listOf("Living Room") }
        assertThat(repo.current().morningHomeStates).containsExactly("home", "Bedroom", "Office").inOrder()
    }
```

If `Phase` is not already imported in this test file, add `import com.anchor.data.db.Phase`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.ui.settings.SettingsViewModelTest' --no-daemon`
Expected: compilation FAILS: no parameter `currentFix`, unresolved `PlaceTarget`.

- [ ] **Step 3: Write the implementation**

In `SettingsViewModel.kt` add imports:

```kotlin
import com.anchor.data.location.PhoneFix
import com.anchor.data.location.PhonePresence
import com.anchor.data.location.SavedPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Above the class:

```kotlin
/** Which saved place a "Use current location" or radius edit is for. */
enum class PlaceTarget { HOME, VACATION }
```

Extend the KDoc's `@param` list and constructor:

```kotlin
 * @param currentFix reads the phone's location for "Use current location";
 *   a lambda for the same reason as [onScheduleChanged].
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val questionDao: CustomQuestionDao,
    private val appLimitDao: AppLimitDao,
    private val onScheduleChanged: () -> Unit,
    private val currentFix: suspend () -> PhoneFix? = { null },
) : ViewModel() {
```

After `setLocationMode`, add:

```kotlin
    fun setHomeStates(phase: Phase, raw: String) = updateSettings {
        val states = parseRoomList(raw)
        when (phase) {
            Phase.MORNING -> it.copy(morningHomeStates = states)
            Phase.EVENING -> it.copy(eveningHomeStates = states)
        }
    }

    // --- Places ---

    private val _placeMessage = MutableStateFlow<String?>(null)

    /** A one-line problem from the last "Use current location", or null. */
    val placeMessage: StateFlow<String?> = _placeMessage.asStateFlow()

    /**
     * Saves where the phone is now as [target], keeping any radius already
     * chosen. A missing or vague fix saves nothing: a place recorded from a
     * cell-tower guess would put "home" in the wrong street.
     */
    fun capturePlace(target: PlaceTarget) {
        viewModelScope.launch {
            val fix = currentFix()
            if (fix == null || !PhonePresence.isUsable(fix)) {
                _placeMessage.value = CAPTURE_FAILED
                return@launch
            }
            _placeMessage.value = null
            settingsRepository.update { s ->
                s.withPlace(target) { old ->
                    SavedPlace(fix.latitude, fix.longitude, old?.radiusMeters ?: SavedPlace.DEFAULT_RADIUS_METERS)
                }
            }
        }
    }

    /** Ignores anything but a whole number; clamps the rest. Needs a place to exist. */
    fun setPlaceRadius(target: PlaceTarget, raw: String) {
        val meters = raw.trim().toIntOrNull() ?: return
        updateSettings { s ->
            s.withPlace(target) { old -> old?.copy(radiusMeters = SavedPlace.clampRadius(meters)) }
        }
    }

    fun reportLocationDenied() {
        _placeMessage.value = PERMISSION_DENIED
    }

    private fun AnchorSettings.withPlace(
        target: PlaceTarget,
        change: (SavedPlace?) -> SavedPlace?,
    ): AnchorSettings = when (target) {
        PlaceTarget.HOME -> copy(homePlace = change(homePlace))
        PlaceTarget.VACATION -> copy(vacationPlace = change(vacationPlace))
    }
```

At the end of the class:

```kotlin
    companion object {
        const val CAPTURE_FAILED =
            "Couldn't get a precise enough location. Check that location is on, then try again."
        const val PERMISSION_DENIED = "Location permission is needed to save a place."
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest --tests 'com.anchor.ui.settings.SettingsViewModelTest' --no-daemon`
Expected: PASS (old and new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/anchor/ui/settings/SettingsViewModel.kt app/src/test/java/com/anchor/ui/settings/SettingsViewModelTest.kt
git commit -m "feat: settings can save places from the phone, set radii and state lists"
```

---

### Task 8: Settings UI — Location section and state-list fields

**Files:**
- Create: `app/src/main/java/com/anchor/ui/settings/sections/LocationSection.kt`
- Modify: `app/src/main/java/com/anchor/ui/settings/sections/HomeAssistantSection.kt`
- Modify: `app/src/main/java/com/anchor/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/anchor/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `PlaceTarget`, `placeMessage`, `capturePlace`, `setPlaceRadius`, `reportLocationDenied`, `setHomeStates`, `currentFix` (Task 7); `LocationSource`, `PresenceSource`, `presenceSource` (Task 2); `SavedPlace`; `PhoneLocationSource` (Task 4); `granted`, `hasForegroundLocation`, `FOREGROUND_LOCATION` (Task 6); existing `SettingsSection`, `TextSetting`, `RadioGroup`, `SettingRow`, `Hint`.
- Produces: `LocationSection(settings, placeMessage, onChange, onCapturePlace, onSetRadius)` composable; `SettingsScreen` gains param `onCapturePlace: (PlaceTarget) -> Unit`; `HomeAssistantSection` gains param `onSetHomeStates: (Phase, String) -> Unit`.

UI code has no unit tests in this project; this task is verified by building and by the manual checks in Task 9.

- [ ] **Step 1: Create the Location section**

`app/src/main/java/com/anchor/ui/settings/sections/LocationSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationSource
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SettingRow
import com.anchor.ui.settings.PlaceTarget
import java.util.Locale

@Composable
fun LocationSection(
    settings: AnchorSettings,
    placeMessage: String?,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
    onCapturePlace: (PlaceTarget) -> Unit,
    onSetRadius: (PlaceTarget, String) -> Unit,
) {
    SettingsSection(title = "Location") {
        RadioGroup(
            options = LocationSource.entries,
            selected = settings.locationSource,
            label = {
                when (it) {
                    LocationSource.HOME_ASSISTANT -> "Home Assistant"
                    LocationSource.PHONE -> "This phone"
                }
            },
            supporting = {
                when (it) {
                    LocationSource.HOME_ASSISTANT -> "Ask your device tracker, using the morning and evening settings below."
                    LocationSource.PHONE -> "Compare this phone's location with a saved home place. " +
                        "Needs location set to \"Allow all the time\"."
                }
            },
            onPick = { source -> onChange { it.copy(locationSource = source) } },
        )
        if (settings.locationSource == LocationSource.PHONE) {
            PlaceSetting("Home place", settings.homePlace, PlaceTarget.HOME, onCapturePlace, onSetRadius)
        }

        SettingRow(
            "Vacation mode",
            supporting = "Treat a second place as home, using this phone's location. " +
                "Home Assistant is not asked where you are while it is on.",
        ) {
            Switch(
                checked = settings.vacationMode,
                onCheckedChange = { on -> onChange { it.copy(vacationMode = on) } },
            )
        }
        if (settings.vacationMode) {
            PlaceSetting("Vacation place", settings.vacationPlace, PlaceTarget.VACATION, onCapturePlace, onSetRadius)
            if (settings.vacationPlace == null) Hint("Nothing changes until a vacation place is saved.")
        }

        placeMessage?.let { Hint(it, Modifier.padding(vertical = 6.dp)) }

        SettingRow(
            "Ask even when location is unknown",
            supporting = "Off: when your location cannot be read (Home Assistant down or not set up, " +
                "or no phone location), the morning lock is skipped and the evening shows a 5-second " +
                "pause. On: the questions are asked anyway. A confirmed \"away\" always skips.",
        ) {
            Switch(
                checked = settings.enforceWithoutHomeAssistant,
                onCheckedChange = { on -> onChange { it.copy(enforceWithoutHomeAssistant = on) } },
            )
        }
    }
}

@Composable
private fun PlaceSetting(
    label: String,
    place: SavedPlace?,
    target: PlaceTarget,
    onCapture: (PlaceTarget) -> Unit,
    onSetRadius: (PlaceTarget, String) -> Unit,
) {
    SettingRow(
        label,
        supporting = place?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) } ?: "Not set",
    ) {
        TextButton(onClick = { onCapture(target) }) { Text("Use current location") }
    }
    if (place != null) {
        TextSetting("Radius in metres (25–5000)", place.radiusMeters.toString(), "150") {
            onSetRadius(target, it)
        }
    }
}
```

- [ ] **Step 2: Update the Home Assistant section**

In `HomeAssistantSection.kt`:

1. Add imports `com.anchor.data.settings.PresenceSource`.
2. Add parameter `onSetHomeStates: (Phase, String) -> Unit,` after `onSetLocationMode`.
3. Delete the whole `SettingRow("Ask even without Home Assistant", ...) { Switch(...) }` block (it now lives in the Location section). Keep the `Switch` import: the kill switch still uses it.
4. Replace the "Morning location" and "Evening location" sections with:

```kotlin
    SettingsSection(title = "Morning location") {
        if (settings.presenceSource != PresenceSource.HOME_ASSISTANT) Hint(PHONE_SOURCE_HINT)
        LocationModePicker(settings.morningLocationMode) { onSetLocationMode(Phase.MORNING, it) }
        when (settings.morningLocationMode) {
            LocationMode.AT_HOME -> TextSetting(
                "States that count (comma-separated)",
                formatRoomList(settings.morningHomeStates),
                "home, Bedroom, Office",
            ) { onSetHomeStates(Phase.MORNING, it) }
            LocationMode.SPECIFIC_ROOMS -> {
                TextSetting(
                    "Allowed rooms (comma-separated)",
                    formatRoomList(settings.morningAllowedRooms),
                    "Bedroom, Office",
                ) { onSetRooms(Phase.MORNING, it) }
                Hint("Matched against the tracker's friendly name, case-insensitively.")
            }
        }
    }

    SettingsSection(title = "Evening location") {
        if (settings.presenceSource != PresenceSource.HOME_ASSISTANT) Hint(PHONE_SOURCE_HINT)
        LocationModePicker(settings.eveningLocationMode) { onSetLocationMode(Phase.EVENING, it) }
        when (settings.eveningLocationMode) {
            LocationMode.AT_HOME -> TextSetting(
                "States that count (comma-separated)",
                formatRoomList(settings.eveningHomeStates),
                "home, Bedroom, Living Room",
            ) { onSetHomeStates(Phase.EVENING, it) }
            LocationMode.SPECIFIC_ROOMS -> TextSetting(
                "Restricted rooms (comma-separated)",
                formatRoomList(settings.eveningAllowedRooms),
                "Bedroom, Living Room",
            ) { onSetRooms(Phase.EVENING, it) }
        }
    }
```

5. In `LocationModePicker`, change the AT_HOME label to `"Entity state"` and its supporting text to `"When the tracker's state is one of your states (\"home\" unless you change it)."`.
6. At the bottom of the file:

```kotlin
private const val PHONE_SOURCE_HINT = "Not used right now: your location comes from this phone."
```

- [ ] **Step 3: Wire SettingsScreen**

In `SettingsScreen.kt`:

1. Add imports `com.anchor.ui.settings.sections.LocationSection`.
2. Add parameter `onCapturePlace: (PlaceTarget) -> Unit,` after `onPickExportFolder`.
3. After `val limits by ...`: `val placeMessage by viewModel.placeMessage.collectAsState()`
4. Immediately before `item { Group("Home Assistant") }`:

```kotlin
            item { Group("Location") }
            item { LocationSection(
                settings = settings,
                placeMessage = placeMessage,
                onChange = viewModel::updateSettings,
                onCapturePlace = onCapturePlace,
                onSetRadius = viewModel::setPlaceRadius,
            ) }
```

5. In the `HomeAssistantSection(...)` call add `onSetHomeStates = viewModel::setHomeStates,`.

- [ ] **Step 4: Wire MainActivity**

1. Add imports `com.anchor.data.location.PhoneLocationSource` and `com.anchor.ui.settings.PlaceTarget`.
2. Add `@Inject lateinit var phoneLocation: PhoneLocationSource`.
3. Add a field: `/** The place waiting on a location permission answer; lost on recreation, which only means tapping again. */ private var pendingPlace: PlaceTarget? = null`
4. In the `SettingsViewModel` factory add `currentFix = { phoneLocation.current() },`.
5. In `setContent`, after `foregroundLocationPermission`:

```kotlin
                val placeLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    val target = pendingPlace ?: return@rememberLauncherForActivityResult
                    pendingPlace = null
                    if (granted.values.any { it }) settingsViewModel.capturePlace(target)
                    else settingsViewModel.reportLocationDenied()
                }
```

6. In the `SettingsScreen(...)` call add:

```kotlin
                            onCapturePlace = { target ->
                                if (hasForegroundLocation()) {
                                    settingsViewModel.capturePlace(target)
                                } else {
                                    pendingPlace = target
                                    placeLocationPermission.launch(FOREGROUND_LOCATION)
                                }
                            },
```

- [ ] **Step 5: Build and test**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Look at it (if an emulator is available)**

If `scripts/emulator.sh` can start an emulator, install the debug APK, open Settings, and check: the Location group appears above Home Assistant; choosing "This phone" shows Home place; Vacation mode shows Vacation place and the "Nothing changes…" hint; the Morning/Evening location sections show "States that count" under "Entity state" and the "Not used right now" hint while the phone is the source. If no emulator is available, say so in the hand-off rather than claiming it was checked.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/anchor/ui
git commit -m "feat: Location settings for phone source, vacation mode and state lists"
```

---

### Task 9: Documentation and final verification

**Files:**
- Modify: `README.md` (Home Assistant "Location gating" section and the fail-open table)
- Modify: `docs/manual-verification.md`

- [ ] **Step 1: README**

Replace the "**Location gating** decides…" paragraph and its two bullets with:

```markdown
**Location gating** decides whether the protocol applies where you are. Two modes:

- *Entity state* — the device tracker's state is one of your "States that count"
  (`home` unless you change it). List several, e.g. `home, Bedroom, Office`, for
  trackers whose state is a room or zone. Matching ignores case and must be exact.
- *Specific rooms* — the tracker's `friendly_name` (or its state) contains one of
  your room names, matched case-insensitively. Useful with per-room presence
  sensors: lock down in the bedroom, not in the kitchen.

### Phone location and vacation mode

Under **Settings → Location** you can skip Home Assistant for location entirely:

- **This phone** — save a home place with **Use current location** and set a
  radius (150 m by default, 25–5000 m). Inside the radius counts as home.
- **Vacation mode** — save a second place. While the switch is on, that place is
  home, checked with the phone's location, and Home Assistant is not asked where
  you are. Turn it on before saving a place and nothing changes.

Both need location set to **Allow all the time**, because the checks run in the
background; the dashboard lists it under Setup when it is missing. The kill switch
still comes from Home Assistant in every mode.
```

In the fail-open table, add rows after "Home Assistant not configured":

```markdown
| Phone location off, denied, or vaguer than 500 m | skip | 5-second pause | unaffected |
```

and rename the toggle sentence below it to: `The single toggle that changes this is **Ask even when location is unknown**.`

- [ ] **Step 2: Manual verification**

In `docs/manual-verification.md`, rename section `## 7c. Asking without Home Assistant` text to refer to "Ask even when location is unknown", and add before `## 8.`:

```markdown
## 7j. Phone location, state lists and vacation mode

1. In Settings → Location pick **This phone**, tap **Use current location** and
   grant location. Verify the coordinates appear and the dashboard Setup card lists
   "Location (all the time)"; grant it from there (Android opens a settings page).
2. With the HA URL cleared, trigger the morning check (section 4). Verify it locks.
   Set the radius to 25 and the home place to a spot over 1 km away
   (`adb emu geo fix <lon> <lat>` on an emulator), trigger again: verify it skips.
3. Turn device location off. Trigger again: verify it skips (unknown) and, with
   "Ask even when location is unknown" on, that it locks.
4. Switch back to Home Assistant. Set "States that count" to `home, Office` and set
   the tracker to `Office` in HA. Verify the morning lock fires; set it to `Kitchen`
   and verify it skips. Clear the field: verify `home` still locks.
5. Turn on **Vacation mode** without a place: verify the dashboard shows no banner
   and behaviour is unchanged. Save a vacation place: verify the "Vacation mode"
   banner appears, and the morning lock fires there while HA says `not_home`.
6. With vacation mode on, flip the HA kill switch: verify blocking is skipped.
```

- [ ] **Step 3: Full verification**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon`
Expected: BUILD SUCCESSFUL, all unit tests pass. (CI runs the same tasks.)

- [ ] **Step 4: Commit**

```bash
git add README.md docs/manual-verification.md
git commit -m "docs: phone location, state lists and vacation mode"
```
