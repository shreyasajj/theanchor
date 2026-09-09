# The Anchor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-hosted Android app that enforces a morning check-in lockdown and an evening app-blocking ritual, gated by Home Assistant location and a remote kill switch, exporting every check-in to Markdown.

**Architecture:** Single-module Android app, MVVM + Jetpack Compose, Hilt DI. All decision logic (time windows, location gating, kill switch, foreground-app decisions, Markdown rendering) lives in pure Kotlin classes that are unit-tested with JVM tests; Android framework classes (AccessibilityService, Activities, Services) are thin shells that delegate to those. Room stores daily logs and user-defined questions; DataStore stores settings. Enforcement sits behind a `LockdownEnforcer` interface so the accessibility-relaunch implementation can later be swapped for a Device Owner lock-task implementation.

**Tech Stack:** Kotlin 2.0, AGP 8.7, Jetpack Compose (BOM 2024.09), Hilt, Room, DataStore Preferences, Retrofit + kotlinx.serialization, OkHttp MockWebServer, JUnit4, Robolectric, Turbine, AccessibilityService, AlarmManager, WorkManager (not used — see Global Constraints), SAF (`DocumentFile`).

**Spec:** `AGENTS.md` (repo root)

## Global Constraints

- `minSdk = 33`, `targetSdk = 35`, `compileSdk = 35`. Personal sideloaded app; Play Store policy compliance is explicitly **not** a requirement, but we still avoid `MANAGE_EXTERNAL_STORAGE`.
- Package / namespace: `com.anchor`.
- Language: Kotlin only. UI: Jetpack Compose only — no XML layouts except `AndroidManifest.xml` and the accessibility-service config XML.
- Architecture: MVVM. ViewModels never touch Android framework classes directly; they depend on repositories/interfaces.
- **Fail-open is mandatory.** Any Home Assistant failure (network error, timeout, non-200, malformed body) MUST result in *less* blocking, never more: morning → skip the lockdown entirely; evening → 5-second simple delay instead of the strict overlay. This is a safety property — every HA call site needs a test that proves it.
- HA network timeouts: 3 seconds connect, 3 seconds read. A blocking decision must never hang the UI thread or leave the user staring at a spinner.
- Time is injected everywhere via a `Clock` (`java.time.Clock`). No call to `System.currentTimeMillis()`, `LocalDate.now()`, or `LocalTime.now()` outside of the Hilt module that provides the `Clock`. This is what makes the time-window logic testable.
- Dates are ISO `yyyy-MM-dd` strings in the system default zone.
- Export directory is a **SAF tree URI** obtained via `ACTION_OPEN_DOCUMENT_TREE` with persisted read/write permission. No `java.io.File` writes to shared storage.
- Every task ends with a commit. Commit messages use Conventional Commits (`feat:`, `test:`, `chore:`, `fix:`).
- Tests run with `./gradlew test` (JVM unit tests). Instrumented tests are **out of scope** — anything that would need a device is verified by a Robolectric test or by a manual verification step written into the task.

---

## File Structure

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/java/com/anchor/
  AnchorApp.kt                         @HiltAndroidApp
  di/AppModule.kt                      Clock, OkHttp, Retrofit, Room, DataStore providers
  di/EnforcerModule.kt                 binds LockdownEnforcer
  data/db/
    AnchorDatabase.kt                  Room database + migrations
    DailyLog.kt                        entity
    DailyLogDao.kt
    CustomQuestion.kt                  entity + Phase enum + SlotKey constants
    CustomQuestionDao.kt
    DefaultQuestions.kt                seed data
  data/settings/
    AnchorSettings.kt                  immutable settings snapshot data class
    SettingsRepository.kt              DataStore-backed read/write
  data/ha/
    HomeAssistantApi.kt                Retrofit interface (@Url based)
    HaStateDto.kt
    HomeAssistantClient.kt             URL/token assembly, error→Unknown mapping
    LocationGate.kt                    pure: LocationMode + friendly_name matching
    KillSwitch.kt                      pure-ish: entity state → Active/Inactive/Unknown
  data/export/
    MarkdownRenderer.kt                pure: DailyLog + questions → Markdown string
    MarkdownExporter.kt                SAF write/append
    JoplinApi.kt                       Retrofit interface
    JoplinExporter.kt                  push + silent fallback
    CheckInExporter.kt                 orchestrates local + Joplin
  domain/
    TimeWindow.kt                      pure: minute-of-day windows incl. midnight wrap
    AnchorDate.kt                      pure: which calendar day an evening belongs to
    MorningGate.kt                     should the morning lockdown fire?
    EveningGate.kt                     strict overlay vs simple delay vs no block?
    ForegroundAppDecider.kt            pure: package name → Action
    LockdownEnforcer.kt                interface
    AccessibilityLockdownEnforcer.kt   relaunch-based implementation
    SubmitCheckIn.kt                   use case: persist + export
  service/
    AnchorAccessibilityService.kt
    AnchorForegroundService.kt         persistent notification + override indicator
    MorningAlarmScheduler.kt
    MorningAlarmReceiver.kt
    BootReceiver.kt
  ui/
    MainActivity.kt                    dashboard + onboarding + nav host
    theme/Theme.kt, Color.kt, Type.kt
    lock/LockScreen.kt                 shared Compose lock UI
    lock/LockViewModel.kt
    lock/MorningLockActivity.kt
    lock/EveningLockActivity.kt
    lock/SimpleDelayActivity.kt        the 5-second fail-open path
    settings/SettingsScreen.kt         section list
    settings/SettingsViewModel.kt
    settings/sections/*.kt             one file per settings section
    home/DashboardScreen.kt, DashboardViewModel.kt
app/src/test/java/com/anchor/          JVM unit tests, mirroring the above
```

---

## Phase 0 — Scaffold

### Task 1: Gradle project scaffold with Hilt and a passing test

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/anchor/AnchorApp.kt`, `app/src/main/java/com/anchor/di/AppModule.kt`, `.gitignore`
- Test: `app/src/test/java/com/anchor/ScaffoldTest.kt`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `AnchorApp` (`@HiltAndroidApp`), `AppModule` with `@Provides fun provideClock(): Clock = Clock.systemDefaultZone()`. Every later task adds providers to `AppModule`.

- [ ] **Step 1: Initialize the git repository**

The project directory is not yet a git repo.

```bash
cd /Users/shre/Documents/projects/Anchor
git init
git add AGENTS.md docs/
git commit -m "chore: add spec and implementation plan"
```

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.25"
hilt = "2.52"
room = "2.6.1"
composeBom = "2024.09.03"
lifecycle = "2.8.6"
retrofit = "2.11.0"
okhttp = "4.12.0"
serialization = "1.7.3"
datastore = "1.1.1"
documentfile = "1.0.1"
robolectric = "4.13"
turbine = "1.1.0"
truth = "1.4.4"
coroutines = "1.9.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version = "1.9.2" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version = "2.8.2" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
documentfile = { module = "androidx.documentfile:documentfile", version.ref = "documentfile" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-serialization = { module = "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter", version = "1.0.0" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version = "4.13.2" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core-ktx", version = "1.6.1" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 3: Write the Gradle build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Anchor"
include(":app")
```

`build.gradle.kts` (root):

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.anchor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anchor"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // personal sideload; keep stack traces readable
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 4: Write the failing scaffold test**

`app/src/test/java/com/anchor/ScaffoldTest.kt`:

```kotlin
package com.anchor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ScaffoldTest {
    @Test
    fun `fixed clock is injectable and deterministic`() {
        val clock = Clock.fixed(Instant.parse("2026-09-09T07:30:00Z"), ZoneId.of("UTC"))
        assertThat(clock.instant().toString()).isEqualTo("2026-09-09T07:30:00Z")
    }
}
```

- [ ] **Step 5: Run the test to verify the toolchain fails for the right reason**

Run: `./gradlew :app:test --tests 'com.anchor.ScaffoldTest'`
Expected: FAIL — no Gradle wrapper yet (`./gradlew: No such file or directory`).

- [ ] **Step 6: Generate the Gradle wrapper**

```bash
gradle wrapper --gradle-version 8.10.2
```

If `gradle` is not on PATH: `brew install gradle`. Commit the wrapper jar and properties.

- [ ] **Step 7: Write `AnchorApp.kt` and `AppModule.kt`**

`app/src/main/java/com/anchor/AnchorApp.kt`:

```kotlin
package com.anchor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AnchorApp : Application()
```

`app/src/main/java/com/anchor/di/AppModule.kt`:

```kotlin
package com.anchor.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Central Hilt module. Later tasks add providers here for Room, DataStore,
 * Retrofit and the exporters.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The single source of "now" for the whole app. Every piece of time-window
     * logic takes this as a dependency so tests can pin it to a fixed instant.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
```

- [ ] **Step 8: Write the minimal `AndroidManifest.xml`**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".AnchorApp"
        android:allowBackup="false"
        android:label="The Anchor"
        android:supportsRtl="true"
        android:theme="@style/Theme.Anchor">
    </application>

</manifest>
```

Add `app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Anchor" parent="android:Theme.Material.NoActionBar" />
</resources>
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.ScaffoldTest'`
Expected: PASS (1 test).

- [ ] **Step 10: Verify the app assembles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "chore: scaffold Android project with Hilt, Compose and test toolchain"
```

---

## Phase 1 — Data Layer

> **Design note on the `DailyLog` schema.** The spec asks for columns
> `id, date, mission, avoiding, led, softened, faked`, *and* for fully
> user-customizable questions. Those pull in opposite directions. The
> resolution used throughout this plan: every question carries a stable
> `slotKey`. The five spec slots (`mission`, `avoiding`, `led`, `softened`,
> `faked`) map to the five real columns; any question the user adds gets a
> `custom:<uuid>` slot key and its answer lands in an `extraAnswersJson`
> map on the same row. The spec's columns exist literally, the Markdown
> template stays stable, and custom questions still round-trip.

### Task 2: `DailyLog` entity, DAO, and per-phase upsert

**Files:**
- Create: `app/src/main/java/com/anchor/data/db/DailyLog.kt`
- Create: `app/src/main/java/com/anchor/data/db/DailyLogDao.kt`
- Create: `app/src/main/java/com/anchor/data/db/AnchorDatabase.kt`
- Test: `app/src/test/java/com/anchor/data/db/DailyLogDaoTest.kt`

**Interfaces:**
- Consumes: `AppModule` (Task 1).
- Produces:
  - `data class DailyLog(id: Long, date: String, mission: String?, avoiding: String?, led: String?, softened: String?, faked: String?, extraAnswersJson: String?, morningCompletedAt: Long?, eveningCompletedAt: Long?)`
  - `interface DailyLogDao` with `suspend fun findByDate(date: String): DailyLog?`, `fun observeByDate(date: String): Flow<DailyLog?>`, `suspend fun upsert(log: DailyLog): Long`, `suspend fun recent(limit: Int): List<DailyLog>`
  - `abstract class AnchorDatabase : RoomDatabase()` exposing `dailyLogDao()`

- [ ] **Step 1: Write the failing DAO test**

`app/src/test/java/com/anchor/data/db/DailyLogDaoTest.kt`:

```kotlin
package com.anchor.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DailyLogDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: DailyLogDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dailyLogDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `returns null when no log exists for the date`() = runTest {
        assertThat(dao.findByDate("2026-09-09")).isNull()
    }

    @Test
    fun `stores and reads back a morning entry`() = runTest {
        dao.upsert(
            DailyLog(
                date = "2026-09-09",
                mission = "Ship the plan",
                avoiding = "The hard conversation",
                morningCompletedAt = 1_757_000_000_000L,
            )
        )

        val found = dao.findByDate("2026-09-09")!!
        assertThat(found.mission).isEqualTo("Ship the plan")
        assertThat(found.avoiding).isEqualTo("The hard conversation")
        assertThat(found.eveningCompletedAt).isNull()
    }

    @Test
    fun `upserting the evening keeps the morning answers on the same row`() = runTest {
        val id = dao.upsert(
            DailyLog(date = "2026-09-09", mission = "Ship", avoiding = "Email")
        )

        val existing = dao.findByDate("2026-09-09")!!
        dao.upsert(
            existing.copy(
                led = "Chose the architecture",
                softened = "Thanked a friend",
                faked = "Agreed to a meeting I did not want",
                eveningCompletedAt = 1_757_040_000_000L,
            )
        )

        val merged = dao.findByDate("2026-09-09")!!
        assertThat(merged.id).isEqualTo(id)
        assertThat(merged.mission).isEqualTo("Ship")
        assertThat(merged.led).isEqualTo("Chose the architecture")
        assertThat(dao.recent(10)).hasSize(1)
    }

    @Test
    fun `date is unique so two inserts for one day collapse to one row`() = runTest {
        dao.upsert(DailyLog(date = "2026-09-09", mission = "First"))
        dao.upsert(DailyLog(date = "2026-09-09", mission = "Second"))

        assertThat(dao.recent(10)).hasSize(1)
        assertThat(dao.findByDate("2026-09-09")!!.mission).isEqualTo("Second")
    }

    @Test
    fun `recent returns newest dates first`() = runTest {
        dao.upsert(DailyLog(date = "2026-09-07", mission = "A"))
        dao.upsert(DailyLog(date = "2026-09-09", mission = "C"))
        dao.upsert(DailyLog(date = "2026-09-08", mission = "B"))

        assertThat(dao.recent(2).map { it.date })
            .containsExactly("2026-09-09", "2026-09-08").inOrder()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.db.DailyLogDaoTest'`
Expected: FAIL — `Unresolved reference: AnchorDatabase` / `DailyLog`.

- [ ] **Step 3: Write `DailyLog.kt`**

```kotlin
package com.anchor.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per calendar day. The five named columns are the spec's default
 * question slots; anything the user adds themselves is serialised into
 * [extraAnswersJson] as a JSON object of slotKey -> answer.
 *
 * Morning and evening write to the same row: the evening check-in reads the
 * existing row, copies it with the evening fields filled in, and upserts.
 */
@Entity(
    tableName = "daily_log",
    indices = [Index(value = ["date"], unique = true)],
)
data class DailyLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO yyyy-MM-dd in the device's default zone. */
    val date: String,
    val mission: String? = null,
    val avoiding: String? = null,
    val led: String? = null,
    val softened: String? = null,
    val faked: String? = null,
    /** JSON object: {"custom:<uuid>": "answer"}. Null when there are none. */
    val extraAnswersJson: String? = null,
    val morningCompletedAt: Long? = null,
    val eveningCompletedAt: Long? = null,
)
```

- [ ] **Step 4: Write `DailyLogDao.kt`**

```kotlin
package com.anchor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyLogDao {

    @Query("SELECT * FROM daily_log WHERE date = :date LIMIT 1")
    suspend fun findByDate(date: String): DailyLog?

    @Query("SELECT * FROM daily_log WHERE date = :date LIMIT 1")
    fun observeByDate(date: String): Flow<DailyLog?>

    @Query("SELECT * FROM daily_log ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DailyLog>

    /**
     * REPLACE on the unique `date` index. Callers that want to preserve the
     * other half of the day MUST read the existing row and `copy()` it first —
     * see [DailyLogDaoTest] "upserting the evening keeps the morning answers".
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DailyLog): Long
}
```

- [ ] **Step 5: Write `AnchorDatabase.kt`**

```kotlin
package com.anchor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DailyLog::class],
    version = 1,
    exportSchema = false,
)
abstract class AnchorDatabase : RoomDatabase() {
    abstract fun dailyLogDao(): DailyLogDao
}
```

> `CustomQuestion` is added to `entities` in Task 3, bumping the version to 2.
> Since the app is unreleased at this point, Task 3 keeps version 1 and relies
> on a fresh install rather than writing a migration.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.db.DailyLogDaoTest'`
Expected: PASS (5 tests).

Note: the REPLACE strategy assigns a new rowid on conflict when `id = 0`. The
"keeps the morning answers" test passes because the caller copies the existing
row (including its `id`). If the test fails on the `merged.id` assertion,
that is the bug — do not weaken the assertion.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add DailyLog entity, DAO and Room database"
```

---

### Task 3: `CustomQuestion` entity, DAO, and default seeding

**Files:**
- Create: `app/src/main/java/com/anchor/data/db/CustomQuestion.kt`
- Create: `app/src/main/java/com/anchor/data/db/CustomQuestionDao.kt`
- Create: `app/src/main/java/com/anchor/data/db/DefaultQuestions.kt`
- Modify: `app/src/main/java/com/anchor/data/db/AnchorDatabase.kt` (add entity + DAO accessor)
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt` (provide database + DAOs, seed on create)
- Test: `app/src/test/java/com/anchor/data/db/CustomQuestionDaoTest.kt`

**Interfaces:**
- Consumes: `AnchorDatabase`, `DailyLog` (Task 2).
- Produces:
  - `enum class Phase { MORNING, EVENING }`
  - `object SlotKey { const val MISSION = "mission"; const val AVOIDING = "avoiding"; const val LED = "led"; const val SOFTENED = "softened"; const val FAKED = "faked"; fun custom(): String }`
  - `data class CustomQuestion(id: Long, phase: Phase, slotKey: String, prompt: String, sortOrder: Int, enabled: Boolean)`
  - `interface CustomQuestionDao` with `fun observe(phase: Phase): Flow<List<CustomQuestion>>`, `suspend fun list(phase: Phase): List<CustomQuestion>`, `suspend fun upsert(q: CustomQuestion): Long`, `suspend fun delete(q: CustomQuestion)`, `suspend fun count(): Int`
  - `object DefaultQuestions { val ALL: List<CustomQuestion> }`
  - Hilt providers: `AnchorDatabase`, `DailyLogDao`, `CustomQuestionDao`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/db/CustomQuestionDaoTest.kt`:

```kotlin
package com.anchor.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CustomQuestionDaoTest {

    private lateinit var db: AnchorDatabase
    private lateinit var dao: CustomQuestionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.customQuestionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `defaults contain the two morning and three evening questions`() {
        val morning = DefaultQuestions.ALL.filter { it.phase == Phase.MORNING }
        val evening = DefaultQuestions.ALL.filter { it.phase == Phase.EVENING }

        assertThat(morning.map { it.slotKey })
            .containsExactly(SlotKey.MISSION, SlotKey.AVOIDING).inOrder()
        assertThat(evening.map { it.slotKey })
            .containsExactly(SlotKey.LED, SlotKey.SOFTENED, SlotKey.FAKED).inOrder()
        assertThat(morning.first().prompt).isEqualTo("What is my mission today?")
    }

    @Test
    fun `lists only the requested phase, ordered by sortOrder`() = runTest {
        DefaultQuestions.ALL.forEach { dao.upsert(it) }

        assertThat(dao.list(Phase.MORNING).map { it.prompt })
            .containsExactly(
                "What is my mission today?",
                "What am I currently avoiding?",
            ).inOrder()
        assertThat(dao.list(Phase.EVENING)).hasSize(3)
    }

    @Test
    fun `disabled questions are excluded from list`() = runTest {
        DefaultQuestions.ALL.forEach { dao.upsert(it) }
        val first = dao.list(Phase.MORNING).first()
        dao.upsert(first.copy(enabled = false))

        assertThat(dao.list(Phase.MORNING).map { it.slotKey })
            .containsExactly(SlotKey.AVOIDING)
    }

    @Test
    fun `a user-added question gets a unique custom slot key`() = runTest {
        val a = SlotKey.custom()
        val b = SlotKey.custom()
        assertThat(a).startsWith("custom:")
        assertThat(a).isNotEqualTo(b)

        dao.upsert(
            CustomQuestion(
                phase = Phase.MORNING,
                slotKey = a,
                prompt = "Who do I owe a reply?",
                sortOrder = 99,
            )
        )
        assertThat(dao.list(Phase.MORNING).map { it.slotKey }).containsExactly(a)
    }

    @Test
    fun `deleting a question removes it`() = runTest {
        DefaultQuestions.ALL.forEach { dao.upsert(it) }
        dao.delete(dao.list(Phase.EVENING).first())
        assertThat(dao.list(Phase.EVENING)).hasSize(2)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.db.CustomQuestionDaoTest'`
Expected: FAIL — `Unresolved reference: CustomQuestionDao`.

- [ ] **Step 3: Write `CustomQuestion.kt`**

```kotlin
package com.anchor.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class Phase { MORNING, EVENING }

/**
 * Stable identifiers linking a question to where its answer is stored.
 * The five named slots map to real columns on [DailyLog]; everything else
 * is a `custom:<uuid>` key inside `DailyLog.extraAnswersJson`.
 */
object SlotKey {
    const val MISSION = "mission"
    const val AVOIDING = "avoiding"
    const val LED = "led"
    const val SOFTENED = "softened"
    const val FAKED = "faked"

    const val CUSTOM_PREFIX = "custom:"

    val NAMED = setOf(MISSION, AVOIDING, LED, SOFTENED, FAKED)

    fun custom(): String = CUSTOM_PREFIX + UUID.randomUUID()

    fun isCustom(key: String): Boolean = key.startsWith(CUSTOM_PREFIX)
}

/**
 * A question rendered on a lock screen. The user can add, edit, reorder,
 * disable and delete these from Settings without rebuilding the app.
 */
@Entity(tableName = "custom_question")
data class CustomQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phase: Phase,
    val slotKey: String,
    val prompt: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
)
```

- [ ] **Step 4: Write `CustomQuestionDao.kt`**

```kotlin
package com.anchor.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomQuestionDao {

    @Query("SELECT * FROM custom_question WHERE phase = :phase AND enabled = 1 ORDER BY sortOrder ASC")
    fun observe(phase: Phase): Flow<List<CustomQuestion>>

    @Query("SELECT * FROM custom_question WHERE phase = :phase AND enabled = 1 ORDER BY sortOrder ASC")
    suspend fun list(phase: Phase): List<CustomQuestion>

    /** Includes disabled rows — Settings needs to show and re-enable them. */
    @Query("SELECT * FROM custom_question WHERE phase = :phase ORDER BY sortOrder ASC")
    fun observeIncludingDisabled(phase: Phase): Flow<List<CustomQuestion>>

    @Query("SELECT COUNT(*) FROM custom_question")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(question: CustomQuestion): Long

    @Delete
    suspend fun delete(question: CustomQuestion)
}
```

- [ ] **Step 5: Write `DefaultQuestions.kt`**

```kotlin
package com.anchor.data.db

/**
 * Seeded once, on database creation. The user is free to edit or delete
 * any of these afterwards — they are defaults, not invariants.
 */
object DefaultQuestions {
    val ALL: List<CustomQuestion> = listOf(
        CustomQuestion(
            phase = Phase.MORNING,
            slotKey = SlotKey.MISSION,
            prompt = "What is my mission today?",
            sortOrder = 0,
        ),
        CustomQuestion(
            phase = Phase.MORNING,
            slotKey = SlotKey.AVOIDING,
            prompt = "What am I currently avoiding?",
            sortOrder = 1,
        ),
        CustomQuestion(
            phase = Phase.EVENING,
            slotKey = SlotKey.LED,
            prompt = "One moment I led: (What decision did I make without seeking approval?)",
            sortOrder = 0,
        ),
        CustomQuestion(
            phase = Phase.EVENING,
            slotKey = SlotKey.SOFTENED,
            prompt = "One moment I softened: (When did I express a feeling or show genuine appreciation?)",
            sortOrder = 1,
        ),
        CustomQuestion(
            phase = Phase.EVENING,
            slotKey = SlotKey.FAKED,
            prompt = "One moment I faked it: (When did I act to get approval rather than express truth?)",
            sortOrder = 2,
        ),
    )
}
```

- [ ] **Step 6: Add the entity and DAO accessor to `AnchorDatabase.kt`**

Replace the file with:

```kotlin
package com.anchor.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class PhaseConverter {
    @TypeConverter fun toPhase(value: String): Phase = Phase.valueOf(value)
    @TypeConverter fun fromPhase(phase: Phase): String = phase.name
}

@Database(
    entities = [DailyLog::class, CustomQuestion::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(PhaseConverter::class)
abstract class AnchorDatabase : RoomDatabase() {
    abstract fun dailyLogDao(): DailyLogDao
    abstract fun customQuestionDao(): CustomQuestionDao
}
```

- [ ] **Step 7: Add database providers and seeding to `AppModule.kt`**

Append inside `object AppModule`:

```kotlin
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AnchorDatabase =
        Room.databaseBuilder(context, AnchorDatabase::class.java, "anchor.db")
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Seed the default questions on first run. Written as raw
                    // SQL because the DAOs are not available inside onCreate.
                    DefaultQuestions.ALL.forEach { q ->
                        db.execSQL(
                            "INSERT INTO custom_question " +
                                "(phase, slotKey, prompt, sortOrder, enabled) " +
                                "VALUES (?, ?, ?, ?, 1)",
                            arrayOf(q.phase.name, q.slotKey, q.prompt, q.sortOrder),
                        )
                    }
                }
            })
            .build()

    @Provides fun provideDailyLogDao(db: AnchorDatabase): DailyLogDao = db.dailyLogDao()

    @Provides
    fun provideCustomQuestionDao(db: AnchorDatabase): CustomQuestionDao =
        db.customQuestionDao()
```

Add the imports: `android.content.Context`, `androidx.room.Room`, `androidx.room.RoomDatabase`, `androidx.sqlite.db.SupportSQLiteDatabase`, `dagger.hilt.android.qualifiers.ApplicationContext`, `com.anchor.data.db.*`.

- [ ] **Step 8: Write a test proving the seeding callback runs**

Append to `CustomQuestionDaoTest.kt`:

```kotlin
    @Test
    fun `an on-disk database seeds the five default questions on create`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("seed-test.db")
        val seeded = androidx.room.Room
            .databaseBuilder(context, AnchorDatabase::class.java, "seed-test.db")
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    DefaultQuestions.ALL.forEach { q ->
                        db.execSQL(
                            "INSERT INTO custom_question " +
                                "(phase, slotKey, prompt, sortOrder, enabled) " +
                                "VALUES (?, ?, ?, ?, 1)",
                            arrayOf(q.phase.name, q.slotKey, q.prompt, q.sortOrder),
                        )
                    }
                }
            })
            .allowMainThreadQueries()
            .build()

        assertThat(seeded.customQuestionDao().count()).isEqualTo(5)
        seeded.close()
        context.deleteDatabase("seed-test.db")
    }
```

> This duplicates the callback body rather than reaching into Hilt, which
> keeps the test a plain JVM test. If the callback in `AppModule` changes,
> update both. (Extracting the callback into a named object shared by both
> is a fine refactor if you prefer — do it in this step if so.)

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.anchor.data.db.*'`
Expected: PASS (11 tests total across both DAO test classes).

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add customizable questions table with default seeding"
```

---

### Task 4: `SettingsRepository` over DataStore

**Files:**
- Create: `app/src/main/java/com/anchor/data/settings/AnchorSettings.kt`
- Create: `app/src/main/java/com/anchor/data/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt` (provide `DataStore<Preferences>`)
- Test: `app/src/test/java/com/anchor/data/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `AppModule` (Task 1).
- Produces:
  - `enum class LocationMode { AT_HOME, SPECIFIC_ROOMS }`
  - `data class AnchorSettings(...)` — full field list below, with `DEFAULT` companion constant.
  - `class SettingsRepository(dataStore)` with `val settings: Flow<AnchorSettings>`, `suspend fun current(): AnchorSettings`, and one `suspend fun update(transform: (AnchorSettings) -> AnchorSettings)` mutator.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/settings/SettingsRepositoryTest.kt`:

```kotlin
package com.anchor.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        file = File(context.cacheDir, "settings-test-${System.nanoTime()}.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = TestScope()) { file }
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() { file.delete() }

    @Test
    fun `defaults match the spec`() = runTest {
        val s = repo.current()

        assertThat(s.morningStartMinute).isEqualTo(5 * 60)    // 05:00
        assertThat(s.morningEndMinute).isEqualTo(12 * 60)     // 12:00
        assertThat(s.eveningStartMinute).isEqualTo(20 * 60)   // 20:00
        assertThat(s.eveningEndMinute).isEqualTo(5 * 60)      // 05:00 next day
        assertThat(s.morningLocationMode).isEqualTo(LocationMode.AT_HOME)
        assertThat(s.eveningLocationMode).isEqualTo(LocationMode.AT_HOME)
        assertThat(s.killSwitchEnabled).isFalse()
        assertThat(s.killSwitchOverrideState).isEqualTo("on")
        assertThat(s.blockedPackages).isEmpty()
        assertThat(s.exportTreeUri).isNull()
    }

    @Test
    fun `round-trips a full configuration`() = runTest {
        repo.update {
            it.copy(
                haBaseUrl = "http://192.168.1.10:8123",
                haToken = "llat-secret",
                haDeviceTrackerEntityId = "device_tracker.pixel",
                morningLocationMode = LocationMode.SPECIFIC_ROOMS,
                morningAllowedRooms = listOf("Bedroom", "Office"),
                eveningAllowedRooms = listOf("Bedroom", "Living Room"),
                killSwitchEnabled = true,
                killSwitchEntityId = "input_boolean.anchor_override",
                killSwitchOverrideState = "off",
                blockedPackages = setOf("com.google.android.youtube", "com.instagram.android"),
                allowlistPackages = setOf("com.android.dialer"),
                exportTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                joplinBaseUrl = "http://192.168.1.10:41184",
                joplinToken = "joplin-token",
            )
        }

        val s = repo.current()
        assertThat(s.haBaseUrl).isEqualTo("http://192.168.1.10:8123")
        assertThat(s.morningLocationMode).isEqualTo(LocationMode.SPECIFIC_ROOMS)
        assertThat(s.morningAllowedRooms).containsExactly("Bedroom", "Office").inOrder()
        assertThat(s.killSwitchOverrideState).isEqualTo("off")
        assertThat(s.blockedPackages).hasSize(2)
        assertThat(s.joplinToken).isEqualTo("joplin-token")
    }

    @Test
    fun `settings flow emits the new value after an update`() = runTest {
        repo.settings.test {
            assertThat(awaitItem().haToken).isEmpty()
            repo.update { it.copy(haToken = "abc") }
            assertThat(awaitItem().haToken).isEqualTo("abc")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `room lists are trimmed and blank entries dropped`() = runTest {
        repo.update { it.copy(morningAllowedRooms = parseRoomList(" Bedroom ,, Office ,")) }
        assertThat(repo.current().morningAllowedRooms)
            .containsExactly("Bedroom", "Office").inOrder()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.settings.SettingsRepositoryTest'`
Expected: FAIL — `Unresolved reference: SettingsRepository`.

- [ ] **Step 3: Write `AnchorSettings.kt`**

```kotlin
package com.anchor.data.settings

enum class LocationMode { AT_HOME, SPECIFIC_ROOMS }

/**
 * An immutable snapshot of every user-configurable value. Read it once at
 * the moment of a blocking decision — never cache it across decisions, since
 * the kill switch and the schedule can change between them.
 */
data class AnchorSettings(
    // --- Schedule (minutes since midnight, local time) ---
    val morningStartMinute: Int = 5 * 60,
    val morningEndMinute: Int = 12 * 60,
    val eveningStartMinute: Int = 20 * 60,
    val eveningEndMinute: Int = 5 * 60,

    // --- App blocking ---
    val blockedPackages: Set<String> = emptySet(),
    val allowlistPackages: Set<String> = emptySet(),

    // --- Home Assistant ---
    val haBaseUrl: String = "",
    val haToken: String = "",
    val haDeviceTrackerEntityId: String = "",

    val morningLocationMode: LocationMode = LocationMode.AT_HOME,
    val morningAllowedRooms: List<String> = emptyList(),
    val eveningLocationMode: LocationMode = LocationMode.AT_HOME,
    val eveningAllowedRooms: List<String> = emptyList(),

    // --- Remote kill switch ---
    val killSwitchEnabled: Boolean = false,
    val killSwitchEntityId: String = "",
    /** The state value that DISABLES blocking. */
    val killSwitchOverrideState: String = "on",

    // --- Export ---
    /** Persisted SAF tree URI, or null until the user picks a folder. */
    val exportTreeUri: String? = null,
    val joplinBaseUrl: String = "",
    val joplinToken: String = "",
) {
    /** True when there is enough configuration to attempt an HA call at all. */
    val homeAssistantConfigured: Boolean
        get() = haBaseUrl.isNotBlank() && haToken.isNotBlank() &&
            haDeviceTrackerEntityId.isNotBlank()
}

/** Splits a comma-separated room list, trimming and dropping blanks. */
fun parseRoomList(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Renders a room list back into the comma-separated form shown in Settings. */
fun formatRoomList(rooms: List<String>): String = rooms.joinToString(", ")
```

- [ ] **Step 4: Write `SettingsRepository.kt`**

```kotlin
package com.anchor.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val MORNING_START = intPreferencesKey("morning_start")
        val MORNING_END = intPreferencesKey("morning_end")
        val EVENING_START = intPreferencesKey("evening_start")
        val EVENING_END = intPreferencesKey("evening_end")
        val BLOCKED = stringSetPreferencesKey("blocked_packages")
        val ALLOWLIST = stringSetPreferencesKey("allowlist_packages")
        val HA_URL = stringPreferencesKey("ha_url")
        val HA_TOKEN = stringPreferencesKey("ha_token")
        val HA_ENTITY = stringPreferencesKey("ha_entity")
        val MORNING_MODE = stringPreferencesKey("morning_mode")
        val MORNING_ROOMS = stringPreferencesKey("morning_rooms")
        val EVENING_MODE = stringPreferencesKey("evening_mode")
        val EVENING_ROOMS = stringPreferencesKey("evening_rooms")
        val KILL_ENABLED = booleanPreferencesKey("kill_enabled")
        val KILL_ENTITY = stringPreferencesKey("kill_entity")
        val KILL_STATE = stringPreferencesKey("kill_state")
        val EXPORT_TREE = stringPreferencesKey("export_tree_uri")
        val JOPLIN_URL = stringPreferencesKey("joplin_url")
        val JOPLIN_TOKEN = stringPreferencesKey("joplin_token")
    }

    val settings: Flow<AnchorSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AnchorSettings = settings.first()

    /** Read-modify-write; the transform sees the current snapshot. */
    suspend fun update(transform: (AnchorSettings) -> AnchorSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.MORNING_START] = next.morningStartMinute
            prefs[Keys.MORNING_END] = next.morningEndMinute
            prefs[Keys.EVENING_START] = next.eveningStartMinute
            prefs[Keys.EVENING_END] = next.eveningEndMinute
            prefs[Keys.BLOCKED] = next.blockedPackages
            prefs[Keys.ALLOWLIST] = next.allowlistPackages
            prefs[Keys.HA_URL] = next.haBaseUrl
            prefs[Keys.HA_TOKEN] = next.haToken
            prefs[Keys.HA_ENTITY] = next.haDeviceTrackerEntityId
            prefs[Keys.MORNING_MODE] = next.morningLocationMode.name
            prefs[Keys.MORNING_ROOMS] = formatRoomList(next.morningAllowedRooms)
            prefs[Keys.EVENING_MODE] = next.eveningLocationMode.name
            prefs[Keys.EVENING_ROOMS] = formatRoomList(next.eveningAllowedRooms)
            prefs[Keys.KILL_ENABLED] = next.killSwitchEnabled
            prefs[Keys.KILL_ENTITY] = next.killSwitchEntityId
            prefs[Keys.KILL_STATE] = next.killSwitchOverrideState
            prefs[Keys.JOPLIN_URL] = next.joplinBaseUrl
            prefs[Keys.JOPLIN_TOKEN] = next.joplinToken
            val tree = next.exportTreeUri
            if (tree == null) prefs.remove(Keys.EXPORT_TREE) else prefs[Keys.EXPORT_TREE] = tree
        }
    }

    private fun Preferences.toSettings(): AnchorSettings {
        val d = AnchorSettings()
        return AnchorSettings(
            morningStartMinute = this[Keys.MORNING_START] ?: d.morningStartMinute,
            morningEndMinute = this[Keys.MORNING_END] ?: d.morningEndMinute,
            eveningStartMinute = this[Keys.EVENING_START] ?: d.eveningStartMinute,
            eveningEndMinute = this[Keys.EVENING_END] ?: d.eveningEndMinute,
            blockedPackages = this[Keys.BLOCKED] ?: d.blockedPackages,
            allowlistPackages = this[Keys.ALLOWLIST] ?: d.allowlistPackages,
            haBaseUrl = this[Keys.HA_URL] ?: d.haBaseUrl,
            haToken = this[Keys.HA_TOKEN] ?: d.haToken,
            haDeviceTrackerEntityId = this[Keys.HA_ENTITY] ?: d.haDeviceTrackerEntityId,
            morningLocationMode = this[Keys.MORNING_MODE]?.toLocationMode() ?: d.morningLocationMode,
            morningAllowedRooms = parseRoomList(this[Keys.MORNING_ROOMS] ?: ""),
            eveningLocationMode = this[Keys.EVENING_MODE]?.toLocationMode() ?: d.eveningLocationMode,
            eveningAllowedRooms = parseRoomList(this[Keys.EVENING_ROOMS] ?: ""),
            killSwitchEnabled = this[Keys.KILL_ENABLED] ?: d.killSwitchEnabled,
            killSwitchEntityId = this[Keys.KILL_ENTITY] ?: d.killSwitchEntityId,
            killSwitchOverrideState = this[Keys.KILL_STATE] ?: d.killSwitchOverrideState,
            exportTreeUri = this[Keys.EXPORT_TREE],
            joplinBaseUrl = this[Keys.JOPLIN_URL] ?: d.joplinBaseUrl,
            joplinToken = this[Keys.JOPLIN_TOKEN] ?: d.joplinToken,
        )
    }

    private fun String.toLocationMode(): LocationMode =
        runCatching { LocationMode.valueOf(this) }.getOrDefault(LocationMode.AT_HOME)
}
```

- [ ] **Step 5: Provide the DataStore in `AppModule.kt`**

Append inside `object AppModule`:

```kotlin
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("anchor_settings") },
    )
```

Imports: `androidx.datastore.core.DataStore`, `androidx.datastore.preferences.core.PreferenceDataStoreFactory`, `androidx.datastore.preferences.core.Preferences`, `androidx.datastore.preferences.preferencesDataStoreFile`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.SupervisorJob`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.settings.SettingsRepositoryTest'`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add DataStore-backed settings repository"
```

---

## Phase 2 — Home Assistant Integration

### Task 5: Home Assistant REST client with fail-open error mapping

**Files:**
- Create: `app/src/main/java/com/anchor/data/ha/HaStateDto.kt`
- Create: `app/src/main/java/com/anchor/data/ha/HomeAssistantApi.kt`
- Create: `app/src/main/java/com/anchor/data/ha/HomeAssistantClient.kt`
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt` (OkHttp + Retrofit + Json providers)
- Test: `app/src/test/java/com/anchor/data/ha/HomeAssistantClientTest.kt`

**Interfaces:**
- Consumes: `AnchorSettings`, `SettingsRepository` (Task 4).
- Produces:
  - `data class HaStateDto(entityId: String, state: String, attributes: Map<String, JsonElement>)` with `val friendlyName: String?`
  - `interface HomeAssistantApi { suspend fun state(@Url url: String, @Header("Authorization") auth: String): HaStateDto }`
  - `sealed interface HaResult { data class Ok(val state: HaStateDto) : HaResult; data object Unavailable : HaResult }`
  - `class HomeAssistantClient` with `suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult` and `suspend fun fetchDeviceTracker(settings: AnchorSettings): HaResult`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/ha/HomeAssistantClientTest.kt`:

```kotlin
package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HomeAssistantClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HomeAssistantClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HomeAssistantClient(
            api = buildApi(server),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun buildApi(server: MockWebServer): HomeAssistantApi {
        val ok = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }
        return retrofit2.Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(
                json.asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(HomeAssistantApi::class.java)
    }

    private fun settings() = AnchorSettings(
        haBaseUrl = server.url("/").toString().trimEnd('/'),
        haToken = "llat-secret",
        haDeviceTrackerEntityId = "device_tracker.pixel",
    )

    @Test
    fun `parses state and friendly_name`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"entity_id":"device_tracker.pixel","state":"home",
                 "attributes":{"friendly_name":"Bedroom Presence","source_type":"router"}}
                """.trimIndent()
            )
        )

        val result = client.fetchDeviceTracker(settings())

        assertThat(result).isInstanceOf(HaResult.Ok::class.java)
        val ok = result as HaResult.Ok
        assertThat(ok.state.state).isEqualTo("home")
        assertThat(ok.state.friendlyName).isEqualTo("Bedroom Presence")
    }

    @Test
    fun `sends the bearer token and hits the states endpoint`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entity_id":"device_tracker.pixel","state":"home","attributes":{}}"""
            )
        )

        client.fetchDeviceTracker(settings())

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/states/device_tracker.pixel")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer llat-secret")
    }

    @Test
    fun `a 401 maps to Unavailable, not an exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `a 500 maps to Unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `malformed json maps to Unavailable`() = runTest {
        server.enqueue(MockResponse().setBody("not json at all"))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `a network failure maps to Unavailable`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `unconfigured Home Assistant maps to Unavailable without a request`() = runTest {
        val result = client.fetchDeviceTracker(AnchorSettings())
        assertThat(result).isEqualTo(HaResult.Unavailable)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a trailing slash on the base url does not produce a double slash`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"entity_id":"device_tracker.pixel","state":"home","attributes":{}}"""
            )
        )

        client.fetchState(
            baseUrl = server.url("/").toString(),   // ends with "/"
            token = "t",
            entityId = "device_tracker.pixel",
        )

        assertThat(server.takeRequest().path).isEqualTo("/api/states/device_tracker.pixel")
    }
}
```

Add the missing imports at the top: `okhttp3.MediaType.Companion.toMediaType` and
`com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory`
(it is an extension on `Json`, so it needs the `json` receiver shown above).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.ha.HomeAssistantClientTest'`
Expected: FAIL — `Unresolved reference: HomeAssistantClient`.

- [ ] **Step 3: Write `HaStateDto.kt`**

```kotlin
package com.anchor.data.ha

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single entity state from `GET /api/states/<entity_id>`.
 * Attributes are left as raw JSON because Home Assistant's attribute set is
 * entity-specific and we only ever read `friendly_name`.
 */
@Serializable
data class HaStateDto(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: Map<String, JsonElement> = emptyMap(),
) {
    val friendlyName: String?
        get() = runCatching { attributes["friendly_name"]?.jsonPrimitive?.content }.getOrNull()
}
```

- [ ] **Step 4: Write `HomeAssistantApi.kt`**

```kotlin
package com.anchor.data.ha

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/**
 * The base URL is user-configurable and can change at runtime, so every call
 * passes a fully-qualified @Url. Retrofit still needs *some* base URL at
 * construction time; AppModule supplies a placeholder.
 */
interface HomeAssistantApi {

    @GET
    suspend fun state(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): HaStateDto
}
```

- [ ] **Step 5: Write `HomeAssistantClient.kt`**

```kotlin
package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HaResult {
    data class Ok(val state: HaStateDto) : HaResult
    /**
     * Home Assistant could not be reached or understood. Every caller MUST
     * treat this as "do less blocking" — see the fail-open constraint.
     */
    data object Unavailable : HaResult
}

@Singleton
class HomeAssistantClient @Inject constructor(
    private val api: HomeAssistantApi,
) {
    /**
     * Never throws. Any failure — no config, DNS, timeout, 4xx, 5xx, bad
     * JSON — collapses to [HaResult.Unavailable].
     */
    suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult {
        if (baseUrl.isBlank() || token.isBlank() || entityId.isBlank()) {
            return HaResult.Unavailable
        }
        val url = "${baseUrl.trimEnd('/')}/api/states/$entityId"
        return try {
            HaResult.Ok(api.state(url, "Bearer $token"))
        } catch (t: Throwable) {
            HaResult.Unavailable
        }
    }

    suspend fun fetchDeviceTracker(settings: AnchorSettings): HaResult =
        fetchState(
            baseUrl = settings.haBaseUrl,
            token = settings.haToken,
            entityId = settings.haDeviceTrackerEntityId,
        )
}
```

> `catch (t: Throwable)` is deliberate and is the one place in the codebase
> where a blanket catch is correct: a blocking decision must never crash the
> accessibility service, and every failure mode has the same safe answer.

- [ ] **Step 6: Add HTTP providers to `AppModule.kt`**

Append inside `object AppModule`:

```kotlin
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // Placeholder: every call supplies an absolute @Url.
            .baseUrl("http://localhost/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideHomeAssistantApi(retrofit: Retrofit): HomeAssistantApi =
        retrofit.create(HomeAssistantApi::class.java)
```

Imports: `com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory`, `kotlinx.serialization.json.Json`, `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.OkHttpClient`, `retrofit2.Retrofit`, `java.util.concurrent.TimeUnit`, `com.anchor.data.ha.HomeAssistantApi`.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.ha.HomeAssistantClientTest'`
Expected: PASS (8 tests).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add Home Assistant REST client with fail-open error handling"
```

---

### Task 6: `LocationGate` — At Home vs Specific Rooms

**Files:**
- Create: `app/src/main/java/com/anchor/data/ha/LocationGate.kt`
- Test: `app/src/test/java/com/anchor/data/ha/LocationGateTest.kt`

**Interfaces:**
- Consumes: `HaResult`, `HaStateDto` (Task 5), `LocationMode`, `AnchorSettings` (Task 4).
- Produces:
  - `enum class Presence { IN_SCOPE, OUT_OF_SCOPE, UNKNOWN }`
  - `object LocationGate { fun evaluate(result: HaResult, mode: LocationMode, allowedRooms: List<String>): Presence }`

`IN_SCOPE` means "the user is in a place where the protocol applies". Morning:
proceed with the lockdown. Evening: show the strict overlay. `OUT_OF_SCOPE` and
`UNKNOWN` both mean less blocking; they are kept distinct so the dashboard can
tell the user *why*.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/ha/LocationGateTest.kt`:

```kotlin
package com.anchor.data.ha

import com.anchor.data.settings.LocationMode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test

class LocationGateTest {

    private fun ok(state: String, friendlyName: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendlyName
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) }
            ?: emptyMap()
        return HaResult.Ok(HaStateDto("device_tracker.pixel", state, attrs))
    }

    // --- AT_HOME mode ---

    @Test
    fun `at home mode is in scope when the state is home`() {
        assertThat(LocationGate.evaluate(ok("home"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `at home mode is out of scope when the state is not_home`() {
        assertThat(LocationGate.evaluate(ok("not_home"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `at home mode treats an arbitrary zone name as out of scope`() {
        assertThat(LocationGate.evaluate(ok("Work"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `at home mode ignores case on the state`() {
        assertThat(LocationGate.evaluate(ok("Home"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `unavailable is UNKNOWN in at home mode`() {
        assertThat(LocationGate.evaluate(HaResult.Unavailable, LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `an unavailable or unknown entity state is UNKNOWN`() {
        assertThat(LocationGate.evaluate(ok("unavailable"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.UNKNOWN)
        assertThat(LocationGate.evaluate(ok("unknown"), LocationMode.AT_HOME, emptyList()))
            .isEqualTo(Presence.UNKNOWN)
    }

    // --- SPECIFIC_ROOMS mode ---

    @Test
    fun `specific rooms matches a room name inside friendly_name`() {
        val result = LocationGate.evaluate(
            ok("home", "Bedroom Presence Sensor"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom", "Office"),
        )
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `specific rooms matching is case insensitive`() {
        val result = LocationGate.evaluate(
            ok("home", "shre's OFFICE tracker"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("office"),
        )
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `specific rooms is out of scope when no room matches`() {
        val result = LocationGate.evaluate(
            ok("home", "Kitchen Sensor"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom", "Office"),
        )
        assertThat(result).isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `specific rooms falls back to the state value when friendly_name is absent`() {
        val result = LocationGate.evaluate(
            ok("Bedroom"),
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom"),
        )
        assertThat(result).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `specific rooms with an empty room list is out of scope, never in scope`() {
        val result = LocationGate.evaluate(
            ok("home", "Bedroom"),
            LocationMode.SPECIFIC_ROOMS,
            emptyList(),
        )
        assertThat(result).isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `unavailable is UNKNOWN in specific rooms mode`() {
        val result = LocationGate.evaluate(
            HaResult.Unavailable,
            LocationMode.SPECIFIC_ROOMS,
            listOf("Bedroom"),
        )
        assertThat(result).isEqualTo(Presence.UNKNOWN)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.ha.LocationGateTest'`
Expected: FAIL — `Unresolved reference: LocationGate`.

- [ ] **Step 3: Write `LocationGate.kt`**

```kotlin
package com.anchor.data.ha

import com.anchor.data.settings.LocationMode

/**
 * Where the user is, relative to the places the protocol applies.
 *
 * IN_SCOPE     -> morning: lock down. evening: strict overlay.
 * OUT_OF_SCOPE -> morning: skip.      evening: simple 5s delay.
 * UNKNOWN      -> same as OUT_OF_SCOPE for behaviour; kept separate so the
 *                 dashboard can distinguish "you're out" from "HA is down".
 */
enum class Presence { IN_SCOPE, OUT_OF_SCOPE, UNKNOWN }

object LocationGate {

    /** States Home Assistant uses to say "I don't actually know". */
    private val UNKNOWN_STATES = setOf("unavailable", "unknown", "none", "")

    fun evaluate(
        result: HaResult,
        mode: LocationMode,
        allowedRooms: List<String>,
    ): Presence {
        val state = when (result) {
            is HaResult.Unavailable -> return Presence.UNKNOWN
            is HaResult.Ok -> result.state
        }
        if (state.state.lowercase() in UNKNOWN_STATES) return Presence.UNKNOWN

        return when (mode) {
            LocationMode.AT_HOME ->
                if (state.state.equals("home", ignoreCase = true)) Presence.IN_SCOPE
                else Presence.OUT_OF_SCOPE

            LocationMode.SPECIFIC_ROOMS -> {
                // The room usually appears in friendly_name (e.g. a per-room
                // BLE/mmWave tracker). Fall back to the raw state so a
                // zone-based tracker reporting "Bedroom" also works.
                val haystack = (state.friendlyName ?: state.state).lowercase()
                val matched = allowedRooms.any { room ->
                    room.isNotBlank() && haystack.contains(room.trim().lowercase())
                }
                if (matched) Presence.IN_SCOPE else Presence.OUT_OF_SCOPE
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.ha.LocationGateTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add location gate for At Home and Specific Rooms modes"
```

---

### Task 7: Remote kill switch

**Files:**
- Create: `app/src/main/java/com/anchor/data/ha/KillSwitch.kt`
- Test: `app/src/test/java/com/anchor/data/ha/KillSwitchTest.kt`

**Interfaces:**
- Consumes: `HomeAssistantClient`, `HaResult` (Task 5), `AnchorSettings` (Task 4).
- Produces:
  - `enum class OverrideStatus { ACTIVE, INACTIVE, UNKNOWN }` — `ACTIVE` means blocking is disabled.
  - `class KillSwitch(client: HomeAssistantClient)` with `suspend fun check(settings: AnchorSettings): OverrideStatus` and `fun isBlockingDisabled(status: OverrideStatus): Boolean`

**Fail-open decision for the kill switch specifically:** if the kill switch is
enabled but HA is unreachable, `check` returns `UNKNOWN`, and `UNKNOWN` does
**not** disable blocking. Reasoning: the spec's own fail-open rules already
neuter blocking whenever HA is unreachable (the location gate returns
`UNKNOWN` from the same outage), so the user is never trapped. Making an
outage also count as an override would let anyone bypass the app by turning
off Wi-Fi, which defeats the friction the feature exists to create.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/ha/KillSwitchTest.kt`:

```kotlin
package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KillSwitchTest {

    /** A HomeAssistantClient stand-in that returns whatever we hand it. */
    private class FakeClient(private val result: HaResult) :
        HomeAssistantClient(FakeApi) {
        var requestedEntity: String? = null
        override suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult {
            requestedEntity = entityId
            return result
        }
        private object FakeApi : HomeAssistantApi {
            override suspend fun state(url: String, authorization: String): HaStateDto =
                error("not called")
        }
    }

    private fun okState(state: String) =
        HaResult.Ok(HaStateDto("input_boolean.anchor_override", state, emptyMap()))

    private fun enabledSettings(overrideState: String = "on") = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
        killSwitchEnabled = true,
        killSwitchEntityId = "input_boolean.anchor_override",
        killSwitchOverrideState = overrideState,
    )

    @Test
    fun `matching state means the override is ACTIVE`() = runTest {
        val switch = KillSwitch(FakeClient(okState("on")))
        assertThat(switch.check(enabledSettings("on"))).isEqualTo(OverrideStatus.ACTIVE)
    }

    @Test
    fun `non-matching state means INACTIVE`() = runTest {
        val switch = KillSwitch(FakeClient(okState("off")))
        assertThat(switch.check(enabledSettings("on"))).isEqualTo(OverrideStatus.INACTIVE)
    }

    @Test
    fun `the override state is configurable to off`() = runTest {
        val switch = KillSwitch(FakeClient(okState("off")))
        assertThat(switch.check(enabledSettings("off"))).isEqualTo(OverrideStatus.ACTIVE)
    }

    @Test
    fun `state comparison ignores case and surrounding whitespace`() = runTest {
        val switch = KillSwitch(FakeClient(okState("ON")))
        assertThat(switch.check(enabledSettings(" on "))).isEqualTo(OverrideStatus.ACTIVE)
    }

    @Test
    fun `a disabled kill switch is INACTIVE and makes no request`() = runTest {
        val fake = FakeClient(okState("on"))
        val switch = KillSwitch(fake)
        val status = switch.check(enabledSettings("on").copy(killSwitchEnabled = false))

        assertThat(status).isEqualTo(OverrideStatus.INACTIVE)
        assertThat(fake.requestedEntity).isNull()
    }

    @Test
    fun `an enabled kill switch with a blank entity id is INACTIVE`() = runTest {
        val fake = FakeClient(okState("on"))
        val status = KillSwitch(fake).check(enabledSettings("on").copy(killSwitchEntityId = "  "))

        assertThat(status).isEqualTo(OverrideStatus.INACTIVE)
        assertThat(fake.requestedEntity).isNull()
    }

    @Test
    fun `an HA outage is UNKNOWN and does not disable blocking`() = runTest {
        val switch = KillSwitch(FakeClient(HaResult.Unavailable))
        val status = switch.check(enabledSettings("on"))

        assertThat(status).isEqualTo(OverrideStatus.UNKNOWN)
        assertThat(switch.isBlockingDisabled(status)).isFalse()
    }

    @Test
    fun `only ACTIVE disables blocking`() {
        val switch = KillSwitch(FakeClient(HaResult.Unavailable))
        assertThat(switch.isBlockingDisabled(OverrideStatus.ACTIVE)).isTrue()
        assertThat(switch.isBlockingDisabled(OverrideStatus.INACTIVE)).isFalse()
        assertThat(switch.isBlockingDisabled(OverrideStatus.UNKNOWN)).isFalse()
    }

    @Test
    fun `it queries the configured kill switch entity, not the device tracker`() = runTest {
        val fake = FakeClient(okState("off"))
        KillSwitch(fake).check(enabledSettings("on"))
        assertThat(fake.requestedEntity).isEqualTo("input_boolean.anchor_override")
    }
}
```

- [ ] **Step 2: Make `HomeAssistantClient` open so it can be faked**

Modify `app/src/main/java/com/anchor/data/ha/HomeAssistantClient.kt`: change
`class HomeAssistantClient` to `open class HomeAssistantClient` and
`suspend fun fetchState` to `open suspend fun fetchState`.

> Preferred alternative if you would rather not open the class: extract an
> `interface HaStateSource { suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult }`,
> have `HomeAssistantClient` implement it, and depend on the interface in
> `KillSwitch`. Either is fine — pick one and keep it consistent; the tests
> above assume the `open class` route.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.ha.KillSwitchTest'`
Expected: FAIL — `Unresolved reference: KillSwitch`.

- [ ] **Step 4: Write `KillSwitch.kt`**

```kotlin
package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ACTIVE   -> the user has flipped the HA input_boolean; skip all blocking.
 * INACTIVE -> block normally.
 * UNKNOWN  -> HA is unreachable. Deliberately does NOT disable blocking:
 *             turning off Wi-Fi must not be an escape hatch. The user is
 *             still safe because the same outage makes the location gate
 *             return UNKNOWN, which itself suppresses the strict paths.
 */
enum class OverrideStatus { ACTIVE, INACTIVE, UNKNOWN }

@Singleton
class KillSwitch @Inject constructor(
    private val client: HomeAssistantClient,
) {
    /**
     * MUST be called at the moment of blocking, not cached at startup —
     * that is the whole point of a *remote* kill switch.
     */
    suspend fun check(settings: AnchorSettings): OverrideStatus {
        if (!settings.killSwitchEnabled) return OverrideStatus.INACTIVE
        if (settings.killSwitchEntityId.isBlank()) return OverrideStatus.INACTIVE

        return when (
            val result = client.fetchState(
                baseUrl = settings.haBaseUrl,
                token = settings.haToken,
                entityId = settings.killSwitchEntityId.trim(),
            )
        ) {
            is HaResult.Unavailable -> OverrideStatus.UNKNOWN
            is HaResult.Ok ->
                if (result.state.state.trim()
                        .equals(settings.killSwitchOverrideState.trim(), ignoreCase = true)
                ) OverrideStatus.ACTIVE else OverrideStatus.INACTIVE
        }
    }

    fun isBlockingDisabled(status: OverrideStatus): Boolean = status == OverrideStatus.ACTIVE
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.ha.KillSwitchTest'`
Expected: PASS (9 tests).

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew :app:test`
Expected: PASS — all tests from Tasks 1–7.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add remote kill switch backed by a Home Assistant input_boolean"
```

---

## Phase 3 — Time Windows and Markdown Export

### Task 8: `TimeWindow` and `AnchorDate` — schedule arithmetic

**Files:**
- Create: `app/src/main/java/com/anchor/domain/TimeWindow.kt`
- Create: `app/src/main/java/com/anchor/domain/AnchorDate.kt`
- Test: `app/src/test/java/com/anchor/domain/TimeWindowTest.kt`
- Test: `app/src/test/java/com/anchor/domain/AnchorDateTest.kt`

**Interfaces:**
- Consumes: `AnchorSettings` (Task 4), injected `java.time.Clock`.
- Produces:
  - `object TimeWindow { fun contains(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean; fun wraps(startMinute: Int, endMinute: Int): Boolean }`
  - `class AnchorDate(clock: Clock)` with `fun today(): String`, `fun minuteOfDay(): Int`, `fun eveningAnchorDate(eveningEndMinute: Int): String`, and `fun format(date: LocalDate): String`

**The midnight-wrap problem:** the evening window is 20:00→05:00, which spans
midnight. Two consequences: `contains` must handle `start > end`, and "the
evening block resets daily" needs a definition of *which* day 01:00 belongs
to. `eveningAnchorDate` answers that: any time before `eveningEndMinute`
belongs to the previous calendar day's evening, so a 1 AM answer lands in the
same `DailyLog` row as the 10 PM session that preceded it.

- [ ] **Step 1: Write the failing `TimeWindowTest`**

`app/src/test/java/com/anchor/domain/TimeWindowTest.kt`:

```kotlin
package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeWindowTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    // --- Normal (non-wrapping) window: the morning, 05:00–12:00 ---

    @Test
    fun `includes the start minute`() {
        assertThat(TimeWindow.contains(at(5), at(5), at(12))).isTrue()
    }

    @Test
    fun `excludes the end minute`() {
        assertThat(TimeWindow.contains(at(12), at(5), at(12))).isFalse()
    }

    @Test
    fun `includes a minute in the middle`() {
        assertThat(TimeWindow.contains(at(7, 30), at(5), at(12))).isTrue()
    }

    @Test
    fun `excludes a minute before the start`() {
        assertThat(TimeWindow.contains(at(4, 59), at(5), at(12))).isFalse()
    }

    @Test
    fun `excludes a minute after the end`() {
        assertThat(TimeWindow.contains(at(13), at(5), at(12))).isFalse()
    }

    // --- Wrapping window: the evening, 20:00–05:00 ---

    @Test
    fun `wrapping window includes late evening`() {
        assertThat(TimeWindow.contains(at(22), at(20), at(5))).isTrue()
    }

    @Test
    fun `wrapping window includes just after midnight`() {
        assertThat(TimeWindow.contains(at(0, 30), at(20), at(5))).isTrue()
    }

    @Test
    fun `wrapping window includes the start minute`() {
        assertThat(TimeWindow.contains(at(20), at(20), at(5))).isTrue()
    }

    @Test
    fun `wrapping window excludes the end minute`() {
        assertThat(TimeWindow.contains(at(5), at(20), at(5))).isFalse()
    }

    @Test
    fun `wrapping window excludes the afternoon`() {
        assertThat(TimeWindow.contains(at(15), at(20), at(5))).isFalse()
    }

    @Test
    fun `wrapping window excludes 19_59`() {
        assertThat(TimeWindow.contains(at(19, 59), at(20), at(5))).isFalse()
    }

    // --- Degenerate windows ---

    @Test
    fun `a zero-length window contains nothing`() {
        assertThat(TimeWindow.contains(at(9), at(9), at(9))).isFalse()
        assertThat(TimeWindow.contains(at(0), at(9), at(9))).isFalse()
    }

    @Test
    fun `wraps reports whether the window crosses midnight`() {
        assertThat(TimeWindow.wraps(at(20), at(5))).isTrue()
        assertThat(TimeWindow.wraps(at(5), at(12))).isFalse()
        assertThat(TimeWindow.wraps(at(9), at(9))).isFalse()
    }
}
```

- [ ] **Step 2: Write the failing `AnchorDateTest`**

`app/src/test/java/com/anchor/domain/AnchorDateTest.kt`:

```kotlin
package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AnchorDateTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun anchorDateAt(iso: String) =
        AnchorDate(Clock.fixed(Instant.parse(iso), zone))

    @Test
    fun `today formats as ISO yyyy-MM-dd in the local zone`() {
        // 2026-09-09T15:30Z is 08:30 local on the same day.
        assertThat(anchorDateAt("2026-09-09T15:30:00Z").today()).isEqualTo("2026-09-09")
    }

    @Test
    fun `today rolls over using the local zone, not UTC`() {
        // 2026-09-10T05:00Z is 22:00 local on 2026-09-09.
        assertThat(anchorDateAt("2026-09-10T05:00:00Z").today()).isEqualTo("2026-09-09")
    }

    @Test
    fun `minuteOfDay reflects local wall-clock time`() {
        // 15:30Z -> 08:30 local -> 510 minutes.
        assertThat(anchorDateAt("2026-09-09T15:30:00Z").minuteOfDay()).isEqualTo(8 * 60 + 30)
    }

    @Test
    fun `evening at 10pm anchors to the same calendar day`() {
        // 2026-09-10T05:00Z -> 22:00 local on the 9th.
        val date = anchorDateAt("2026-09-10T05:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-09")
    }

    @Test
    fun `evening at 1am anchors to the previous calendar day`() {
        // 2026-09-10T08:00Z -> 01:00 local on the 10th.
        val date = anchorDateAt("2026-09-10T08:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-09")
    }

    @Test
    fun `evening at 6am anchors to the current day, past the window end`() {
        // 2026-09-10T13:00Z -> 06:00 local on the 10th.
        val date = anchorDateAt("2026-09-10T13:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-10")
    }

    @Test
    fun `anchoring works across a month boundary`() {
        // 2026-10-01T08:00Z -> 01:00 local on Oct 1 -> anchors to Sep 30.
        val date = anchorDateAt("2026-10-01T08:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-30")
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.anchor.domain.TimeWindowTest' --tests 'com.anchor.domain.AnchorDateTest'`
Expected: FAIL — `Unresolved reference: TimeWindow` / `AnchorDate`.

- [ ] **Step 4: Write `TimeWindow.kt`**

```kotlin
package com.anchor.domain

/**
 * Half-open [start, end) windows over minutes-since-midnight, supporting
 * windows that cross midnight (the evening window is typically 20:00–05:00).
 */
object TimeWindow {

    /** True when the window crosses midnight, i.e. start is later than end. */
    fun wraps(startMinute: Int, endMinute: Int): Boolean = startMinute > endMinute

    fun contains(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean = when {
        startMinute == endMinute -> false                       // zero-length
        wraps(startMinute, endMinute) ->                        // e.g. 20:00–05:00
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        else ->                                                 // e.g. 05:00–12:00
            minuteOfDay in startMinute until endMinute
    }
}
```

- [ ] **Step 5: Write `AnchorDate.kt`**

```kotlin
package com.anchor.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's only reader of "now". Everything else takes an AnchorDate.
 */
@Singleton
class AnchorDate @Inject constructor(private val clock: Clock) {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    fun today(): String = format(now().toLocalDate())

    fun minuteOfDay(): Int = now().toLocalTime().let { it.hour * 60 + it.minute }

    fun format(date: LocalDate): String = date.format(formatter)

    /**
     * Which calendar day an evening session belongs to. Times before the
     * evening window's end (e.g. 01:00 when the window ends at 05:00) belong
     * to the *previous* day, so the 10 PM and 1 AM halves of one night write
     * to the same [com.anchor.data.db.DailyLog] row.
     */
    fun eveningAnchorDate(eveningEndMinute: Int): String {
        val current = now()
        val minute = current.hour * 60 + current.minute
        val date = if (minute < eveningEndMinute) {
            current.toLocalDate().minusDays(1)
        } else {
            current.toLocalDate()
        }
        return format(date)
    }
}
```

- [ ] **Step 6: Run both tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.anchor.domain.TimeWindowTest' --tests 'com.anchor.domain.AnchorDateTest'`
Expected: PASS (20 tests).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add time window and anchor date arithmetic"
```

---

### Task 9: `MarkdownRenderer` — the export document

**Files:**
- Create: `app/src/main/java/com/anchor/data/export/MarkdownRenderer.kt`
- Test: `app/src/test/java/com/anchor/data/export/MarkdownRendererTest.kt`

**Interfaces:**
- Consumes: `DailyLog` (Task 2), `CustomQuestion`, `Phase`, `SlotKey` (Task 3).
- Produces:
  - `object MarkdownRenderer` with:
    - `fun render(log: DailyLog, questions: List<CustomQuestion>): String` — the whole document
    - `fun renderSection(phase: Phase, log: DailyLog, questions: List<CustomQuestion>): String?` — one `## Morning` / `## Evening` block, or null when that phase has no answers
    - `fun mergeInto(existing: String, section: String, phase: Phase): String` — appends or replaces a phase section in an existing file's content
  - `fun DailyLog.answerFor(slotKey: String): String?` and `fun DailyLog.withAnswer(slotKey: String, answer: String): DailyLog` — the slot ↔ column mapping used everywhere

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/export/MarkdownRendererTest.kt`:

```kotlin
package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownRendererTest {

    private val defaults = DefaultQuestions.ALL

    private val fullLog = DailyLog(
        date = "2026-09-09",
        mission = "Ship the Anchor plan",
        avoiding = "The invoice email",
        led = "Chose the schema without asking",
        softened = "Told my sister I missed her",
        faked = "Nodded along in standup",
    )

    // --- Slot mapping ---

    @Test
    fun `answerFor reads the five named columns`() {
        assertThat(fullLog.answerFor(SlotKey.MISSION)).isEqualTo("Ship the Anchor plan")
        assertThat(fullLog.answerFor(SlotKey.AVOIDING)).isEqualTo("The invoice email")
        assertThat(fullLog.answerFor(SlotKey.LED)).isEqualTo("Chose the schema without asking")
        assertThat(fullLog.answerFor(SlotKey.SOFTENED)).isEqualTo("Told my sister I missed her")
        assertThat(fullLog.answerFor(SlotKey.FAKED)).isEqualTo("Nodded along in standup")
    }

    @Test
    fun `withAnswer writes named slots to columns and custom slots to extras`() {
        val custom = "custom:abc-123"
        val log = DailyLog(date = "2026-09-09")
            .withAnswer(SlotKey.MISSION, "Focus")
            .withAnswer(custom, "Call the plumber")

        assertThat(log.mission).isEqualTo("Focus")
        assertThat(log.answerFor(custom)).isEqualTo("Call the plumber")
        assertThat(log.extraAnswersJson).contains("Call the plumber")
    }

    @Test
    fun `withAnswer on a custom slot preserves other custom answers`() {
        val log = DailyLog(date = "2026-09-09")
            .withAnswer("custom:a", "one")
            .withAnswer("custom:b", "two")

        assertThat(log.answerFor("custom:a")).isEqualTo("one")
        assertThat(log.answerFor("custom:b")).isEqualTo("two")
    }

    @Test
    fun `answerFor returns null for an unknown custom slot`() {
        assertThat(DailyLog(date = "2026-09-09").answerFor("custom:nope")).isNull()
    }

    // --- Rendering ---

    @Test
    fun `renders the exact format from the spec`() {
        val expected = """
            # Daily Anchor - 2026-09-09

            ## Morning
            - **Mission:** Ship the Anchor plan
            - **Avoiding:** The invoice email

            ## Evening
            - **Led:** Chose the schema without asking
            - **Softened:** Told my sister I missed her
            - **Faked:** Nodded along in standup
        """.trimIndent() + "\n"

        assertThat(MarkdownRenderer.render(fullLog, defaults)).isEqualTo(expected)
    }

    @Test
    fun `omits the evening section when there are no evening answers`() {
        val morningOnly = fullLog.copy(led = null, softened = null, faked = null)
        val rendered = MarkdownRenderer.render(morningOnly, defaults)

        assertThat(rendered).contains("## Morning")
        assertThat(rendered).doesNotContain("## Evening")
    }

    @Test
    fun `renders a user-added question using its own prompt as the label`() {
        val custom = CustomQuestion(
            phase = Phase.MORNING,
            slotKey = "custom:x",
            prompt = "Who do I owe a reply?",
            sortOrder = 2,
        )
        val log = fullLog.withAnswer("custom:x", "Priya")

        val rendered = MarkdownRenderer.render(log, defaults + custom)

        assertThat(rendered).contains("- **Who do I owe a reply?:** Priya")
    }

    @Test
    fun `respects question sortOrder`() {
        val reordered = defaults.map {
            when (it.slotKey) {
                SlotKey.MISSION -> it.copy(sortOrder = 5)
                SlotKey.AVOIDING -> it.copy(sortOrder = 1)
                else -> it
            }
        }
        val rendered = MarkdownRenderer.render(fullLog, reordered)

        assertThat(rendered.indexOf("**Avoiding:**"))
            .isLessThan(rendered.indexOf("**Mission:**"))
    }

    @Test
    fun `a multi-line answer is flattened so the bullet list stays valid`() {
        val log = fullLog.copy(mission = "Line one\nLine two")
        assertThat(MarkdownRenderer.render(log, defaults))
            .contains("- **Mission:** Line one Line two")
    }

    @Test
    fun `skips questions whose answer is missing or blank`() {
        val log = fullLog.copy(avoiding = "   ")
        val rendered = MarkdownRenderer.render(log, defaults)

        assertThat(rendered).contains("**Mission:**")
        assertThat(rendered).doesNotContain("**Avoiding:**")
    }

    // --- Merging into an existing file ---

    @Test
    fun `merging an evening section appends it to a morning-only file`() {
        val existing = MarkdownRenderer.render(
            fullLog.copy(led = null, softened = null, faked = null), defaults
        )
        val evening = MarkdownRenderer.renderSection(Phase.EVENING, fullLog, defaults)!!

        val merged = MarkdownRenderer.mergeInto(existing, evening, Phase.EVENING)

        assertThat(merged).isEqualTo(MarkdownRenderer.render(fullLog, defaults))
    }

    @Test
    fun `merging replaces an existing section rather than duplicating it`() {
        val existing = MarkdownRenderer.render(fullLog, defaults)
        val revised = MarkdownRenderer.renderSection(
            Phase.EVENING, fullLog.copy(led = "Actually, I deferred"), defaults
        )!!

        val merged = MarkdownRenderer.mergeInto(existing, revised, Phase.EVENING)

        assertThat(merged.split("## Evening")).hasSize(2)   // exactly one occurrence
        assertThat(merged).contains("Actually, I deferred")
        assertThat(merged).doesNotContain("Chose the schema without asking")
        assertThat(merged).contains("## Morning")
    }

    @Test
    fun `merging a morning section into an evening-only file puts morning first`() {
        val eveningOnly = MarkdownRenderer.render(
            fullLog.copy(mission = null, avoiding = null), defaults
        )
        val morning = MarkdownRenderer.renderSection(Phase.MORNING, fullLog, defaults)!!

        val merged = MarkdownRenderer.mergeInto(eveningOnly, morning, Phase.MORNING)

        assertThat(merged.indexOf("## Morning")).isLessThan(merged.indexOf("## Evening"))
    }

    @Test
    fun `merging into unrelated content preserves that content`() {
        val existing = "# Daily Anchor - 2026-09-09\n\nSome hand-written note.\n"
        val morning = MarkdownRenderer.renderSection(Phase.MORNING, fullLog, defaults)!!

        val merged = MarkdownRenderer.mergeInto(existing, morning, Phase.MORNING)

        assertThat(merged).contains("Some hand-written note.")
        assertThat(merged).contains("**Mission:**")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.export.MarkdownRendererTest'`
Expected: FAIL — `Unresolved reference: MarkdownRenderer`.

- [ ] **Step 3: Write `MarkdownRenderer.kt`**

```kotlin
package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

private val extrasJson = Json { ignoreUnknownKeys = true }

/** Reads an answer by slot key, from a named column or the extras map. */
fun DailyLog.answerFor(slotKey: String): String? = when (slotKey) {
    SlotKey.MISSION -> mission
    SlotKey.AVOIDING -> avoiding
    SlotKey.LED -> led
    SlotKey.SOFTENED -> softened
    SlotKey.FAKED -> faked
    else -> extras()[slotKey]
}

/** Writes an answer by slot key, returning a new log. */
fun DailyLog.withAnswer(slotKey: String, answer: String): DailyLog = when (slotKey) {
    SlotKey.MISSION -> copy(mission = answer)
    SlotKey.AVOIDING -> copy(avoiding = answer)
    SlotKey.LED -> copy(led = answer)
    SlotKey.SOFTENED -> copy(softened = answer)
    SlotKey.FAKED -> copy(faked = answer)
    else -> {
        val next = extras() + (slotKey to answer)
        copy(
            extraAnswersJson = extrasJson.encodeToString(
                JsonObject.serializer(),
                JsonObject(next.mapValues { JsonPrimitive(it.value) }),
            )
        )
    }
}

private fun DailyLog.extras(): Map<String, String> {
    val raw = extraAnswersJson ?: return emptyMap()
    return runCatching {
        extrasJson.decodeFromString(JsonObject.serializer(), raw)
            .mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())
}

/**
 * Renders a [DailyLog] into the Markdown document the spec describes.
 * Pure and Android-free so it can be unit-tested directly.
 */
object MarkdownRenderer {

    private const val MORNING_HEADING = "## Morning"
    private const val EVENING_HEADING = "## Evening"

    /** The short labels the spec uses for the five default slots. */
    private val NAMED_LABELS = mapOf(
        SlotKey.MISSION to "Mission",
        SlotKey.AVOIDING to "Avoiding",
        SlotKey.LED to "Led",
        SlotKey.SOFTENED to "Softened",
        SlotKey.FAKED to "Faked",
    )

    fun heading(phase: Phase): String =
        if (phase == Phase.MORNING) MORNING_HEADING else EVENING_HEADING

    fun title(date: String): String = "# Daily Anchor - $date"

    /**
     * A `## Morning` or `## Evening` block, or null when the log has no
     * answers for that phase.
     */
    fun renderSection(
        phase: Phase,
        log: DailyLog,
        questions: List<CustomQuestion>,
    ): String? {
        val lines = questions
            .filter { it.phase == phase }
            .sortedBy { it.sortOrder }
            .mapNotNull { question ->
                val answer = log.answerFor(question.slotKey)?.trim()
                if (answer.isNullOrBlank()) return@mapNotNull null
                val label = NAMED_LABELS[question.slotKey] ?: question.prompt
                // Flatten newlines so each answer stays one list item.
                "- **$label:** ${answer.replace(Regex("\\s*\\R\\s*"), " ")}"
            }
        if (lines.isEmpty()) return null
        return (listOf(heading(phase)) + lines).joinToString("\n")
    }

    fun render(log: DailyLog, questions: List<CustomQuestion>): String {
        val sections = listOfNotNull(
            renderSection(Phase.MORNING, log, questions),
            renderSection(Phase.EVENING, log, questions),
        )
        return buildString {
            append(title(log.date)).append("\n")
            sections.forEach { append("\n").append(it).append("\n") }
        }
    }

    /**
     * Folds [section] into [existing] file content: replaces the phase's
     * block if present, otherwise inserts it in Morning-then-Evening order.
     * Any other content in the file (hand-written notes, Joplin metadata) is
     * preserved.
     */
    fun mergeInto(existing: String, section: String, phase: Phase): String {
        val heading = heading(phase)
        val otherHeading = heading(if (phase == Phase.MORNING) Phase.EVENING else Phase.MORNING)

        val start = existing.indexOf(heading)
        if (start >= 0) {
            // Replace from this heading up to the next "## " heading or EOF.
            val after = existing.indexOf("\n## ", start + heading.length)
            val end = if (after >= 0) after + 1 else existing.length
            return existing.substring(0, start).trimEnd('\n') +
                "\n\n" + section + "\n" +
                existing.substring(end).let { if (it.isBlank()) "" else "\n$it" }
        }

        // Morning must land before an existing Evening block.
        val otherStart = existing.indexOf(otherHeading)
        if (phase == Phase.MORNING && otherStart >= 0) {
            return existing.substring(0, otherStart).trimEnd('\n') +
                "\n\n" + section + "\n\n" + existing.substring(otherStart)
        }

        return existing.trimEnd('\n') + "\n\n" + section + "\n"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.export.MarkdownRendererTest'`
Expected: PASS (14 tests).

If the whitespace assertions in `renders the exact format from the spec` or
the merge tests fail, fix `MarkdownRenderer` — do not relax the expected
strings. The exact output format is a spec requirement and the merge logic is
what keeps a day's file from accumulating duplicate sections.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add Markdown renderer with slot mapping and section merging"
```

---

### Task 10: `MarkdownExporter` — SAF file write and append

**Files:**
- Create: `app/src/main/java/com/anchor/data/export/MarkdownExporter.kt`
- Test: `app/src/test/java/com/anchor/data/export/MarkdownExporterTest.kt`

**Interfaces:**
- Consumes: `MarkdownRenderer`, `DailyLog`, `CustomQuestion`, `Phase`, `AnchorSettings`.
- Produces:
  - `interface DocumentStore { fun read(fileName: String): String?; fun write(fileName: String, content: String): Boolean }` — the seam that keeps SAF out of the unit tests.
  - `class SafDocumentStore(context: Context, treeUri: Uri) : DocumentStore`
  - `class MarkdownExporter(storeFactory: (String) -> DocumentStore?)` with `suspend fun export(log: DailyLog, questions: List<CustomQuestion>, phase: Phase, treeUri: String?): ExportResult`
  - `sealed interface ExportResult { data class Written(val fileName: String, val content: String) : ExportResult; data object NoDirectoryConfigured : ExportResult; data class Failed(val reason: String) : ExportResult }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/data/export/MarkdownExporterTest.kt`:

```kotlin
package com.anchor.data.export

import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MarkdownExporterTest {

    /** An in-memory DocumentStore — no Android, no SAF, no disk. */
    private class FakeStore(
        seed: Map<String, String> = emptyMap(),
        private val failWrites: Boolean = false,
    ) : DocumentStore {
        val files = seed.toMutableMap()
        override fun read(fileName: String): String? = files[fileName]
        override fun write(fileName: String, content: String): Boolean {
            if (failWrites) return false
            files[fileName] = content
            return true
        }
    }

    private val questions = DefaultQuestions.ALL

    private val morningLog = DailyLog(
        date = "2026-09-09",
        mission = "Ship the plan",
        avoiding = "The invoice email",
    )

    private val fullLog = morningLog.copy(
        led = "Chose the schema",
        softened = "Called my sister",
        faked = "Nodded in standup",
    )

    private fun exporter(store: DocumentStore?) =
        MarkdownExporter { store }

    @Test
    fun `writes a new file named by the date`() = runTest {
        val store = FakeStore()
        val result = exporter(store)
            .export(morningLog, questions, Phase.MORNING, treeUri = "content://tree")

        assertThat(result).isInstanceOf(ExportResult.Written::class.java)
        assertThat((result as ExportResult.Written).fileName).isEqualTo("2026-09-09.md")
        assertThat(store.files["2026-09-09.md"]).contains("# Daily Anchor - 2026-09-09")
        assertThat(store.files["2026-09-09.md"]).contains("**Mission:** Ship the plan")
    }

    @Test
    fun `appends the evening section to an existing morning file`() = runTest {
        val existing = MarkdownRenderer.render(morningLog, questions)
        val store = FakeStore(mapOf("2026-09-09.md" to existing))

        exporter(store).export(fullLog, questions, Phase.EVENING, treeUri = "content://tree")

        val content = store.files["2026-09-09.md"]!!
        assertThat(content).contains("## Morning")
        assertThat(content).contains("## Evening")
        assertThat(content).contains("**Led:** Chose the schema")
        assertThat(content.split("## Morning")).hasSize(2)   // not duplicated
    }

    @Test
    fun `re-exporting the same phase replaces rather than duplicates`() = runTest {
        val store = FakeStore()
        val ex = exporter(store)
        ex.export(morningLog, questions, Phase.MORNING, "content://tree")
        ex.export(
            morningLog.copy(mission = "Revised mission"),
            questions, Phase.MORNING, "content://tree",
        )

        val content = store.files["2026-09-09.md"]!!
        assertThat(content.split("## Morning")).hasSize(2)
        assertThat(content).contains("Revised mission")
        assertThat(content).doesNotContain("Ship the plan")
    }

    @Test
    fun `preserves hand-written content already in the file`() = runTest {
        val store = FakeStore(
            mapOf("2026-09-09.md" to "# Daily Anchor - 2026-09-09\n\nA note I typed in Joplin.\n")
        )

        exporter(store).export(morningLog, questions, Phase.MORNING, "content://tree")

        assertThat(store.files["2026-09-09.md"]).contains("A note I typed in Joplin.")
    }

    @Test
    fun `returns NoDirectoryConfigured when the tree uri is null`() = runTest {
        val result = exporter(FakeStore())
            .export(morningLog, questions, Phase.MORNING, treeUri = null)
        assertThat(result).isEqualTo(ExportResult.NoDirectoryConfigured)
    }

    @Test
    fun `returns NoDirectoryConfigured when the store cannot be opened`() = runTest {
        val result = MarkdownExporter { null }
            .export(morningLog, questions, Phase.MORNING, treeUri = "content://revoked")
        assertThat(result).isEqualTo(ExportResult.NoDirectoryConfigured)
    }

    @Test
    fun `a failed write returns Failed and does not throw`() = runTest {
        val result = exporter(FakeStore(failWrites = true))
            .export(morningLog, questions, Phase.MORNING, "content://tree")
        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
    }

    @Test
    fun `the returned content matches what was written, for Joplin to reuse`() = runTest {
        val store = FakeStore()
        val result = exporter(store)
            .export(fullLog, questions, Phase.EVENING, "content://tree") as ExportResult.Written

        assertThat(result.content).isEqualTo(store.files["2026-09-09.md"])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.data.export.MarkdownExporterTest'`
Expected: FAIL — `Unresolved reference: MarkdownExporter`.

- [ ] **Step 3: Write `MarkdownExporter.kt`**

```kotlin
package com.anchor.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.Phase
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExportResult {
    data class Written(val fileName: String, val content: String) : ExportResult
    data object NoDirectoryConfigured : ExportResult
    data class Failed(val reason: String) : ExportResult
}

/**
 * The seam between the export logic and Android's Storage Access Framework.
 * Unit tests substitute an in-memory implementation.
 */
interface DocumentStore {
    fun read(fileName: String): String?
    /** Returns false on any failure; never throws. */
    fun write(fileName: String, content: String): Boolean
}

/** SAF-backed store rooted at a persisted tree URI. */
class SafDocumentStore(
    private val context: Context,
    private val treeUri: Uri,
) : DocumentStore {

    private fun root(): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canWrite() }

    override fun read(fileName: String): String? = runCatching {
        val file = root()?.findFile(fileName) ?: return null
        context.contentResolver.openInputStream(file.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    override fun write(fileName: String, content: String): Boolean = runCatching {
        val dir = root() ?: return false
        // "w" truncates; we always write the whole merged document.
        val file = dir.findFile(fileName)
            ?: dir.createFile("text/markdown", fileName)
            ?: return false
        context.contentResolver.openOutputStream(file.uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrDefault(false)
}

/**
 * Writes one Markdown file per day into the user's chosen folder, merging the
 * morning and evening sections into a single document.
 *
 * @param storeFactory builds a [DocumentStore] for a tree URI string, or
 *   returns null when the URI is unusable (permission revoked, folder deleted).
 */
@Singleton
class MarkdownExporter @Inject constructor(
    private val storeFactory: (String) -> DocumentStore?,
) {
    suspend fun export(
        log: DailyLog,
        questions: List<CustomQuestion>,
        phase: Phase,
        treeUri: String?,
    ): ExportResult {
        if (treeUri.isNullOrBlank()) return ExportResult.NoDirectoryConfigured
        val store = storeFactory(treeUri) ?: return ExportResult.NoDirectoryConfigured

        val fileName = "${log.date}.md"
        val section = MarkdownRenderer.renderSection(phase, log, questions)
            ?: return ExportResult.Failed("No answers to write for $phase")

        val existing = store.read(fileName)
        val content = if (existing.isNullOrBlank()) {
            MarkdownRenderer.render(log, questions)
        } else {
            MarkdownRenderer.mergeInto(existing, section, phase)
        }

        return if (store.write(fileName, content)) {
            ExportResult.Written(fileName, content)
        } else {
            ExportResult.Failed("Could not write $fileName")
        }
    }
}
```

- [ ] **Step 4: Provide the store factory in `AppModule.kt`**

Append inside `object AppModule`:

```kotlin
    @Provides
    @Singleton
    fun provideDocumentStoreFactory(
        @ApplicationContext context: Context,
    ): (String) -> DocumentStore? = { treeUri ->
        runCatching { SafDocumentStore(context, Uri.parse(treeUri)) }.getOrNull()
    }
```

Imports: `android.net.Uri`, `com.anchor.data.export.DocumentStore`, `com.anchor.data.export.SafDocumentStore`.

> Hilt can inject a `(String) -> DocumentStore?` because it is a concrete
> `Function1` type. If the KSP processor complains about the function type,
> wrap it in a named `fun interface DocumentStoreFactory { operator fun invoke(treeUri: String): DocumentStore? }`
> and update `MarkdownExporter`'s constructor and the test's `exporter()`
> helper to match.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.data.export.MarkdownExporterTest'`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: export daily Markdown to a SAF-selected folder"
```

---

### Task 11: Joplin push with silent fallback

**Files:**
- Create: `app/src/main/java/com/anchor/data/export/JoplinApi.kt`
- Create: `app/src/main/java/com/anchor/data/export/JoplinExporter.kt`
- Create: `app/src/main/java/com/anchor/data/export/CheckInExporter.kt`
- Modify: `app/src/main/java/com/anchor/di/AppModule.kt` (provide `JoplinApi`)
- Test: `app/src/test/java/com/anchor/data/export/JoplinExporterTest.kt`
- Test: `app/src/test/java/com/anchor/data/export/CheckInExporterTest.kt`

**Interfaces:**
- Consumes: `MarkdownExporter`, `ExportResult` (Task 10), `AnchorSettings` (Task 4).
- Produces:
  - `interface JoplinApi { suspend fun createNote(@Url url: String, @Body body: JoplinNoteRequest): JoplinNoteResponse }`
  - `data class JoplinNoteRequest(title: String, body: String)`, `data class JoplinNoteResponse(id: String)`
  - `class JoplinExporter(api: JoplinApi)` with `suspend fun push(title: String, body: String, settings: AnchorSettings): Boolean`
  - `class CheckInExporter(markdown: MarkdownExporter, joplin: JoplinExporter)` with `suspend fun export(log, questions, phase, settings): ExportResult`

- [ ] **Step 1: Write the failing `JoplinExporterTest`**

`app/src/test/java/com/anchor/data/export/JoplinExporterTest.kt`:

```kotlin
package com.anchor.data.export

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class JoplinExporterTest {

    private lateinit var server: MockWebServer
    private lateinit var exporter: JoplinExporter

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(JoplinApi::class.java)
        exporter = JoplinExporter(api)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = AnchorSettings(
        joplinBaseUrl = server.url("/").toString().trimEnd('/'),
        joplinToken = "jop-token",
    )

    @Test
    fun `posts the note to slash notes with the token as a query parameter`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"abc123"}"""))

        val pushed = exporter.push("Daily Anchor - 2026-09-09", "# body", settings())

        assertThat(pushed).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/notes?token=jop-token")
        val sent = request.body.readUtf8()
        assertThat(sent).contains("Daily Anchor - 2026-09-09")
        assertThat(sent).contains("# body")
    }

    @Test
    fun `returns false without a request when Joplin is not configured`() = runTest {
        assertThat(exporter.push("t", "b", AnchorSettings())).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `returns false when the token is missing`() = runTest {
        val partial = settings().copy(joplinToken = "")
        assertThat(exporter.push("t", "b", partial)).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 500 returns false instead of throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(exporter.push("t", "b", settings())).isFalse()
    }

    @Test
    fun `a network failure returns false instead of throwing`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(exporter.push("t", "b", settings())).isFalse()
    }
}
```

- [ ] **Step 2: Write the failing `CheckInExporterTest`**

`app/src/test/java/com/anchor/data/export/CheckInExporterTest.kt`:

```kotlin
package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CheckInExporterTest {

    private class RecordingMarkdown(private val result: ExportResult) :
        MarkdownExporter({ null }) {
        var calls = 0
        override suspend fun export(
            log: DailyLog,
            questions: List<CustomQuestion>,
            phase: Phase,
            treeUri: String?,
        ): ExportResult {
            calls++
            return result
        }
    }

    private class RecordingJoplin(private val ok: Boolean) : JoplinExporter(FakeApi) {
        var pushedTitle: String? = null
        var pushedBody: String? = null
        override suspend fun push(title: String, body: String, settings: AnchorSettings): Boolean {
            pushedTitle = title
            pushedBody = body
            return ok
        }
        private object FakeApi : JoplinApi {
            override suspend fun createNote(url: String, body: JoplinNoteRequest) =
                error("not called")
        }
    }

    private val log = DailyLog(date = "2026-09-09", mission = "Ship", avoiding = "Email")
    private val questions = DefaultQuestions.ALL

    @Test
    fun `writes locally then pushes the same content to Joplin`() = runTest {
        val written = ExportResult.Written("2026-09-09.md", "# Daily Anchor - 2026-09-09\n")
        val joplin = RecordingJoplin(ok = true)
        val exporter = CheckInExporter(RecordingMarkdown(written), joplin)

        val result = exporter.export(log, questions, Phase.MORNING, AnchorSettings())

        assertThat(result).isEqualTo(written)
        assertThat(joplin.pushedTitle).isEqualTo("Daily Anchor - 2026-09-09")
        assertThat(joplin.pushedBody).isEqualTo("# Daily Anchor - 2026-09-09\n")
    }

    @Test
    fun `a Joplin failure does not change the local result`() = runTest {
        val written = ExportResult.Written("2026-09-09.md", "content")
        val exporter = CheckInExporter(RecordingMarkdown(written), RecordingJoplin(ok = false))

        assertThat(exporter.export(log, questions, Phase.MORNING, AnchorSettings()))
            .isEqualTo(written)
    }

    @Test
    fun `Joplin is still attempted when no local directory is configured`() = runTest {
        val joplin = RecordingJoplin(ok = true)
        val exporter = CheckInExporter(
            RecordingMarkdown(ExportResult.NoDirectoryConfigured), joplin
        )

        val result = exporter.export(log, questions, Phase.MORNING, AnchorSettings())

        // Joplin gets the freshly rendered document instead of the file content.
        assertThat(joplin.pushedBody).contains("**Mission:** Ship")
        assertThat(result).isEqualTo(ExportResult.NoDirectoryConfigured)
    }

    @Test
    fun `a local write failure still attempts Joplin`() = runTest {
        val joplin = RecordingJoplin(ok = true)
        val exporter = CheckInExporter(RecordingMarkdown(ExportResult.Failed("disk")), joplin)

        exporter.export(log, questions, Phase.MORNING, AnchorSettings())

        assertThat(joplin.pushedBody).isNotNull()
    }
}
```

- [ ] **Step 3: Run both tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.anchor.data.export.JoplinExporterTest' --tests 'com.anchor.data.export.CheckInExporterTest'`
Expected: FAIL — `Unresolved reference: JoplinApi`.

- [ ] **Step 4: Write `JoplinApi.kt`**

```kotlin
package com.anchor.data.export

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

@Serializable
data class JoplinNoteRequest(
    val title: String,
    val body: String,
)

@Serializable
data class JoplinNoteResponse(
    val id: String = "",
)

/**
 * Joplin's Web Clipper REST API. The token is a query parameter, not a
 * header, so it is baked into the @Url the caller builds.
 */
interface JoplinApi {

    @POST
    suspend fun createNote(
        @Url url: String,
        @Body body: JoplinNoteRequest,
    ): JoplinNoteResponse
}
```

- [ ] **Step 5: Write `JoplinExporter.kt`**

```kotlin
package com.anchor.data.export

import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort push to Joplin. Every failure is swallowed — the spec requires
 * a silent fallback to local-file-only export, and a check-in must never be
 * blocked by an unreachable note server.
 */
@Singleton
open class JoplinExporter @Inject constructor(
    private val api: JoplinApi,
) {
    open suspend fun push(title: String, body: String, settings: AnchorSettings): Boolean {
        if (settings.joplinBaseUrl.isBlank() || settings.joplinToken.isBlank()) return false
        val url = "${settings.joplinBaseUrl.trimEnd('/')}/notes?token=${settings.joplinToken}"
        return try {
            api.createNote(url, JoplinNoteRequest(title = title, body = body))
            true
        } catch (t: Throwable) {
            false
        }
    }
}
```

- [ ] **Step 6: Write `CheckInExporter.kt`**

```kotlin
package com.anchor.data.export

import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.Phase
import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point for "this check-in is done, persist it outward".
 * Local file first (it is the durable copy), Joplin second and optional.
 */
@Singleton
class CheckInExporter @Inject constructor(
    private val markdown: MarkdownExporter,
    private val joplin: JoplinExporter,
) {
    suspend fun export(
        log: DailyLog,
        questions: List<CustomQuestion>,
        phase: Phase,
        settings: AnchorSettings,
    ): ExportResult {
        val local = markdown.export(log, questions, phase, settings.exportTreeUri)

        // Prefer the exact bytes we wrote; fall back to a fresh render so a
        // missing or unwritable folder still produces a Joplin note.
        val body = (local as? ExportResult.Written)?.content
            ?: MarkdownRenderer.render(log, questions)

        joplin.push(
            title = "Daily Anchor - ${log.date}",
            body = body,
            settings = settings,
        )

        return local
    }
}
```

- [ ] **Step 7: Make `MarkdownExporter` fakeable**

Modify `MarkdownExporter.kt`: change `class MarkdownExporter` to
`open class MarkdownExporter` and `suspend fun export` to `open suspend fun export`.

- [ ] **Step 8: Provide `JoplinApi` in `AppModule.kt`**

```kotlin
    @Provides
    @Singleton
    fun provideJoplinApi(retrofit: Retrofit): JoplinApi = retrofit.create(JoplinApi::class.java)
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.anchor.data.export.*'`
Expected: PASS (31 tests across the three export test classes).

- [ ] **Step 10: Run the whole suite**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: add Joplin push with silent fallback to local export"
```

---

## Phase 4 — The Decision Engine

This phase contains the app's actual behaviour. Everything here is pure or
near-pure and fully unit-tested; Phase 5's services do nothing but call into
it. If a bug is ever reported about "it blocked when it shouldn't have", the
failing test belongs in this phase.

### Task 12: `MorningGate` — should the lockdown fire?

**Files:**
- Create: `app/src/main/java/com/anchor/domain/MorningGate.kt`
- Test: `app/src/test/java/com/anchor/domain/MorningGateTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository`, `AnchorSettings` (Task 4), `HomeAssistantClient`, `LocationGate`, `Presence`, `KillSwitch`, `OverrideStatus` (Tasks 5–7), `DailyLogDao` (Task 2), `AnchorDate`, `TimeWindow` (Task 8).
- Produces:
  - `sealed interface MorningDecision { data object Lock : MorningDecision; data class Skip(val reason: SkipReason) : MorningDecision }`
  - `enum class SkipReason { OUTSIDE_WINDOW, ALREADY_COMPLETED, OVERRIDE_ACTIVE, NOT_IN_SCOPE, LOCATION_UNKNOWN }`
  - `class MorningGate(...)` with `suspend fun decide(): MorningDecision`

**Order of checks (cheapest and most restrictive first):**
1. Inside the morning window? → `OUTSIDE_WINDOW`
2. Already completed today? → `ALREADY_COMPLETED`
3. Kill switch active? → `OVERRIDE_ACTIVE`
4. Location in scope? → `NOT_IN_SCOPE` / `LOCATION_UNKNOWN`
5. Otherwise → `Lock`

The kill switch is checked before the location call so that an override
short-circuits network work, and — critically — is checked *at decision time*,
never cached.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/domain/MorningGateTest.kt`:

```kotlin
package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.DailyLog
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.HomeAssistantApi
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class MorningGateTest {

    private lateinit var db: AnchorDatabase

    /** Returns a canned HaResult per entity id. */
    private class FakeClient(
        private val byEntity: Map<String, HaResult>,
    ) : HomeAssistantClient(NoApi) {
        var calls = mutableListOf<String>()
        override suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult {
            calls += entityId
            return byEntity[entityId] ?: HaResult.Unavailable
        }
        private object NoApi : HomeAssistantApi {
            override suspend fun state(url: String, authorization: String) = error("unused")
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val zone = ZoneId.of("America/Los_Angeles")

    /** 2026-09-09 07:00 local. */
    private val morningInstant = Instant.parse("2026-09-09T14:00:00Z")
    /** 2026-09-09 15:00 local. */
    private val afternoonInstant = Instant.parse("2026-09-09T22:00:00Z")

    private fun state(s: String, friendly: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendly
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) } ?: emptyMap()
        return HaResult.Ok(HaStateDto("e", s, attrs))
    }

    private fun gate(
        instant: Instant = morningInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
    ): Pair<MorningGate, FakeClient> {
        val client = FakeClient(haStates)
        val clock = Clock.fixed(instant, zone)
        val gate = MorningGate(
            settingsProvider = { settings },
            dailyLogDao = db.dailyLogDao(),
            client = client,
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
        )
        return gate to client
    }

    private fun configured() = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
    )

    @Test
    fun `locks in the window, at home, not yet completed`() = runTest {
        val (g, _) = gate()
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `skips outside the morning window`() = runTest {
        val (g, _) = gate(instant = afternoonInstant)
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `skips when today's morning is already completed`() = runTest {
        db.dailyLogDao().upsert(
            DailyLog(date = "2026-09-09", mission = "done", morningCompletedAt = 1L)
        )
        val (g, _) = gate()
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `a row with no morningCompletedAt does not count as completed`() = runTest {
        db.dailyLogDao().upsert(DailyLog(date = "2026-09-09", led = "evening only"))
        val (g, _) = gate()
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `skips when the kill switch override is active`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, _) = gate(
            settings = settings,
            haStates = mapOf(
                "input_boolean.anchor_override" to state("on"),
                "device_tracker.pixel" to state("home"),
            ),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `an inactive kill switch does not prevent locking`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, _) = gate(
            settings = settings,
            haStates = mapOf(
                "input_boolean.anchor_override" to state("off"),
                "device_tracker.pixel" to state("home"),
            ),
        )
        assertThat(g.decide()).isEqualTo(MorningDecision.Lock)
    }

    @Test
    fun `the kill switch is checked before the location, saving a call`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val (g, client) = gate(
            settings = settings,
            haStates = mapOf("input_boolean.anchor_override" to state("on")),
        )
        g.decide()
        assertThat(client.calls).containsExactly("input_boolean.anchor_override")
    }

    @Test
    fun `skips when the user is not home`() = runTest {
        val (g, _) = gate(haStates = mapOf("device_tracker.pixel" to state("not_home")))
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `FAIL-OPEN - skips when Home Assistant is unreachable`() = runTest {
        val (g, _) = gate(haStates = mapOf("device_tracker.pixel" to HaResult.Unavailable))
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN))
    }

    @Test
    fun `FAIL-OPEN - skips when Home Assistant is not configured at all`() = runTest {
        val (g, _) = gate(settings = AnchorSettings())
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN))
    }

    @Test
    fun `specific rooms mode locks only in an allowed room`() = runTest {
        val settings = configured().copy(
            morningLocationMode = LocationMode.SPECIFIC_ROOMS,
            morningAllowedRooms = listOf("Bedroom", "Office"),
        )
        val inBedroom = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Bedroom Tracker")),
        ).first
        val inKitchen = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Kitchen Tracker")),
        ).first

        assertThat(inBedroom.decide()).isEqualTo(MorningDecision.Lock)
        assertThat(inKitchen.decide()).isEqualTo(MorningDecision.Skip(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `honours a custom morning window`() = runTest {
        // 07:00 local, window moved to 08:00-12:00 -> outside.
        val settings = configured().copy(morningStartMinute = 8 * 60)
        val (g, _) = gate(settings = settings)
        assertThat(g.decide()).isEqualTo(MorningDecision.Skip(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `evaluates the window before making any network call`() = runTest {
        val (g, client) = gate(instant = afternoonInstant)
        g.decide()
        assertThat(client.calls).isEmpty()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.domain.MorningGateTest'`
Expected: FAIL — `Unresolved reference: MorningGate`.

- [ ] **Step 3: Write `MorningGate.kt`**

```kotlin
package com.anchor.domain

import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

enum class SkipReason {
    OUTSIDE_WINDOW,
    ALREADY_COMPLETED,
    OVERRIDE_ACTIVE,
    NOT_IN_SCOPE,
    LOCATION_UNKNOWN,
}

sealed interface MorningDecision {
    data object Lock : MorningDecision
    data class Skip(val reason: SkipReason) : MorningDecision
}

/**
 * Decides whether the morning lockdown should fire right now.
 *
 * @param settingsProvider reads a fresh settings snapshot per decision.
 *   Never cache: the schedule and the kill switch can change between calls.
 */
@Singleton
class MorningGate @Inject constructor(
    private val settingsProvider: suspend () -> AnchorSettings,
    private val dailyLogDao: DailyLogDao,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(): MorningDecision {
        val settings = settingsProvider()

        // 1. Cheap, local checks first — no network unless they pass.
        if (!TimeWindow.contains(
                anchorDate.minuteOfDay(),
                settings.morningStartMinute,
                settings.morningEndMinute,
            )
        ) return MorningDecision.Skip(SkipReason.OUTSIDE_WINDOW)

        val today = anchorDate.today()
        if (dailyLogDao.findByDate(today)?.morningCompletedAt != null) {
            return MorningDecision.Skip(SkipReason.ALREADY_COMPLETED)
        }

        // 2. Remote kill switch, checked at the moment of blocking.
        if (killSwitch.isBlockingDisabled(killSwitch.check(settings))) {
            return MorningDecision.Skip(SkipReason.OVERRIDE_ACTIVE)
        }

        // 3. Location gate. Fail-open: anything short of a confirmed
        //    in-scope reading skips the lockdown.
        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.morningLocationMode,
            allowedRooms = settings.morningAllowedRooms,
        )
        return when (presence) {
            Presence.IN_SCOPE -> MorningDecision.Lock
            Presence.OUT_OF_SCOPE -> MorningDecision.Skip(SkipReason.NOT_IN_SCOPE)
            Presence.UNKNOWN -> MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN)
        }
    }
}
```

- [ ] **Step 4: Provide the settings lambda in `AppModule.kt`**

```kotlin
    @Provides
    fun provideSettingsProvider(
        repository: SettingsRepository,
    ): suspend () -> AnchorSettings = { repository.current() }
```

Import `com.anchor.data.settings.SettingsRepository` and `com.anchor.data.settings.AnchorSettings`.

> If Hilt cannot bind the `suspend () -> AnchorSettings` function type (it
> desugars to `Function1<Continuation, Object>`, which can collide), replace
> it with `fun interface SettingsProvider { suspend operator fun invoke(): AnchorSettings }`
> in `data/settings/`, and update `MorningGate`, `EveningGate` and their
> tests to use it. Decide this once, in this step, and apply it consistently.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.domain.MorningGateTest'`
Expected: PASS (13 tests).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add morning lockdown decision gate"
```

---

### Task 13: `EveningGate` — strict overlay, simple delay, or nothing

**Files:**
- Create: `app/src/main/java/com/anchor/domain/EveningGate.kt`
- Test: `app/src/test/java/com/anchor/domain/EveningGateTest.kt`

**Interfaces:**
- Consumes: same collaborators as `MorningGate`.
- Produces:
  - `sealed interface EveningDecision { data object Strict : EveningDecision; data object SimpleDelay : EveningDecision; data class Allow(val reason: SkipReason) : EveningDecision }`
  - `class EveningGate(...)` with `suspend fun decide(packageName: String): EveningDecision`

**Semantics:** `Strict` shows the three-question overlay. `SimpleDelay` shows
the 5-second interstitial (the fail-open path). `Allow` lets the app open
untouched — the package isn't blocked, we're outside the window, tonight's
questions are already answered, or the override is on.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/domain/EveningGateTest.kt`:

```kotlin
package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.DailyLog
import com.anchor.data.ha.HaResult
import com.anchor.data.ha.HaStateDto
import com.anchor.data.ha.HomeAssistantApi
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class EveningGateTest {

    private lateinit var db: AnchorDatabase

    private class FakeClient(private val byEntity: Map<String, HaResult>) :
        HomeAssistantClient(NoApi) {
        override suspend fun fetchState(baseUrl: String, token: String, entityId: String) =
            byEntity[entityId] ?: HaResult.Unavailable
        private object NoApi : HomeAssistantApi {
            override suspend fun state(url: String, authorization: String) = error("unused")
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val zone = ZoneId.of("America/Los_Angeles")
    private val YOUTUBE = "com.google.android.youtube"

    /** 2026-09-09 22:00 local. */
    private val nightInstant = Instant.parse("2026-09-10T05:00:00Z")
    /** 2026-09-10 01:00 local — still "the 9th's" evening. */
    private val afterMidnightInstant = Instant.parse("2026-09-10T08:00:00Z")
    /** 2026-09-09 15:00 local. */
    private val afternoonInstant = Instant.parse("2026-09-09T22:00:00Z")

    private fun state(s: String, friendly: String? = null): HaResult.Ok {
        val attrs: Map<String, JsonElement> = friendly
            ?.let { mapOf("friendly_name" to Json.parseToJsonElement("\"$it\"")) } ?: emptyMap()
        return HaResult.Ok(HaStateDto("e", s, attrs))
    }

    private fun configured() = AnchorSettings(
        haBaseUrl = "http://ha.local:8123",
        haToken = "t",
        haDeviceTrackerEntityId = "device_tracker.pixel",
        blockedPackages = setOf(YOUTUBE, "com.instagram.android"),
    )

    private fun gate(
        instant: Instant = nightInstant,
        settings: AnchorSettings = configured(),
        haStates: Map<String, HaResult> = mapOf("device_tracker.pixel" to state("home")),
    ): EveningGate {
        val client = FakeClient(haStates)
        val clock = Clock.fixed(instant, zone)
        return EveningGate(
            settingsProvider = { settings },
            dailyLogDao = db.dailyLogDao(),
            client = client,
            killSwitch = KillSwitch(client),
            anchorDate = AnchorDate(clock),
        )
    }

    @Test
    fun `strict overlay for a blocked app at home during the window`() = runTest {
        assertThat(gate().decide(YOUTUBE)).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `allows an app that is not on the blocked list`() = runTest {
        assertThat(gate().decide("com.android.calculator2"))
            .isEqualTo(EveningDecision.Allow(SkipReason.NOT_IN_SCOPE))
    }

    @Test
    fun `allows a blocked app outside the evening window`() = runTest {
        assertThat(gate(instant = afternoonInstant).decide(YOUTUBE))
            .isEqualTo(EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW))
    }

    @Test
    fun `blocks after midnight, which is still inside the wrapping window`() = runTest {
        assertThat(gate(instant = afterMidnightInstant).decide(YOUTUBE))
            .isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `allows once tonight's evening check-in is complete`() = runTest {
        db.dailyLogDao().upsert(
            DailyLog(date = "2026-09-09", led = "x", eveningCompletedAt = 1L)
        )
        assertThat(gate().decide(YOUTUBE))
            .isEqualTo(EveningDecision.Allow(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `a 10pm completion still counts at 1am the next morning`() = runTest {
        // Completed against the 9th; at 01:00 on the 10th the anchor date is
        // still the 9th, so the block stays lifted for the rest of the night.
        db.dailyLogDao().upsert(
            DailyLog(date = "2026-09-09", led = "x", eveningCompletedAt = 1L)
        )
        assertThat(gate(instant = afterMidnightInstant).decide(YOUTUBE))
            .isEqualTo(EveningDecision.Allow(SkipReason.ALREADY_COMPLETED))
    }

    @Test
    fun `the block resets the next night`() = runTest {
        db.dailyLogDao().upsert(
            DailyLog(date = "2026-09-08", led = "yesterday", eveningCompletedAt = 1L)
        )
        assertThat(gate().decide(YOUTUBE)).isEqualTo(EveningDecision.Strict)
    }

    @Test
    fun `the kill switch allows the app through entirely`() = runTest {
        val settings = configured().copy(
            killSwitchEnabled = true,
            killSwitchEntityId = "input_boolean.anchor_override",
            killSwitchOverrideState = "on",
        )
        val decision = gate(
            settings = settings,
            haStates = mapOf(
                "input_boolean.anchor_override" to state("on"),
                "device_tracker.pixel" to state("home"),
            ),
        ).decide(YOUTUBE)

        assertThat(decision).isEqualTo(EveningDecision.Allow(SkipReason.OVERRIDE_ACTIVE))
    }

    @Test
    fun `FAIL-OPEN - simple delay when Home Assistant is unreachable`() = runTest {
        val decision = gate(
            haStates = mapOf("device_tracker.pixel" to HaResult.Unavailable)
        ).decide(YOUTUBE)
        assertThat(decision).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `FAIL-OPEN - simple delay when Home Assistant is not configured`() = runTest {
        assertThat(gate(settings = AnchorSettings(blockedPackages = setOf(YOUTUBE))).decide(YOUTUBE))
            .isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `simple delay when the user is away from home`() = runTest {
        val decision = gate(
            haStates = mapOf("device_tracker.pixel" to state("not_home"))
        ).decide(YOUTUBE)
        assertThat(decision).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `specific rooms mode is strict only in a restricted room`() = runTest {
        val settings = configured().copy(
            eveningLocationMode = LocationMode.SPECIFIC_ROOMS,
            eveningAllowedRooms = listOf("Bedroom", "Living Room"),
        )
        val inBedroom = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Bedroom Sensor")),
        ).decide(YOUTUBE)
        val inGarage = gate(
            settings = settings,
            haStates = mapOf("device_tracker.pixel" to state("home", "Garage Sensor")),
        ).decide(YOUTUBE)

        assertThat(inBedroom).isEqualTo(EveningDecision.Strict)
        assertThat(inGarage).isEqualTo(EveningDecision.SimpleDelay)
    }

    @Test
    fun `never blocks the Anchor app itself`() = runTest {
        val settings = configured().copy(blockedPackages = configured().blockedPackages + "com.anchor")
        assertThat(gate(settings = settings).decide("com.anchor"))
            .isEqualTo(EveningDecision.Allow(SkipReason.NOT_IN_SCOPE))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.domain.EveningGateTest'`
Expected: FAIL — `Unresolved reference: EveningGate`.

- [ ] **Step 3: Write `EveningGate.kt`**

```kotlin
package com.anchor.domain

import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EveningDecision {
    /** Show the three-question overlay. */
    data object Strict : EveningDecision
    /** Show the 5-second interstitial — the fail-open path. */
    data object SimpleDelay : EveningDecision
    /** Let the app open untouched. */
    data class Allow(val reason: SkipReason) : EveningDecision
}

@Singleton
class EveningGate @Inject constructor(
    private val settingsProvider: suspend () -> AnchorSettings,
    private val dailyLogDao: DailyLogDao,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(packageName: String): EveningDecision {
        val settings = settingsProvider()

        // Never intercept ourselves — that would be an infinite relaunch loop.
        if (packageName == OWN_PACKAGE) {
            return EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)
        }
        if (packageName !in settings.blockedPackages) {
            return EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)
        }
        if (!TimeWindow.contains(
                anchorDate.minuteOfDay(),
                settings.eveningStartMinute,
                settings.eveningEndMinute,
            )
        ) return EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW)

        // Which night this belongs to — 01:00 still counts as the previous
        // evening, so answering at 22:00 keeps the block lifted until 05:00.
        val night = anchorDate.eveningAnchorDate(settings.eveningEndMinute)
        if (dailyLogDao.findByDate(night)?.eveningCompletedAt != null) {
            return EveningDecision.Allow(SkipReason.ALREADY_COMPLETED)
        }

        if (killSwitch.isBlockingDisabled(killSwitch.check(settings))) {
            return EveningDecision.Allow(SkipReason.OVERRIDE_ACTIVE)
        }

        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.eveningLocationMode,
            allowedRooms = settings.eveningAllowedRooms,
        )
        return when (presence) {
            Presence.IN_SCOPE -> EveningDecision.Strict
            // Both "away" and "HA is down" get the lighter treatment.
            Presence.OUT_OF_SCOPE, Presence.UNKNOWN -> EveningDecision.SimpleDelay
        }
    }

    companion object {
        const val OWN_PACKAGE = "com.anchor"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.domain.EveningGateTest'`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add evening app-blocking decision gate"
```

---

### Task 14: `ForegroundAppDecider` — the allowlist during lockdown

**Files:**
- Create: `app/src/main/java/com/anchor/domain/ForegroundAppDecider.kt`
- Test: `app/src/test/java/com/anchor/domain/ForegroundAppDeciderTest.kt`

**Interfaces:**
- Consumes: `AnchorSettings` (Task 4).
- Produces:
  - `sealed interface ForegroundAction { data object Ignore : ForegroundAction; data object ReassertMorningLock : ForegroundAction; data class EvaluateEvening(val packageName: String) : ForegroundAction }`
  - `object ForegroundAppDecider { fun decide(packageName: String, morningLockActive: Boolean, settings: AnchorSettings): ForegroundAction; val ALWAYS_ALLOWED_PREFIXES: Set<String>; fun defaultAllowlist(dialer: String?, sms: String?): Set<String> }`

This is the safety-critical allowlist logic from spec §1: during a morning
lockdown the phone dialer, the SMS app, the system UI, and anything the user
allowlisted must never be interrupted. Getting this wrong means a phone that
cannot make an emergency call.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/domain/ForegroundAppDeciderTest.kt`:

```kotlin
package com.anchor.domain

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForegroundAppDeciderTest {

    private val settings = AnchorSettings(
        blockedPackages = setOf("com.google.android.youtube"),
        allowlistPackages = setOf("com.android.dialer", "com.google.android.apps.messaging"),
    )

    private fun decide(pkg: String, locked: Boolean) =
        ForegroundAppDecider.decide(pkg, morningLockActive = locked, settings = settings)

    // --- Morning lockdown active ---

    @Test
    fun `reasserts the lock when an ordinary app comes to the foreground`() {
        assertThat(decide("com.google.android.youtube", locked = true))
            .isEqualTo(ForegroundAction.ReassertMorningLock)
    }

    @Test
    fun `reasserts the lock for the launcher`() {
        assertThat(decide("com.google.android.apps.nexuslauncher", locked = true))
            .isEqualTo(ForegroundAction.ReassertMorningLock)
    }

    @Test
    fun `does NOT interrupt the dialer`() {
        assertThat(decide("com.android.dialer", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt the messaging app`() {
        assertThat(decide("com.google.android.apps.messaging", locked = true))
            .isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt an in-call UI even when not explicitly allowlisted`() {
        assertThat(decide("com.android.incallui", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt the emergency dialer`() {
        assertThat(decide("com.android.emergency", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt system UI`() {
        assertThat(decide("com.android.systemui", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt itself`() {
        assertThat(decide("com.anchor", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `does NOT interrupt a user-added allowlist entry`() {
        val withTorch = settings.copy(allowlistPackages = settings.allowlistPackages + "com.torch.app")
        val action = ForegroundAppDecider.decide("com.torch.app", true, withTorch)
        assertThat(action).isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `ignores a blank package name`() {
        assertThat(decide("", locked = true)).isEqualTo(ForegroundAction.Ignore)
    }

    // --- No morning lockdown ---

    @Test
    fun `defers to the evening gate when not locked down`() {
        assertThat(decide("com.google.android.youtube", locked = false))
            .isEqualTo(ForegroundAction.EvaluateEvening("com.google.android.youtube"))
    }

    @Test
    fun `an allowlisted app is never evaluated for the evening block`() {
        assertThat(decide("com.android.dialer", locked = false))
            .isEqualTo(ForegroundAction.Ignore)
    }

    @Test
    fun `system packages are never evaluated for the evening block`() {
        assertThat(decide("com.android.systemui", locked = false))
            .isEqualTo(ForegroundAction.Ignore)
    }

    // --- Default allowlist construction ---

    @Test
    fun `the default allowlist includes the resolved dialer and sms apps`() {
        val allowlist = ForegroundAppDecider.defaultAllowlist(
            dialer = "com.android.dialer",
            sms = "com.google.android.apps.messaging",
        )
        assertThat(allowlist).contains("com.android.dialer")
        assertThat(allowlist).contains("com.google.android.apps.messaging")
    }

    @Test
    fun `the default allowlist tolerates unresolvable defaults`() {
        assertThat(ForegroundAppDecider.defaultAllowlist(dialer = null, sms = null)).isEmpty()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.domain.ForegroundAppDeciderTest'`
Expected: FAIL — `Unresolved reference: ForegroundAppDecider`.

- [ ] **Step 3: Write `ForegroundAppDecider.kt`**

```kotlin
package com.anchor.domain

import com.anchor.data.settings.AnchorSettings

sealed interface ForegroundAction {
    /** Leave the foreground app alone. */
    data object Ignore : ForegroundAction
    /** The morning lock is up and something escaped it — bring it back. */
    data object ReassertMorningLock : ForegroundAction
    /** Hand this package to [EveningGate]. */
    data class EvaluateEvening(val packageName: String) : ForegroundAction
}

/**
 * Pure classification of a foreground-app change.
 *
 * The allowlist here is a safety feature, not a convenience: during a morning
 * lockdown the user must always be able to place a call, send a message, and
 * reach system surfaces. Anything ambiguous resolves to [ForegroundAction.Ignore].
 */
object ForegroundAppDecider {

    private const val OWN_PACKAGE = "com.anchor"

    /**
     * Never interrupted, regardless of settings. Matched as prefixes so OEM
     * variants (com.samsung.android.incallui, com.android.server.telecom, …)
     * are covered too.
     */
    val ALWAYS_ALLOWED_PREFIXES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.dialer",
        "com.android.emergency",
        "com.android.settings",      // so the user can always reach Settings
        "android",
    )

    fun decide(
        packageName: String,
        morningLockActive: Boolean,
        settings: AnchorSettings,
    ): ForegroundAction {
        if (packageName.isBlank()) return ForegroundAction.Ignore
        if (packageName == OWN_PACKAGE) return ForegroundAction.Ignore
        if (isAlwaysAllowed(packageName)) return ForegroundAction.Ignore
        if (packageName in settings.allowlistPackages) return ForegroundAction.Ignore

        return if (morningLockActive) {
            ForegroundAction.ReassertMorningLock
        } else {
            ForegroundAction.EvaluateEvening(packageName)
        }
    }

    fun isAlwaysAllowed(packageName: String): Boolean =
        ALWAYS_ALLOWED_PREFIXES.any { packageName == it || packageName.startsWith("$it.") }

    /**
     * Seeds the user's allowlist with the device's actual default dialer and
     * SMS apps, which vary by OEM. Nulls (unresolvable) are simply dropped.
     */
    fun defaultAllowlist(dialer: String?, sms: String?): Set<String> =
        listOfNotNull(dialer, sms).filter { it.isNotBlank() }.toSet()
}
```

Note that `isAlwaysAllowed` uses prefix matching with a dot boundary, so
`com.android.dialer` and `com.android.dialer.foo` match but a hypothetical
`com.android.dialerapp` does not.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.domain.ForegroundAppDeciderTest'`
Expected: PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add foreground app classification with emergency allowlist"
```

---

### Task 15: `SubmitCheckIn` — persist and export a completed check-in

**Files:**
- Create: `app/src/main/java/com/anchor/domain/SubmitCheckIn.kt`
- Test: `app/src/test/java/com/anchor/domain/SubmitCheckInTest.kt`

**Interfaces:**
- Consumes: `DailyLogDao` (Task 2), `CustomQuestionDao`, `Phase` (Task 3), `CheckInExporter` (Task 11), `AnchorDate` (Task 8), `SettingsRepository` (Task 4), `withAnswer` (Task 9).
- Produces:
  - `class SubmitCheckIn(...)` with `suspend operator fun invoke(phase: Phase, answers: Map<String, String>): SubmitResult`
  - `data class SubmitResult(val log: DailyLog, val export: ExportResult)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/domain/SubmitCheckInTest.kt`:

```kotlin
package com.anchor.domain

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.export.CheckInExporter
import com.anchor.data.export.ExportResult
import com.anchor.data.export.JoplinApi
import com.anchor.data.export.JoplinExporter
import com.anchor.data.export.JoplinNoteRequest
import com.anchor.data.export.MarkdownExporter
import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SubmitCheckInTest {

    private lateinit var db: AnchorDatabase
    private lateinit var recorded: MutableList<Pair<Phase, DailyLog>>

    private inner class RecordingExporter : CheckInExporter(
        markdown = object : MarkdownExporter({ null }) {},
        joplin = object : JoplinExporter(NoApi) {},
    ) {
        override suspend fun export(
            log: DailyLog,
            questions: List<CustomQuestion>,
            phase: Phase,
            settings: AnchorSettings,
        ): ExportResult {
            recorded += phase to log
            return ExportResult.Written("${log.date}.md", "content")
        }
    }

    private object NoApi : JoplinApi {
        override suspend fun createNote(url: String, body: JoplinNoteRequest) = error("unused")
    }

    /** 2026-09-09 07:00 local. */
    private val clock = Clock.fixed(
        Instant.parse("2026-09-09T14:00:00Z"), ZoneId.of("America/Los_Angeles")
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        DefaultQuestions.ALL.forEach { db.customQuestionDao().upsert(it) }
        recorded = mutableListOf()
    }

    @After
    fun tearDown() = db.close()

    private fun submit() = SubmitCheckIn(
        dailyLogDao = db.dailyLogDao(),
        questionDao = db.customQuestionDao(),
        exporter = RecordingExporter(),
        anchorDate = AnchorDate(clock),
        settingsProvider = { AnchorSettings() },
    )

    @Test
    fun `saves morning answers to the named columns and stamps the timestamp`() = runTest {
        val result = submit()(
            Phase.MORNING,
            mapOf(SlotKey.MISSION to "Ship the plan", SlotKey.AVOIDING to "The invoice"),
        )

        val saved = db.dailyLogDao().findByDate("2026-09-09")!!
        assertThat(saved.mission).isEqualTo("Ship the plan")
        assertThat(saved.avoiding).isEqualTo("The invoice")
        assertThat(saved.morningCompletedAt).isNotNull()
        assertThat(saved.eveningCompletedAt).isNull()
        assertThat(result.export).isInstanceOf(ExportResult.Written::class.java)
    }

    @Test
    fun `an evening submission merges into the same row as the morning`() = runTest {
        val s = submit()
        s(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship", SlotKey.AVOIDING to "Email"))
        s(
            Phase.EVENING,
            mapOf(SlotKey.LED to "Decided", SlotKey.SOFTENED to "Called", SlotKey.FAKED to "Nodded"),
        )

        assertThat(db.dailyLogDao().recent(10)).hasSize(1)
        val saved = db.dailyLogDao().findByDate("2026-09-09")!!
        assertThat(saved.mission).isEqualTo("Ship")
        assertThat(saved.led).isEqualTo("Decided")
        assertThat(saved.morningCompletedAt).isNotNull()
        assertThat(saved.eveningCompletedAt).isNotNull()
    }

    @Test
    fun `a custom question's answer round-trips through extras`() = runTest {
        val custom = CustomQuestion(
            phase = Phase.MORNING, slotKey = "custom:zz", prompt = "Who?", sortOrder = 9,
        )
        db.customQuestionDao().upsert(custom)

        submit()(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship", "custom:zz" to "Priya"))

        val saved = db.dailyLogDao().findByDate("2026-09-09")!!
        assertThat(saved.extraAnswersJson).contains("Priya")
    }

    @Test
    fun `blank answers are stored as-is rather than dropped`() = runTest {
        // The UI enforces non-blank; the use case must not silently discard.
        submit()(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship", SlotKey.AVOIDING to ""))
        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.avoiding).isEmpty()
    }

    @Test
    fun `it exports the phase that was submitted, with the merged log`() = runTest {
        val s = submit()
        s(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship"))
        s(Phase.EVENING, mapOf(SlotKey.LED to "Decided"))

        assertThat(recorded.map { it.first }).containsExactly(Phase.MORNING, Phase.EVENING).inOrder()
        // The evening export sees the morning answers too, so a fresh file
        // render would still contain both sections.
        assertThat(recorded.last().second.mission).isEqualTo("Ship")
    }

    @Test
    fun `the evening submission uses the evening anchor date`() = runTest {
        // 01:00 local on the 10th -> the night of the 9th.
        val lateClock = Clock.fixed(
            Instant.parse("2026-09-10T08:00:00Z"), ZoneId.of("America/Los_Angeles")
        )
        val s = SubmitCheckIn(
            dailyLogDao = db.dailyLogDao(),
            questionDao = db.customQuestionDao(),
            exporter = RecordingExporter(),
            anchorDate = AnchorDate(lateClock),
            settingsProvider = { AnchorSettings() },
        )

        s(Phase.EVENING, mapOf(SlotKey.LED to "Decided"))

        assertThat(db.dailyLogDao().findByDate("2026-09-09")).isNotNull()
        assertThat(db.dailyLogDao().findByDate("2026-09-10")).isNull()
    }

    @Test
    fun `an export failure still persists the answers`() = runTest {
        val failing = object : CheckInExporter(
            markdown = object : MarkdownExporter({ null }) {},
            joplin = object : JoplinExporter(NoApi) {},
        ) {
            override suspend fun export(
                log: DailyLog, questions: List<CustomQuestion>,
                phase: Phase, settings: AnchorSettings,
            ) = ExportResult.Failed("no folder")
        }
        val s = SubmitCheckIn(
            dailyLogDao = db.dailyLogDao(),
            questionDao = db.customQuestionDao(),
            exporter = failing,
            anchorDate = AnchorDate(clock),
            settingsProvider = { AnchorSettings() },
        )

        val result = s(Phase.MORNING, mapOf(SlotKey.MISSION to "Ship"))

        assertThat(result.export).isInstanceOf(ExportResult.Failed::class.java)
        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.mission).isEqualTo("Ship")
        assertThat(db.dailyLogDao().findByDate("2026-09-09")!!.morningCompletedAt).isNotNull()
    }
}
```

- [ ] **Step 2: Make `CheckInExporter` fakeable**

Modify `CheckInExporter.kt`: change `class CheckInExporter` to
`open class CheckInExporter` and `suspend fun export` to `open suspend fun export`.
Also change `MarkdownExporter`'s and `JoplinExporter`'s constructor parameters
to `protected val` if the compiler complains about subclassing.

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.domain.SubmitCheckInTest'`
Expected: FAIL — `Unresolved reference: SubmitCheckIn`.

- [ ] **Step 4: Write `SubmitCheckIn.kt`**

```kotlin
package com.anchor.domain

import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DailyLogDao
import com.anchor.data.db.Phase
import com.anchor.data.export.CheckInExporter
import com.anchor.data.export.ExportResult
import com.anchor.data.export.withAnswer
import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

data class SubmitResult(
    val log: DailyLog,
    val export: ExportResult,
)

/**
 * The one path a completed check-in takes: merge the answers into the day's
 * row, stamp the phase's completion timestamp, persist, then export.
 *
 * Persistence happens before export, and an export failure never rolls it
 * back — the database is the source of truth and losing an answer because a
 * folder permission expired would be unacceptable.
 */
@Singleton
class SubmitCheckIn @Inject constructor(
    private val dailyLogDao: DailyLogDao,
    private val questionDao: CustomQuestionDao,
    private val exporter: CheckInExporter,
    private val anchorDate: AnchorDate,
    private val settingsProvider: suspend () -> AnchorSettings,
) {
    /** @param answers slotKey -> answer, as collected by the lock screen. */
    suspend operator fun invoke(phase: Phase, answers: Map<String, String>): SubmitResult {
        val settings = settingsProvider()

        val date = when (phase) {
            Phase.MORNING -> anchorDate.today()
            Phase.EVENING -> anchorDate.eveningAnchorDate(settings.eveningEndMinute)
        }

        val now = System.currentTimeMillis()
        val existing = dailyLogDao.findByDate(date) ?: DailyLog(date = date)
        val merged = answers
            .entries
            .fold(existing) { acc, (slot, answer) -> acc.withAnswer(slot, answer) }
            .let {
                when (phase) {
                    Phase.MORNING -> it.copy(morningCompletedAt = now)
                    Phase.EVENING -> it.copy(eveningCompletedAt = now)
                }
            }

        dailyLogDao.upsert(merged)
        val saved = dailyLogDao.findByDate(date) ?: merged

        val export = exporter.export(
            log = saved,
            questions = questionDao.list(phase),
            phase = phase,
            settings = settings,
        )
        return SubmitResult(log = saved, export = export)
    }
}
```

> `System.currentTimeMillis()` here is the one sanctioned exception to the
> injected-clock rule: it is a completion *stamp*, never read back for a
> decision. If you prefer strict purity, add `fun nowMillis(): Long` to
> `AnchorDate` and call that instead — it is a two-line change and the tests
> above pass either way.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.domain.SubmitCheckInTest'`
Expected: PASS (7 tests).

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew :app:test`
Expected: PASS — the entire decision engine is now covered without a device.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add check-in submission use case"
```

---

## Phase 5 — Services and Enforcement

### Task 16: `LockdownEnforcer` and the manifest

**Files:**
- Create: `app/src/main/java/com/anchor/domain/LockdownEnforcer.kt`
- Create: `app/src/main/java/com/anchor/domain/AccessibilityLockdownEnforcer.kt`
- Create: `app/src/main/java/com/anchor/di/EnforcerModule.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/anchor/domain/LockdownStateTest.kt`

**Interfaces:**
- Consumes: `AnchorSettings` (Task 4).
- Produces:
  - `interface LockdownEnforcer { fun begin(); fun end(); val isActive: Boolean; fun reassert() }`
  - `object LockdownState` — the process-wide "is the morning lock up?" flag, readable from the accessibility service and the lock Activity: `fun begin()`, `fun end()`, `val active: Boolean`, `val activeFlow: StateFlow<Boolean>`
  - `class AccessibilityLockdownEnforcer(context: Context) : LockdownEnforcer`

**Why a process-wide object:** the accessibility service and the lock Activity
run in the same process but are separate components with no natural shared
owner. A Hilt `@Singleton` would work equally well; `LockdownState` is
deliberately trivial and injected through the enforcer so the decision logic
never reaches for a global.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/domain/LockdownStateTest.kt`:

```kotlin
package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class LockdownStateTest {

    @After
    fun tearDown() = LockdownState.end()

    @Test
    fun `starts inactive`() {
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `begin activates and end deactivates`() {
        LockdownState.begin()
        assertThat(LockdownState.active).isTrue()
        LockdownState.end()
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `begin is idempotent`() {
        LockdownState.begin()
        LockdownState.begin()
        LockdownState.end()
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `the flow reflects the current value`() = runTest {
        LockdownState.begin()
        assertThat(LockdownState.activeFlow.value).isTrue()
        LockdownState.end()
        assertThat(LockdownState.activeFlow.value).isFalse()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.domain.LockdownStateTest'`
Expected: FAIL — `Unresolved reference: LockdownState`.

- [ ] **Step 3: Write `LockdownEnforcer.kt`**

```kotlin
package com.anchor.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the morning lock screen is currently up. Read by the accessibility
 * service on every foreground-app change, so it must be cheap and lock-free.
 */
object LockdownState {
    private val _activeFlow = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> = _activeFlow.asStateFlow()

    val active: Boolean get() = _activeFlow.value

    fun begin() { _activeFlow.value = true }
    fun end() { _activeFlow.value = false }
}

/**
 * How the morning lockdown is actually enforced. Two implementations are
 * planned:
 *
 *  - [AccessibilityLockdownEnforcer] (Task 16): relaunches the lock Activity
 *    whenever anything else reaches the foreground. Works on a stock device
 *    with no provisioning; a determined user can still escape.
 *  - `DeviceOwnerLockdownEnforcer` (Task 22, optional): true kiosk mode via
 *    lock task. Requires the app to be provisioned as Device Owner.
 */
interface LockdownEnforcer {
    /** Put the lock up and remember that it is up. */
    fun begin()

    /** Take the lock down. */
    fun end()

    val isActive: Boolean

    /** Bring the lock screen back to the foreground. */
    fun reassert()
}
```

- [ ] **Step 4: Write `AccessibilityLockdownEnforcer.kt`**

```kotlin
package com.anchor.domain

import android.content.Context
import android.content.Intent
import com.anchor.ui.lock.MorningLockActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Relaunch-based enforcement: whenever the accessibility service sees another
 * app reach the foreground while [LockdownState] is active, it calls
 * [reassert], which brings the lock Activity straight back.
 *
 * Known limitation, accepted for a personal app: the user sees a brief flash
 * of whatever they switched to. Task 22 adds a Device Owner implementation
 * for genuine kiosk behaviour.
 */
@Singleton
class AccessibilityLockdownEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
) : LockdownEnforcer {

    override fun begin() {
        LockdownState.begin()
        reassert()
    }

    override fun end() {
        LockdownState.end()
    }

    override val isActive: Boolean get() = LockdownState.active

    override fun reassert() {
        val intent = Intent(context, MorningLockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        context.startActivity(intent)
    }
}
```

> `MorningLockActivity` does not exist until Task 19. Write this file now but
> expect a compile error until then; if you would rather keep the tree green,
> create an empty `class MorningLockActivity : ComponentActivity()` stub in
> `ui/lock/` in this step and flesh it out in Task 19.

- [ ] **Step 5: Write `EnforcerModule.kt`**

```kotlin
package com.anchor.di

import com.anchor.domain.AccessibilityLockdownEnforcer
import com.anchor.domain.LockdownEnforcer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Swap the binding here to change enforcement strategy — this is the seam
 * the Device Owner implementation plugs into.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnforcerModule {

    @Binds
    @Singleton
    abstract fun bindLockdownEnforcer(
        impl: AccessibilityLockdownEnforcer,
    ): LockdownEnforcer
}
```

- [ ] **Step 6: Write `accessibility_service_config.xml`**

`app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100" />
```

Add to `app/src/main/res/values/strings.xml` (create it):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">The Anchor</string>
    <string name="accessibility_service_description">The Anchor uses this service to notice which app is in the foreground so it can enforce your morning check-in and evening app blocks. It reads only the package name of the foreground app; it does not read screen content and nothing leaves your device except the Home Assistant and Joplin calls you configure.</string>
</resources>
```

- [ ] **Step 7: Write the full `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Home Assistant and Joplin are on the local network over plain HTTP. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Draw the blocking overlay / relaunch the lock activity from the background. -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <!-- Spec §6. The accessibility service is what actually detects the
         foreground app; usage stats is the fallback and powers the app picker. -->
    <uses-permission
        android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />

    <!-- Keep the monitor alive. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Fire the morning check exactly at the window start. -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />

    <!-- Read the installed app list for the blocked-app and allowlist pickers. -->
    <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />

    <application
        android:name=".AnchorApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Anchor"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- The lock screens live in their own task, excluded from recents,
             so they cannot be swiped away from the task switcher. -->
        <activity
            android:name=".ui.lock.MorningLockActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleInstance"
            android:noHistory="false"
            android:showOnLockScreen="true"
            android:taskAffinity=".lock"
            android:turnScreenOn="true" />

        <activity
            android:name=".ui.lock.EveningLockActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleInstance"
            android:taskAffinity=".lock" />

        <activity
            android:name=".ui.lock.SimpleDelayActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleInstance"
            android:taskAffinity=".lock" />

        <service
            android:name=".service.AnchorAccessibilityService"
            android:exported="false"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".service.AnchorForegroundService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Personal self-imposed screen-time protocol enforcement" />
        </service>

        <receiver
            android:name=".service.MorningAlarmReceiver"
            android:exported="false" />

        <receiver
            android:name=".service.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

`android:usesCleartextTraffic="true"` is required because Home Assistant and
Joplin are typically reached over plain HTTP on the LAN.

- [ ] **Step 8: Run the test and build**

Run: `./gradlew :app:test --tests 'com.anchor.domain.LockdownStateTest'`
Expected: PASS (4 tests).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (with the `MorningLockActivity` stub from Step 4).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add lockdown enforcer abstraction and full manifest"
```

---

### Task 17: `AnchorAccessibilityService` — foreground app interception

**Files:**
- Create: `app/src/main/java/com/anchor/service/AnchorAccessibilityService.kt`
- Test: `app/src/test/java/com/anchor/service/AccessibilityRoutingTest.kt`

**Interfaces:**
- Consumes: `ForegroundAppDecider`, `ForegroundAction` (Task 14), `EveningGate`, `EveningDecision` (Task 13), `LockdownEnforcer` (Task 16), `SettingsRepository` (Task 4).
- Produces:
  - `class AnchorAccessibilityService : AccessibilityService()`
  - `object EveningRouting { fun intentActionFor(decision: EveningDecision): RouteTarget }` and `enum class RouteTarget { NONE, STRICT_OVERLAY, SIMPLE_DELAY }` — the testable half of the routing.

The service itself is untestable without a device, so all the branching lives
in `ForegroundAppDecider` (already tested) and `EveningRouting` (tested here).
The service is a ~60-line adapter.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/service/AccessibilityRoutingTest.kt`:

```kotlin
package com.anchor.service

import com.anchor.domain.EveningDecision
import com.anchor.domain.SkipReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityRoutingTest {

    @Test
    fun `strict decisions route to the overlay`() {
        assertThat(EveningRouting.intentActionFor(EveningDecision.Strict))
            .isEqualTo(RouteTarget.STRICT_OVERLAY)
    }

    @Test
    fun `simple delay routes to the delay screen`() {
        assertThat(EveningRouting.intentActionFor(EveningDecision.SimpleDelay))
            .isEqualTo(RouteTarget.SIMPLE_DELAY)
    }

    @Test
    fun `every Allow reason routes nowhere`() {
        SkipReason.entries.forEach { reason ->
            assertThat(EveningRouting.intentActionFor(EveningDecision.Allow(reason)))
                .isEqualTo(RouteTarget.NONE)
        }
    }

    @Test
    fun `debounce suppresses a repeat of the same package inside the window`() {
        val debounce = PackageDebounce(windowMillis = 3_000)

        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 1_000)).isTrue()
        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 2_000)).isFalse()
        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 5_000)).isTrue()
    }

    @Test
    fun `debounce does not suppress a different package`() {
        val debounce = PackageDebounce(windowMillis = 3_000)

        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 1_000)).isTrue()
        assertThat(debounce.shouldHandle("com.instagram", nowMillis = 1_100)).isTrue()
    }

    @Test
    fun `clearing the debounce lets the same package through again`() {
        val debounce = PackageDebounce(windowMillis = 3_000)

        debounce.shouldHandle("com.youtube", nowMillis = 1_000)
        debounce.clear()
        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 1_100)).isTrue()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.service.AccessibilityRoutingTest'`
Expected: FAIL — `Unresolved reference: EveningRouting`.

- [ ] **Step 3: Write `AnchorAccessibilityService.kt`**

```kotlin
package com.anchor.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.EveningDecision
import com.anchor.domain.EveningGate
import com.anchor.domain.ForegroundAction
import com.anchor.domain.ForegroundAppDecider
import com.anchor.domain.LockdownEnforcer
import com.anchor.ui.lock.EveningLockActivity
import com.anchor.ui.lock.SimpleDelayActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RouteTarget { NONE, STRICT_OVERLAY, SIMPLE_DELAY }

/** Pure mapping from a decision to a screen, so it can be unit-tested. */
object EveningRouting {
    fun intentActionFor(decision: EveningDecision): RouteTarget = when (decision) {
        is EveningDecision.Strict -> RouteTarget.STRICT_OVERLAY
        is EveningDecision.SimpleDelay -> RouteTarget.SIMPLE_DELAY
        is EveningDecision.Allow -> RouteTarget.NONE
    }
}

/**
 * Suppresses repeat handling of the same package within a short window.
 * Android emits several TYPE_WINDOW_STATE_CHANGED events per app launch;
 * without this the gate would be queried (and HA hit) several times per open.
 */
class PackageDebounce(private val windowMillis: Long = 3_000) {
    private var lastPackage: String? = null
    private var lastAtMillis: Long = Long.MIN_VALUE

    fun shouldHandle(packageName: String, nowMillis: Long): Boolean {
        val repeat = packageName == lastPackage && nowMillis - lastAtMillis < windowMillis
        lastPackage = packageName
        lastAtMillis = nowMillis
        return !repeat
    }

    fun clear() {
        lastPackage = null
        lastAtMillis = Long.MIN_VALUE
    }
}

/**
 * Watches foreground-app changes and routes them through the decision engine.
 * Contains no policy of its own — see [ForegroundAppDecider] and [EveningGate].
 */
@AndroidEntryPoint
class AnchorAccessibilityService : AccessibilityService() {

    @Inject lateinit var eveningGate: EveningGate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val debounce = PackageDebounce()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return

        scope.launch {
            val settings = settingsRepository.current()
            when (
                val action = ForegroundAppDecider.decide(
                    packageName = packageName,
                    morningLockActive = enforcer.isActive,
                    settings = settings,
                )
            ) {
                is ForegroundAction.Ignore -> Unit

                is ForegroundAction.ReassertMorningLock -> {
                    // Bypasses the debounce: escaping the lock must always
                    // bring it straight back, however fast the user taps.
                    enforcer.reassert()
                }

                is ForegroundAction.EvaluateEvening -> {
                    if (!debounce.shouldHandle(action.packageName, System.currentTimeMillis())) {
                        return@launch
                    }
                    when (EveningRouting.intentActionFor(eveningGate.decide(action.packageName))) {
                        RouteTarget.NONE -> Unit
                        RouteTarget.STRICT_OVERLAY ->
                            launchLock(EveningLockActivity::class.java, action.packageName)
                        RouteTarget.SIMPLE_DELAY ->
                            launchLock(SimpleDelayActivity::class.java, action.packageName)
                    }
                }
            }
        }
    }

    private fun launchLock(target: Class<*>, blockedPackage: String) {
        startActivity(
            Intent(this, target).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            }
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.service.AccessibilityRoutingTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add accessibility service for foreground app interception"
```

---

### Task 18: Foreground service, morning alarm, and boot receiver

**Files:**
- Create: `app/src/main/java/com/anchor/service/AnchorForegroundService.kt`
- Create: `app/src/main/java/com/anchor/service/MorningAlarmScheduler.kt`
- Create: `app/src/main/java/com/anchor/service/MorningAlarmReceiver.kt`
- Create: `app/src/main/java/com/anchor/service/BootReceiver.kt`
- Test: `app/src/test/java/com/anchor/service/MorningAlarmSchedulerTest.kt`

**Interfaces:**
- Consumes: `AnchorSettings` (Task 4), `AnchorDate` (Task 8), `MorningGate`, `MorningDecision` (Task 12), `LockdownEnforcer` (Task 16), `KillSwitch`, `OverrideStatus` (Task 7).
- Produces:
  - `object NextMorningAlarm { fun nextTriggerMillis(nowMillis: Long, zone: ZoneId, morningStartMinute: Int): Long }` — pure, tested here.
  - `class MorningAlarmScheduler(context, alarmManager, clock)` with `fun schedule(settings: AnchorSettings)` and `fun cancel()`
  - `class MorningAlarmReceiver : BroadcastReceiver()`
  - `class BootReceiver : BroadcastReceiver()`
  - `class AnchorForegroundService : Service()` with `companion object { fun start(context: Context); fun stop(context: Context) }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/service/MorningAlarmSchedulerTest.kt`:

```kotlin
package com.anchor.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class MorningAlarmSchedulerTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun millisAt(local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun nextAt(nowLocal: String, startMinute: Int = 5 * 60): LocalDateTime =
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(
                NextMorningAlarm.nextTriggerMillis(millisAt(nowLocal), zone, startMinute)
            ),
            zone,
        )

    @Test
    fun `before the window start, the alarm is today at the start`() {
        assertThat(nextAt("2026-09-09T03:00:00"))
            .isEqualTo(LocalDateTime.parse("2026-09-09T05:00:00"))
    }

    @Test
    fun `after the window start, the alarm is tomorrow at the start`() {
        assertThat(nextAt("2026-09-09T09:00:00"))
            .isEqualTo(LocalDateTime.parse("2026-09-10T05:00:00"))
    }

    @Test
    fun `exactly at the window start, the alarm moves to tomorrow`() {
        assertThat(nextAt("2026-09-09T05:00:00"))
            .isEqualTo(LocalDateTime.parse("2026-09-10T05:00:00"))
    }

    @Test
    fun `late at night, the alarm is the next morning`() {
        assertThat(nextAt("2026-09-09T23:30:00"))
            .isEqualTo(LocalDateTime.parse("2026-09-10T05:00:00"))
    }

    @Test
    fun `honours a custom window start`() {
        assertThat(nextAt("2026-09-09T03:00:00", startMinute = 6 * 60 + 30))
            .isEqualTo(LocalDateTime.parse("2026-09-09T06:30:00"))
    }

    @Test
    fun `rolls over a month boundary`() {
        assertThat(nextAt("2026-09-30T09:00:00"))
            .isEqualTo(LocalDateTime.parse("2026-10-01T05:00:00"))
    }

    @Test
    fun `always returns a future instant`() {
        listOf("2026-09-09T00:00:00", "2026-09-09T05:00:00", "2026-09-09T23:59:59")
            .forEach { now ->
                val next = NextMorningAlarm.nextTriggerMillis(millisAt(now), zone, 5 * 60)
                assertThat(next).isGreaterThan(millisAt(now))
            }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.service.MorningAlarmSchedulerTest'`
Expected: FAIL — `Unresolved reference: NextMorningAlarm`.

- [ ] **Step 3: Write `MorningAlarmScheduler.kt`**

```kotlin
package com.anchor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.anchor.data.settings.AnchorSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Pure next-fire-time arithmetic. */
object NextMorningAlarm {

    /**
     * The next instant at which the morning window opens, strictly after
     * [nowMillis]. At exactly the start minute we schedule tomorrow, because
     * "now" means the alarm for today has already fired.
     */
    fun nextTriggerMillis(nowMillis: Long, zone: ZoneId, morningStartMinute: Int): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val startTime = LocalTime.of(morningStartMinute / 60, morningStartMinute % 60)
        val todayStart = now.toLocalDate().atTime(startTime)
        val target = if (now.isBefore(todayStart)) todayStart else todayStart.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }
}

@Singleton
class MorningAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, MorningAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(settings: AnchorSettings) {
        val triggerAt = NextMorningAlarm.nextTriggerMillis(
            nowMillis = clock.millis(),
            zone = clock.zone,
            morningStartMinute = settings.morningStartMinute,
        )
        // setAlarmClock survives Doze, which setExactAndAllowWhileIdle does
        // not reliably do on all OEM builds.
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, pendingIntent()),
            pendingIntent(),
        )
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    private companion object {
        const val REQUEST_CODE = 4201
    }
}
```

- [ ] **Step 4: Write `MorningAlarmReceiver.kt`**

```kotlin
package com.anchor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.MorningDecision
import com.anchor.domain.MorningGate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when the morning window opens. Asks [MorningGate] whether to lock and,
 * either way, schedules tomorrow's alarm before finishing.
 */
@AndroidEntryPoint
class MorningAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var morningGate: MorningGate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (morningGate.decide() is MorningDecision.Lock) {
                    enforcer.begin()
                }
                // Always re-arm, so a skipped morning does not stop tomorrow's.
                scheduler.schedule(settingsRepository.current())
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 5: Write `BootReceiver.kt`**

```kotlin
package com.anchor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anchor.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Alarms and foreground services do not survive a reboot; re-establish both. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                scheduler.schedule(settingsRepository.current())
                AnchorForegroundService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 6: Write `AnchorForegroundService.kt`**

```kotlin
package com.anchor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anchor.R
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.OverrideStatus
import com.anchor.data.settings.SettingsRepository
import com.anchor.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process warm so the accessibility service is not reaped, and owns
 * the persistent notification that tells the user whether the remote override
 * is currently active (spec §4, "UI Indication").
 */
@AndroidEntryPoint
class AnchorForegroundService : Service() {

    @Inject lateinit var killSwitch: KillSwitch
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(OverrideStatus.INACTIVE))
        observeOverride()
    }

    /**
     * Polls the kill switch for the *notification text only*. Blocking
     * decisions never read this — they call KillSwitch themselves at the
     * moment of blocking, as the spec requires.
     */
    private fun observeOverride() = scope.launch {
        while (true) {
            val settings = settingsRepository.current()
            val status = if (settings.killSwitchEnabled) {
                killSwitch.check(settings)
            } else {
                OverrideStatus.INACTIVE
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status))
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    private fun buildNotification(status: OverrideStatus): Notification {
        val text = when (status) {
            OverrideStatus.ACTIVE -> "Override active — blocking is disabled"
            OverrideStatus.INACTIVE -> "Protocol active"
            OverrideStatus.UNKNOWN -> "Protocol active (Home Assistant unreachable)"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("The Anchor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_anchor_notification)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Anchor status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows whether the Anchor protocol is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "anchor_status"
        private const val NOTIFICATION_ID = 4200
        private const val POLL_INTERVAL_MILLIS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AnchorForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnchorForegroundService::class.java))
        }
    }
}
```

Create a simple white-on-transparent vector at
`app/src/main/res/drawable/ic_anchor_notification.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="#FF000000"
        android:pathData="M12,2a3,3 0,1 0,0.01 6.01A3,3 0,0 0,12 2zM11,9v2H8v2h3v6.92C7.6,19.44 5,16.53 5,13H3c0,4.97 4.03,9 9,9s9,-4.03 9,-9h-2c0,3.53 -2.6,6.44 -6,6.92V13h3v-2h-3V9h-2z" />
</vector>
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.service.MorningAlarmSchedulerTest'`
Expected: PASS (7 tests).

- [ ] **Step 8: Verify the app assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (UI classes still stubbed).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add foreground service, morning alarm and boot receiver"
```

---

## Phase 6 — User Interface

### Task 19: Theme and the shared lock screen

**Files:**
- Create: `app/src/main/java/com/anchor/ui/theme/Color.kt`, `Type.kt`, `Theme.kt`
- Create: `app/src/main/java/com/anchor/ui/lock/LockViewModel.kt`
- Create: `app/src/main/java/com/anchor/ui/lock/LockScreen.kt`
- Test: `app/src/test/java/com/anchor/ui/lock/LockViewModelTest.kt`

**Interfaces:**
- Consumes: `CustomQuestionDao`, `Phase` (Task 3), `SubmitCheckIn`, `SubmitResult` (Task 15).
- Produces:
  - `data class LockUiState(questions: List<CustomQuestion>, answers: Map<String, String>, isSubmitting: Boolean, canSubmit: Boolean, submitted: Boolean, exportWarning: String?)`
  - `class LockViewModel(phase, questionDao, submitCheckIn)` with `val state: StateFlow<LockUiState>`, `fun onAnswerChanged(slotKey: String, value: String)`, `fun submit()`
  - `@Composable fun LockScreen(title: String, subtitle: String, state: LockUiState, onAnswerChanged: (String, String) -> Unit, onSubmit: () -> Unit)`
  - `@Composable fun AnchorTheme(content: @Composable () -> Unit)` — dark, minimal, no dynamic color

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/ui/lock/LockViewModelTest.kt`:

```kotlin
package com.anchor.ui.lock

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.export.ExportResult
import com.anchor.domain.SubmitCheckIn
import com.anchor.domain.SubmitResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LockViewModelTest {

    private lateinit var db: AnchorDatabase
    private val dispatcher = StandardTestDispatcher()

    /** Records what was submitted and returns a configurable result. */
    private class FakeSubmit(
        private val result: SubmitResult = SubmitResult(
            log = DailyLog(date = "2026-09-09"),
            export = ExportResult.Written("2026-09-09.md", "content"),
        ),
    ) {
        var submitted: Pair<Phase, Map<String, String>>? = null
        val fn: suspend (Phase, Map<String, String>) -> SubmitResult = { phase, answers ->
            submitted = phase to answers
            result
        }
    }

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AnchorDatabase::class.java,
        ).allowMainThreadQueries().build()
        DefaultQuestions.ALL.forEach { db.customQuestionDao().upsert(it) }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        phase: Phase = Phase.MORNING,
        submit: FakeSubmit = FakeSubmit(),
    ) = LockViewModel(
        phase = phase,
        questionDao = db.customQuestionDao(),
        submitCheckIn = submit.fn,
    )

    @Test
    fun `loads the questions for its phase, in order`() = runTest(dispatcher) {
        viewModel().state.test {
            skipItems(1)   // initial empty state
            val loaded = awaitItem()
            assertThat(loaded.questions.map { it.slotKey })
                .containsExactly(SlotKey.MISSION, SlotKey.AVOIDING).inOrder()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the evening view model loads three questions`() = runTest(dispatcher) {
        val vm = viewModel(phase = Phase.EVENING)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.questions).hasSize(3)
    }

    @Test
    fun `cannot submit until every question has a non-blank answer`() = runTest(dispatcher) {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.canSubmit).isFalse()

        vm.onAnswerChanged(SlotKey.MISSION, "Ship the plan")
        assertThat(vm.state.value.canSubmit).isFalse()

        vm.onAnswerChanged(SlotKey.AVOIDING, "The invoice")
        assertThat(vm.state.value.canSubmit).isTrue()
    }

    @Test
    fun `whitespace-only answers do not enable submission`() = runTest(dispatcher) {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onAnswerChanged(SlotKey.MISSION, "Ship")
        vm.onAnswerChanged(SlotKey.AVOIDING, "    ")
        assertThat(vm.state.value.canSubmit).isFalse()
    }

    @Test
    fun `submit passes trimmed answers keyed by slot`() = runTest(dispatcher) {
        val submit = FakeSubmit()
        val vm = viewModel(submit = submit)
        dispatcher.scheduler.advanceUntilIdle()

        vm.onAnswerChanged(SlotKey.MISSION, "  Ship the plan  ")
        vm.onAnswerChanged(SlotKey.AVOIDING, "The invoice")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(submit.submitted!!.first).isEqualTo(Phase.MORNING)
        assertThat(submit.submitted!!.second).containsExactly(
            SlotKey.MISSION, "Ship the plan",
            SlotKey.AVOIDING, "The invoice",
        )
    }

    @Test
    fun `submitted becomes true so the activity can finish`() = runTest(dispatcher) {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onAnswerChanged(SlotKey.MISSION, "a")
        vm.onAnswerChanged(SlotKey.AVOIDING, "b")

        assertThat(vm.state.value.submitted).isFalse()
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.state.value.submitted).isTrue()
    }

    @Test
    fun `submit is a no-op when the form is incomplete`() = runTest(dispatcher) {
        val submit = FakeSubmit()
        val vm = viewModel(submit = submit)
        dispatcher.scheduler.advanceUntilIdle()

        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(submit.submitted).isNull()
        assertThat(vm.state.value.submitted).isFalse()
    }

    @Test
    fun `an export failure still lets the user through, with a warning`() = runTest(dispatcher) {
        val submit = FakeSubmit(
            SubmitResult(DailyLog(date = "2026-09-09"), ExportResult.NoDirectoryConfigured)
        )
        val vm = viewModel(submit = submit)
        dispatcher.scheduler.advanceUntilIdle()
        vm.onAnswerChanged(SlotKey.MISSION, "a")
        vm.onAnswerChanged(SlotKey.AVOIDING, "b")
        vm.submit()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.submitted).isTrue()
        assertThat(vm.state.value.exportWarning).isNotNull()
    }

    @Test
    fun `a user-added question is rendered and required like any other`() = runTest(dispatcher) {
        db.customQuestionDao().upsert(
            CustomQuestion(
                phase = Phase.MORNING, slotKey = "custom:zz",
                prompt = "Who do I owe a reply?", sortOrder = 5,
            )
        )
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(vm.state.value.questions).hasSize(3)
        vm.onAnswerChanged(SlotKey.MISSION, "a")
        vm.onAnswerChanged(SlotKey.AVOIDING, "b")
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.onAnswerChanged("custom:zz", "Priya")
        assertThat(vm.state.value.canSubmit).isTrue()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.ui.lock.LockViewModelTest'`
Expected: FAIL — `Unresolved reference: LockViewModel`.

- [ ] **Step 3: Write the theme files**

`app/src/main/java/com/anchor/ui/theme/Color.kt`:

```kotlin
package com.anchor.ui.theme

import androidx.compose.ui.graphics.Color

// Deliberately narrow palette. The lock screens should feel like a quiet
// room, not an app: near-black ground, one warm accent, nothing else.
val AnchorBlack = Color(0xFF0B0B0D)
val AnchorSurface = Color(0xFF141417)
val AnchorAmber = Color(0xFFD8A657)
val AnchorText = Color(0xFFEDEDEF)
val AnchorMuted = Color(0xFF8A8A93)
val AnchorError = Color(0xFFE06C75)
```

`app/src/main/java/com/anchor/ui/theme/Type.kt`:

```kotlin
package com.anchor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AnchorTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
)
```

`app/src/main/java/com/anchor/ui/theme/Theme.kt`:

```kotlin
package com.anchor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AnchorColors = darkColorScheme(
    primary = AnchorAmber,
    onPrimary = AnchorBlack,
    background = AnchorBlack,
    onBackground = AnchorText,
    surface = AnchorSurface,
    onSurface = AnchorText,
    onSurfaceVariant = AnchorMuted,
    error = AnchorError,
)

/**
 * Always dark, regardless of system setting: these screens are shown at 5 AM
 * and at midnight, and a white flash is the opposite of the intent.
 */
@Composable
fun AnchorTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AnchorColors,
        typography = AnchorTypography,
        content = content,
    )
}
```

- [ ] **Step 4: Write `LockViewModel.kt`**

```kotlin
package com.anchor.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.data.export.ExportResult
import com.anchor.domain.SubmitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LockUiState(
    val questions: List<CustomQuestion> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val exportWarning: String? = null,
) {
    /** Every rendered question needs a non-blank answer. */
    val canSubmit: Boolean
        get() = questions.isNotEmpty() &&
            !isSubmitting &&
            questions.all { !answers[it.slotKey].isNullOrBlank() }
}

/**
 * Backs both lock screens. Takes [submitCheckIn] as a function rather than
 * the use-case class so it can be faked in a plain JVM test.
 */
class LockViewModel(
    private val phase: Phase,
    private val questionDao: CustomQuestionDao,
    private val submitCheckIn: suspend (Phase, Map<String, String>) -> SubmitResult,
) : ViewModel() {

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(questions = questionDao.list(phase))
        }
    }

    fun onAnswerChanged(slotKey: String, value: String) {
        _state.value = _state.value.copy(
            answers = _state.value.answers + (slotKey to value)
        )
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.value = current.copy(isSubmitting = true)
        viewModelScope.launch {
            val answers = current.questions.associate { q ->
                q.slotKey to (current.answers[q.slotKey]?.trim() ?: "")
            }
            val result = submitCheckIn(phase, answers)
            _state.value = _state.value.copy(
                isSubmitting = false,
                submitted = true,
                exportWarning = warningFor(result),
            )
        }
    }

    /**
     * Export problems never block the user — the answers are already saved.
     * They surface as a one-line warning the dashboard repeats.
     */
    private fun warningFor(result: SubmitResult): String? = when (result.export) {
        is ExportResult.Written -> null
        is ExportResult.NoDirectoryConfigured ->
            "Saved locally. Pick an export folder in Settings to write Markdown files."
        is ExportResult.Failed ->
            "Saved to the database, but the Markdown file could not be written."
    }
}
```

- [ ] **Step 5: Write `LockScreen.kt`**

```kotlin
package com.anchor.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * The shared blocking UI: a title, the dynamically rendered questions, and a
 * single submit button. Back is swallowed — the only way out is to answer.
 */
@Composable
fun LockScreen(
    title: String,
    subtitle: String,
    state: LockUiState,
    onAnswerChanged: (String, String) -> Unit,
    onSubmit: () -> Unit,
) {
    // Consumes the back gesture without doing anything.
    BackHandler(enabled = true) { }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))

            state.questions.forEachIndexed { index, question ->
                Text(text = question.prompt, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.answers[question.slotKey].orEmpty(),
                    onValueChange = { onAnswerChanged(question.slotKey, it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (index == state.questions.lastIndex) {
                            ImeAction.Done
                        } else {
                            ImeAction.Next
                        },
                    ),
                )
                Spacer(Modifier.height(28.dp))
            }

            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSubmitting) "Saving…" else "Continue")
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.ui.lock.LockViewModelTest'`
Expected: PASS (9 tests).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add dark theme and shared lock screen with dynamic questions"
```

---

### Task 20: The three blocking Activities

**Files:**
- Create: `app/src/main/java/com/anchor/ui/lock/MorningLockActivity.kt` (replaces the stub)
- Create: `app/src/main/java/com/anchor/ui/lock/EveningLockActivity.kt`
- Create: `app/src/main/java/com/anchor/ui/lock/SimpleDelayActivity.kt`
- Create: `app/src/main/java/com/anchor/ui/lock/LockViewModelFactory.kt`
- Test: `app/src/test/java/com/anchor/ui/lock/SimpleDelayTimerTest.kt`

**Interfaces:**
- Consumes: `LockViewModel`, `LockScreen` (Task 19), `SubmitCheckIn` (Task 15), `LockdownEnforcer` (Task 16), `CustomQuestionDao` (Task 3).
- Produces:
  - `class LockViewModelFactory(phase, questionDao, submitCheckIn) : ViewModelProvider.Factory`
  - The three Activities.
  - `object SimpleDelayTimer { const val DEFAULT_SECONDS = 5; fun remaining(elapsedMillis: Long, totalSeconds: Int): Int }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/ui/lock/SimpleDelayTimerTest.kt`:

```kotlin
package com.anchor.ui.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimpleDelayTimerTest {

    @Test
    fun `the spec's delay is five seconds`() {
        assertThat(SimpleDelayTimer.DEFAULT_SECONDS).isEqualTo(5)
    }

    @Test
    fun `counts down from the total`() {
        assertThat(SimpleDelayTimer.remaining(0, 5)).isEqualTo(5)
        assertThat(SimpleDelayTimer.remaining(1_000, 5)).isEqualTo(4)
        assertThat(SimpleDelayTimer.remaining(4_500, 5)).isEqualTo(1)
    }

    @Test
    fun `reaches zero and never goes negative`() {
        assertThat(SimpleDelayTimer.remaining(5_000, 5)).isEqualTo(0)
        assertThat(SimpleDelayTimer.remaining(60_000, 5)).isEqualTo(0)
    }

    @Test
    fun `a partial second still shows the higher number`() {
        // At 0.4s elapsed the user should still see "5", not "4".
        assertThat(SimpleDelayTimer.remaining(400, 5)).isEqualTo(5)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.ui.lock.SimpleDelayTimerTest'`
Expected: FAIL — `Unresolved reference: SimpleDelayTimer`.

- [ ] **Step 3: Write `LockViewModelFactory.kt`**

```kotlin
package com.anchor.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.domain.SubmitCheckIn

/**
 * The phase is an Activity-level constant rather than a saved-state argument,
 * so a plain factory is simpler here than @HiltViewModel + assisted injection.
 */
class LockViewModelFactory(
    private val phase: Phase,
    private val questionDao: CustomQuestionDao,
    private val submitCheckIn: SubmitCheckIn,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LockViewModel(
            phase = phase,
            questionDao = questionDao,
            submitCheckIn = { p, answers -> submitCheckIn(p, answers) },
        ) as T
}
```

- [ ] **Step 4: Write `MorningLockActivity.kt`**

```kotlin
package com.anchor.ui.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.SubmitCheckIn
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The morning lockdown. Non-dismissable by design:
 *  - back is swallowed by [LockScreen]'s BackHandler
 *  - home / recents are countered by the accessibility service, which calls
 *    [LockdownEnforcer.reassert] whenever another app reaches the foreground
 *  - the task is excluded from recents (manifest) so it cannot be swiped away
 *
 * The accessibility service's allowlist means the dialer, messaging and
 * system surfaces still get through — see [com.anchor.domain.ForegroundAppDecider].
 */
@AndroidEntryPoint
class MorningLockActivity : ComponentActivity() {

    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var submitCheckIn: SubmitCheckIn
    @Inject lateinit var enforcer: LockdownEnforcer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val viewModel = ViewModelProvider(
            this,
            LockViewModelFactory(Phase.MORNING, questionDao, submitCheckIn),
        )[LockViewModel::class.java]

        setContent {
            AnchorTheme {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.submitted) {
                    if (state.submitted) {
                        enforcer.end()
                        finish()
                    }
                }

                LockScreen(
                    title = "Morning Anchor",
                    subtitle = "Answer to begin the day.",
                    state = state,
                    onAnswerChanged = viewModel::onAnswerChanged,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }

    /** Never let the system pause us into the background silently. */
    override fun onPause() {
        super.onPause()
        if (enforcer.isActive && !isFinishing) {
            enforcer.reassert()
        }
    }
}
```

- [ ] **Step 5: Write `EveningLockActivity.kt`**

```kotlin
package com.anchor.ui.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.domain.SubmitCheckIn
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The strict evening overlay. Unlike the morning lock this does NOT relaunch
 * itself: once the three questions are answered the user proceeds to the app
 * they opened, and the block stays lifted for the rest of the night.
 */
@AndroidEntryPoint
class EveningLockActivity : ComponentActivity() {

    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var submitCheckIn: SubmitCheckIn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = ViewModelProvider(
            this,
            LockViewModelFactory(Phase.EVENING, questionDao, submitCheckIn),
        )[LockViewModel::class.java]

        setContent {
            AnchorTheme {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.submitted) {
                    if (state.submitted) finish()
                }

                LockScreen(
                    title = "Evening Anchor",
                    subtitle = "Three moments from today, before you go on.",
                    state = state,
                    onAnswerChanged = viewModel::onAnswerChanged,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }
}
```

- [ ] **Step 6: Write `SimpleDelayActivity.kt`**

```kotlin
package com.anchor.ui.lock

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.ui.theme.AnchorTheme
import kotlinx.coroutines.delay

/** Pure countdown arithmetic, so the timing is unit-testable. */
object SimpleDelayTimer {
    const val DEFAULT_SECONDS = 5

    /** Seconds still to wait, rounded up, floored at zero. */
    fun remaining(elapsedMillis: Long, totalSeconds: Int): Int {
        val remainingMillis = totalSeconds * 1000L - elapsedMillis
        if (remainingMillis <= 0) return 0
        return ((remainingMillis + 999) / 1000).toInt()
    }
}

/**
 * The fail-open path: shown instead of the strict overlay when the user is
 * away from the restricted location, or when Home Assistant is unreachable.
 * A pause, not a wall.
 */
class SimpleDelayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnchorTheme {
                val start = remember { SystemClock.elapsedRealtime() }
                var remaining by remember { mutableIntStateOf(SimpleDelayTimer.DEFAULT_SECONDS) }

                LaunchedEffect(Unit) {
                    while (remaining > 0) {
                        delay(200)
                        remaining = SimpleDelayTimer.remaining(
                            elapsedMillis = SystemClock.elapsedRealtime() - start,
                            totalSeconds = SimpleDelayTimer.DEFAULT_SECONDS,
                        )
                    }
                }

                BackHandler(enabled = remaining > 0) { }

                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (remaining > 0) "$remaining" else "Go ahead",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "A moment before you open this.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Button(
                            onClick = { finish() },
                            enabled = remaining == 0,
                            modifier = Modifier.padding(top = 40.dp),
                        ) { Text("Continue") }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.ui.lock.SimpleDelayTimerTest'`
Expected: PASS (4 tests).

- [ ] **Step 8: Verify the app assembles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add morning, evening and simple-delay blocking activities"
```

---

### Task 21: Settings screen

**Files:**
- Create: `app/src/main/java/com/anchor/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/sections/ScheduleSection.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/sections/AppsSection.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/sections/QuestionsSection.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/sections/HomeAssistantSection.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/sections/ExportSection.kt`
- Create: `app/src/main/java/com/anchor/ui/settings/InstalledAppsRepository.kt`
- Test: `app/src/test/java/com/anchor/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository`, `AnchorSettings`, `LocationMode`, `parseRoomList` (Task 4), `CustomQuestionDao`, `CustomQuestion`, `SlotKey`, `Phase` (Task 3), `MorningAlarmScheduler` (Task 18).
- Produces:
  - `data class InstalledApp(packageName: String, label: String)`
  - `class InstalledAppsRepository(context)` with `suspend fun launchableApps(): List<InstalledApp>`
  - `class SettingsViewModel` with `val settings: StateFlow<AnchorSettings>`, `val morningQuestions/eveningQuestions: StateFlow<List<CustomQuestion>>`, and mutators: `fun updateSettings(transform)`, `fun setExportTree(uri: String)`, `fun addQuestion(phase, prompt)`, `fun editQuestion(question, prompt)`, `fun deleteQuestion(question)`, `fun moveQuestion(question, delta)`, `fun toggleBlockedApp(pkg)`, `fun toggleAllowlistApp(pkg)`
  - `@Composable fun SettingsScreen(viewModel, onPickExportFolder: () -> Unit)`

**Settings sections (spec §6 checklist):** morning lock time, evening lock
time, blocked apps, morning allowlist, custom questions per phase, HA URL /
token / device id, location mode + rooms per phase, kill switch entity +
override state, export folder, Joplin URL + token.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/ui/settings/SettingsViewModelTest.kt`:

```kotlin
package com.anchor.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anchor.data.db.AnchorDatabase
import com.anchor.data.db.DefaultQuestions
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.SettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var db: AnchorDatabase
    private lateinit var file: File
    private lateinit var repo: SettingsRepository
    private val dispatcher = StandardTestDispatcher()
    private var rescheduleCount = 0

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AnchorDatabase::class.java)
            .allowMainThreadQueries().build()
        DefaultQuestions.ALL.forEach { db.customQuestionDao().upsert(it) }
        file = File(context.cacheDir, "settings-vm-${System.nanoTime()}.preferences_pb")
        val store: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = TestScope()) { file }
        repo = SettingsRepository(store)
        rescheduleCount = 0
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(
        settingsRepository = repo,
        questionDao = db.customQuestionDao(),
        onScheduleChanged = { rescheduleCount++ },
    )

    @Test
    fun `exposes the current settings`() = runTest(dispatcher) {
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(vm.settings.value.morningStartMinute).isEqualTo(5 * 60)
    }

    @Test
    fun `updating a setting persists it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.updateSettings { it.copy(haBaseUrl = "http://ha.local:8123") }
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.current().haBaseUrl).isEqualTo("http://ha.local:8123")
    }

    @Test
    fun `changing the morning start reschedules the alarm`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.updateSettings { it.copy(morningStartMinute = 6 * 60) }
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(rescheduleCount).isEqualTo(1)
    }

    @Test
    fun `changing an unrelated setting does not reschedule`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.updateSettings { it.copy(joplinToken = "abc") }
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(rescheduleCount).isEqualTo(0)
    }

    @Test
    fun `toggling a blocked app adds then removes it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleBlockedApp("com.google.android.youtube")
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(repo.current().blockedPackages).contains("com.google.android.youtube")

        vm.toggleBlockedApp("com.google.android.youtube")
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(repo.current().blockedPackages).isEmpty()
    }

    @Test
    fun `toggling an allowlist app adds then removes it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleAllowlistApp("com.android.dialer")
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(repo.current().allowlistPackages).contains("com.android.dialer")
    }

    @Test
    fun `adding a question appends it with a custom slot key`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addQuestion(Phase.MORNING, "Who do I owe a reply?")
        dispatcher.scheduler.advanceUntilIdle()

        val questions = db.customQuestionDao().list(Phase.MORNING)
        assertThat(questions).hasSize(3)
        val added = questions.last()
        assertThat(added.prompt).isEqualTo("Who do I owe a reply?")
        assertThat(SlotKey.isCustom(added.slotKey)).isTrue()
        assertThat(added.sortOrder).isEqualTo(2)
    }

    @Test
    fun `adding a blank question is ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addQuestion(Phase.MORNING, "   ")
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(db.customQuestionDao().list(Phase.MORNING)).hasSize(2)
    }

    @Test
    fun `editing a question changes only its prompt`() = runTest(dispatcher) {
        val vm = viewModel()
        val original = db.customQuestionDao().list(Phase.MORNING).first()
        vm.editQuestion(original, "What matters most today?")
        dispatcher.scheduler.advanceUntilIdle()

        val updated = db.customQuestionDao().list(Phase.MORNING).first()
        assertThat(updated.prompt).isEqualTo("What matters most today?")
        assertThat(updated.slotKey).isEqualTo(original.slotKey)
        assertThat(updated.id).isEqualTo(original.id)
    }

    @Test
    fun `deleting a question removes it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.deleteQuestion(db.customQuestionDao().list(Phase.EVENING).first())
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(db.customQuestionDao().list(Phase.EVENING)).hasSize(2)
    }

    @Test
    fun `moving a question up swaps its sort order with its neighbour`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = db.customQuestionDao().list(Phase.EVENING)
        vm.moveQuestion(before[1], delta = -1)
        dispatcher.scheduler.advanceUntilIdle()

        val after = db.customQuestionDao().list(Phase.EVENING)
        assertThat(after.first().slotKey).isEqualTo(before[1].slotKey)
        assertThat(after[1].slotKey).isEqualTo(before[0].slotKey)
    }

    @Test
    fun `moving the first question up is a no-op`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = db.customQuestionDao().list(Phase.EVENING).map { it.slotKey }
        vm.moveQuestion(db.customQuestionDao().list(Phase.EVENING).first(), delta = -1)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(db.customQuestionDao().list(Phase.EVENING).map { it.slotKey })
            .isEqualTo(before)
    }

    @Test
    fun `setting the export tree persists the uri`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setExportTree("content://com.android.externalstorage.documents/tree/primary%3ADocs")
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.current().exportTreeUri).contains("primary%3ADocs")
    }

    @Test
    fun `room lists are parsed from the comma-separated field`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setRooms(Phase.EVENING, " Bedroom , Living Room ,")
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.current().eveningAllowedRooms)
            .containsExactly("Bedroom", "Living Room").inOrder()
    }

    @Test
    fun `location mode is set per phase`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setLocationMode(Phase.MORNING, LocationMode.SPECIFIC_ROOMS)
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repo.current().morningLocationMode).isEqualTo(LocationMode.SPECIFIC_ROOMS)
        assertThat(repo.current().eveningLocationMode).isEqualTo(LocationMode.AT_HOME)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.ui.settings.SettingsViewModelTest'`
Expected: FAIL — `Unresolved reference: SettingsViewModel`.

- [ ] **Step 3: Write `SettingsViewModel.kt`**

```kotlin
package com.anchor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.settings.parseRoomList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @param onScheduleChanged invoked when a change requires re-arming the
 *   morning alarm. Passed as a lambda so the ViewModel stays Android-free
 *   and unit-testable.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val questionDao: CustomQuestionDao,
    private val onScheduleChanged: () -> Unit,
) : ViewModel() {

    val settings: StateFlow<AnchorSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnchorSettings())

    val morningQuestions: StateFlow<List<CustomQuestion>> =
        questionDao.observeIncludingDisabled(Phase.MORNING)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val eveningQuestions: StateFlow<List<CustomQuestion>> =
        questionDao.observeIncludingDisabled(Phase.EVENING)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateSettings(transform: (AnchorSettings) -> AnchorSettings) {
        viewModelScope.launch {
            val before = settingsRepository.current()
            val after = transform(before)
            settingsRepository.update { after }
            if (after.morningStartMinute != before.morningStartMinute) {
                onScheduleChanged()
            }
        }
    }

    fun setExportTree(uri: String) = updateSettings { it.copy(exportTreeUri = uri) }

    fun setRooms(phase: Phase, raw: String) = updateSettings {
        val rooms = parseRoomList(raw)
        when (phase) {
            Phase.MORNING -> it.copy(morningAllowedRooms = rooms)
            Phase.EVENING -> it.copy(eveningAllowedRooms = rooms)
        }
    }

    fun setLocationMode(phase: Phase, mode: LocationMode) = updateSettings {
        when (phase) {
            Phase.MORNING -> it.copy(morningLocationMode = mode)
            Phase.EVENING -> it.copy(eveningLocationMode = mode)
        }
    }

    fun toggleBlockedApp(packageName: String) = updateSettings {
        it.copy(blockedPackages = it.blockedPackages.toggle(packageName))
    }

    fun toggleAllowlistApp(packageName: String) = updateSettings {
        it.copy(allowlistPackages = it.allowlistPackages.toggle(packageName))
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    // --- Questions ---

    fun addQuestion(phase: Phase, prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val existing = questionDao.list(phase)
            questionDao.upsert(
                CustomQuestion(
                    phase = phase,
                    slotKey = SlotKey.custom(),
                    prompt = prompt.trim(),
                    sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                )
            )
        }
    }

    fun editQuestion(question: CustomQuestion, prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch { questionDao.upsert(question.copy(prompt = prompt.trim())) }
    }

    fun deleteQuestion(question: CustomQuestion) {
        viewModelScope.launch { questionDao.delete(question) }
    }

    /** @param delta -1 to move earlier, +1 to move later. */
    fun moveQuestion(question: CustomQuestion, delta: Int) {
        viewModelScope.launch {
            val ordered = questionDao.list(question.phase).sortedBy { it.sortOrder }
            val index = ordered.indexOfFirst { it.id == question.id }
            val target = index + delta
            if (index < 0 || target !in ordered.indices) return@launch

            val a = ordered[index]
            val b = ordered[target]
            questionDao.upsert(a.copy(sortOrder = b.sortOrder))
            questionDao.upsert(b.copy(sortOrder = a.sortOrder))
        }
    }
}
```

- [ ] **Step 4: Write `InstalledAppsRepository.kt`**

```kotlin
package com.anchor.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(val packageName: String, val label: String)

@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Launchable apps only — services and libraries would just be noise. */
    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                InstalledApp(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
```

- [ ] **Step 5: Write the settings sections**

`app/src/main/java/com/anchor/ui/settings/sections/ScheduleSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.settings.AnchorSettings

fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** Four time fields: morning start/end and evening start/end. */
@Composable
fun ScheduleSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Schedule") {
        TimeRow("Morning starts", settings.morningStartMinute) { m ->
            onChange { it.copy(morningStartMinute = m) }
        }
        TimeRow("Morning ends", settings.morningEndMinute) { m ->
            onChange { it.copy(morningEndMinute = m) }
        }
        TimeRow("Evening starts", settings.eveningStartMinute) { m ->
            onChange { it.copy(eveningStartMinute = m) }
        }
        TimeRow("Evening ends", settings.eveningEndMinute) { m ->
            onChange { it.copy(eveningEndMinute = m) }
        }
        Text(
            "The evening window may cross midnight — 20:00 to 05:00 is one night.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TimeRow(label: String, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(vertical = 12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(formatMinute(minuteOfDay), color = MaterialTheme.colorScheme.primary)
    }

    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = minuteOfDay / 60,
            initialMinute = minuteOfDay % 60,
            is24Hour = true,
        )
        AnchorDialog(
            onDismiss = { showPicker = false },
            onConfirm = {
                onPicked(state.hour * 60 + state.minute)
                showPicker = false
            },
        ) {
            Column { TimePicker(state = state) }
        }
    }
}
```

`app/src/main/java/com/anchor/ui/settings/sections/Common.kt` — shared bits
used by every section:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
fun TextSetting(
    label: String,
    value: String,
    placeholder: String = "",
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation =
            if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
fun AnchorDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { content() },
    )
}
```

`app/src/main/java/com/anchor/ui/settings/sections/HomeAssistantSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.db.Phase
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.formatRoomList

@Composable
fun HomeAssistantSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
    onSetRooms: (Phase, String) -> Unit,
    onSetLocationMode: (Phase, LocationMode) -> Unit,
) {
    SettingsSection(title = "Home Assistant") {
        TextSetting("Base URL", settings.haBaseUrl, "http://192.168.1.10:8123") { v ->
            onChange { it.copy(haBaseUrl = v.trim()) }
        }
        TextSetting("Long-lived access token", settings.haToken, secret = true) { v ->
            onChange { it.copy(haToken = v.trim()) }
        }
        TextSetting(
            "Device tracker entity", settings.haDeviceTrackerEntityId,
            "device_tracker.pixel",
        ) { v -> onChange { it.copy(haDeviceTrackerEntityId = v.trim()) } }

        Text(
            "If Home Assistant cannot be reached, The Anchor does less, not more: " +
                "the morning lock is skipped and the evening shows a 5-second pause.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    SettingsSection(title = "Morning location") {
        LocationModePicker(settings.morningLocationMode) { onSetLocationMode(Phase.MORNING, it) }
        if (settings.morningLocationMode == LocationMode.SPECIFIC_ROOMS) {
            TextSetting(
                "Allowed rooms (comma-separated)",
                formatRoomList(settings.morningAllowedRooms),
                "Bedroom, Office",
            ) { onSetRooms(Phase.MORNING, it) }
        }
    }

    SettingsSection(title = "Evening location") {
        LocationModePicker(settings.eveningLocationMode) { onSetLocationMode(Phase.EVENING, it) }
        if (settings.eveningLocationMode == LocationMode.SPECIFIC_ROOMS) {
            TextSetting(
                "Restricted rooms (comma-separated)",
                formatRoomList(settings.eveningAllowedRooms),
                "Bedroom, Living Room",
            ) { onSetRooms(Phase.EVENING, it) }
        }
    }

    SettingsSection(title = "Remote kill switch") {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enabled", Modifier.weight(1f))
            Switch(
                checked = settings.killSwitchEnabled,
                onCheckedChange = { on -> onChange { it.copy(killSwitchEnabled = on) } },
            )
        }
        if (settings.killSwitchEnabled) {
            TextSetting(
                "Entity ID", settings.killSwitchEntityId, "input_boolean.anchor_override",
            ) { v -> onChange { it.copy(killSwitchEntityId = v.trim()) } }
            TextSetting("State that disables blocking", settings.killSwitchOverrideState, "on") { v ->
                onChange { it.copy(killSwitchOverrideState = v.trim()) }
            }
            Text(
                "Checked at the moment of every block. Disabling the app means " +
                    "opening Home Assistant — that friction is the point.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocationModePicker(current: LocationMode, onPick: (LocationMode) -> Unit) {
    LocationMode.entries.forEach { mode ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = current == mode, onClick = { onPick(mode) })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = current == mode, onClick = { onPick(mode) })
            Text(
                text = when (mode) {
                    LocationMode.AT_HOME -> "At home (anywhere in the house)"
                    LocationMode.SPECIFIC_ROOMS -> "Specific rooms"
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
```

`app/src/main/java/com/anchor/ui/settings/sections/AppsSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.ui.settings.InstalledApp

/**
 * Used twice: once for the evening blocked list, once for the morning
 * allowlist. Capped in height so two of these fit on one settings page.
 */
@Composable
fun AppsSection(
    title: String,
    description: String,
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    SettingsSection(title = title) {
        Text(description, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            items(apps, key = { it.packageName }) { app ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = app.packageName in selected,
                        onCheckedChange = { onToggle(app.packageName) },
                    )
                    Text(app.label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
```

`app/src/main/java/com/anchor/ui/settings/sections/QuestionsSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.db.CustomQuestion

/**
 * Add, edit, reorder and delete the questions for one phase. Editing a
 * question keeps its slot key, so historical answers stay attached.
 */
@Composable
fun QuestionsSection(
    title: String,
    questions: List<CustomQuestion>,
    onAdd: (String) -> Unit,
    onEdit: (CustomQuestion, String) -> Unit,
    onDelete: (CustomQuestion) -> Unit,
    onMove: (CustomQuestion, Int) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    SettingsSection(title = title) {
        questions.forEach { question ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = question.prompt,
                    onValueChange = { onEdit(question, it) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(question, -1) }) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(question, 1) }) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(onClick = { onDelete(question) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("New question") },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onAdd(draft); draft = "" }) { Text("Add") }
        }
    }
}
```

`app/src/main/java/com/anchor/ui/settings/sections/ExportSection.kt`:

```kotlin
package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.settings.AnchorSettings

@Composable
fun ExportSection(
    settings: AnchorSettings,
    onPickFolder: () -> Unit,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Markdown export") {
        Text(
            text = settings.exportTreeUri?.let { "Folder selected" } ?: "No folder selected",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onPickFolder, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (settings.exportTreeUri == null) "Choose folder" else "Change folder")
        }
        Text(
            "One file per day, named YYYY-MM-DD.md. Point this at a folder your " +
                "Joplin or Syncthing setup already watches.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    SettingsSection(title = "Joplin (optional)") {
        TextSetting("API URL", settings.joplinBaseUrl, "http://192.168.1.10:41184") { v ->
            onChange { it.copy(joplinBaseUrl = v.trim()) }
        }
        TextSetting("API token", settings.joplinToken, secret = true) { v ->
            onChange { it.copy(joplinToken = v.trim()) }
        }
        Text(
            "If a push fails, The Anchor falls back to the local file silently.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 6: Write `SettingsScreen.kt`**

```kotlin
package com.anchor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.db.Phase
import com.anchor.ui.settings.sections.AppsSection
import com.anchor.ui.settings.sections.ExportSection
import com.anchor.ui.settings.sections.HomeAssistantSection
import com.anchor.ui.settings.sections.QuestionsSection
import com.anchor.ui.settings.sections.ScheduleSection

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    installedApps: InstalledAppsRepository,
    onPickExportFolder: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val morningQuestions by viewModel.morningQuestions.collectAsState()
    val eveningQuestions by viewModel.eveningQuestions.collectAsState()

    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    LaunchedEffect(Unit) { apps = installedApps.launchableApps() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        ScheduleSection(settings, viewModel::updateSettings)

        AppsSection(
            title = "Blocked apps (evening)",
            description = "Opening one of these during the evening window triggers the check-in.",
            apps = apps,
            selected = settings.blockedPackages,
            onToggle = viewModel::toggleBlockedApp,
        )

        AppsSection(
            title = "Morning allowlist",
            description = "Never interrupted during the morning lockdown. The dialer, " +
                "messaging, emergency and system apps are always allowed.",
            apps = apps,
            selected = settings.allowlistPackages,
            onToggle = viewModel::toggleAllowlistApp,
        )

        QuestionsSection(
            title = "Morning questions",
            questions = morningQuestions,
            onAdd = { viewModel.addQuestion(Phase.MORNING, it) },
            onEdit = viewModel::editQuestion,
            onDelete = viewModel::deleteQuestion,
            onMove = viewModel::moveQuestion,
        )

        QuestionsSection(
            title = "Evening questions",
            questions = eveningQuestions,
            onAdd = { viewModel.addQuestion(Phase.EVENING, it) },
            onEdit = viewModel::editQuestion,
            onDelete = viewModel::deleteQuestion,
            onMove = viewModel::moveQuestion,
        )

        HomeAssistantSection(
            settings = settings,
            onChange = viewModel::updateSettings,
            onSetRooms = viewModel::setRooms,
            onSetLocationMode = viewModel::setLocationMode,
        )

        ExportSection(
            settings = settings,
            onPickFolder = onPickExportFolder,
            onChange = viewModel::updateSettings,
        )
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.ui.settings.SettingsViewModelTest'`
Expected: PASS (15 tests).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add settings screen covering every configurable value"
```

---

### Task 22: `MainActivity` — dashboard, permissions and folder picker

**Files:**
- Create: `app/src/main/java/com/anchor/ui/MainActivity.kt`
- Create: `app/src/main/java/com/anchor/ui/home/DashboardViewModel.kt`
- Create: `app/src/main/java/com/anchor/ui/home/DashboardScreen.kt`
- Create: `app/src/main/java/com/anchor/ui/home/PermissionState.kt`
- Test: `app/src/test/java/com/anchor/ui/home/PermissionStateTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository` (Task 4), `DailyLogDao` (Task 2), `AnchorDate` (Task 8), `MorningGate` (Task 12), `KillSwitch`, `OverrideStatus` (Task 7), `SettingsViewModel`, `InstalledAppsRepository` (Task 21), `MorningAlarmScheduler`, `AnchorForegroundService` (Task 18).
- Produces:
  - `data class PermissionState(accessibilityEnabled: Boolean, overlayGranted: Boolean, usageStatsGranted: Boolean, notificationsGranted: Boolean, exportFolderChosen: Boolean)` with `val isFullyConfigured: Boolean` and `val missing: List<String>`
  - `class DashboardViewModel` with `val state: StateFlow<DashboardUiState>` and `fun refresh()`
  - `@Composable fun DashboardScreen(...)`
  - `class MainActivity : ComponentActivity()` with a `NavHost` over `dashboard` and `settings`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/ui/home/PermissionStateTest.kt`:

```kotlin
package com.anchor.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionStateTest {

    private val all = PermissionState(
        accessibilityEnabled = true,
        overlayGranted = true,
        usageStatsGranted = true,
        notificationsGranted = true,
        exportFolderChosen = true,
    )

    @Test
    fun `fully configured when everything is granted`() {
        assertThat(all.isFullyConfigured).isTrue()
        assertThat(all.missing).isEmpty()
    }

    @Test
    fun `accessibility is required`() {
        val state = all.copy(accessibilityEnabled = false)
        assertThat(state.isFullyConfigured).isFalse()
        assertThat(state.missing).contains("Accessibility service")
    }

    @Test
    fun `overlay permission is required`() {
        assertThat(all.copy(overlayGranted = false).missing).contains("Display over other apps")
    }

    @Test
    fun `usage access is required`() {
        assertThat(all.copy(usageStatsGranted = false).missing).contains("Usage access")
    }

    @Test
    fun `notifications are required for the foreground service`() {
        assertThat(all.copy(notificationsGranted = false).missing).contains("Notifications")
    }

    @Test
    fun `a missing export folder is listed but does not block operation`() {
        val state = all.copy(exportFolderChosen = false)
        assertThat(state.missing).contains("Markdown export folder")
        assertThat(state.canEnforce).isTrue()
    }

    @Test
    fun `enforcement requires accessibility and overlay only`() {
        assertThat(
            PermissionState(
                accessibilityEnabled = true, overlayGranted = true,
                usageStatsGranted = false, notificationsGranted = false,
                exportFolderChosen = false,
            ).canEnforce
        ).isTrue()

        assertThat(all.copy(accessibilityEnabled = false).canEnforce).isFalse()
        assertThat(all.copy(overlayGranted = false).canEnforce).isFalse()
    }

    @Test
    fun `missing entries are listed in setup order`() {
        val none = PermissionState(false, false, false, false, false)
        assertThat(none.missing).containsExactly(
            "Accessibility service",
            "Display over other apps",
            "Usage access",
            "Notifications",
            "Markdown export folder",
        ).inOrder()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.ui.home.PermissionStateTest'`
Expected: FAIL — `Unresolved reference: PermissionState`.

- [ ] **Step 3: Write `PermissionState.kt`**

```kotlin
package com.anchor.ui.home

/**
 * What the user still has to grant. Ordered as the onboarding presents them.
 */
data class PermissionState(
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    val usageStatsGranted: Boolean,
    val notificationsGranted: Boolean,
    val exportFolderChosen: Boolean,
) {
    /** The minimum needed to block anything at all. */
    val canEnforce: Boolean get() = accessibilityEnabled && overlayGranted

    val isFullyConfigured: Boolean get() = missing.isEmpty()

    val missing: List<String>
        get() = buildList {
            if (!accessibilityEnabled) add("Accessibility service")
            if (!overlayGranted) add("Display over other apps")
            if (!usageStatsGranted) add("Usage access")
            if (!notificationsGranted) add("Notifications")
            if (!exportFolderChosen) add("Markdown export folder")
        }
}
```

- [ ] **Step 4: Write `DashboardViewModel.kt`**

```kotlin
package com.anchor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.OverrideStatus
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.AnchorDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val today: DailyLog? = null,
    val recent: List<DailyLog> = emptyList(),
    val overrideStatus: OverrideStatus = OverrideStatus.INACTIVE,
    val permissions: PermissionState? = null,
)

class DashboardViewModel(
    private val dailyLogDao: DailyLogDao,
    private val anchorDate: AnchorDate,
    private val killSwitch: KillSwitch,
    private val settingsRepository: SettingsRepository,
    private val readPermissions: () -> PermissionState,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            _state.value = DashboardUiState(
                today = dailyLogDao.findByDate(anchorDate.today()),
                recent = dailyLogDao.recent(limit = 7),
                overrideStatus = killSwitch.check(settings),
                permissions = readPermissions(),
            )
        }
    }
}
```

- [ ] **Step 5: Write `DashboardScreen.kt`**

```kotlin
package com.anchor.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.ha.OverrideStatus

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onOpenSettings: () -> Unit,
    onFixPermission: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("The Anchor", style = MaterialTheme.typography.headlineMedium)

        if (state.overrideStatus == OverrideStatus.ACTIVE) {
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    "Override active — blocking is disabled from Home Assistant.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        state.permissions?.takeIf { !it.isFullyConfigured }?.let { permissions ->
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Setup incomplete", style = MaterialTheme.typography.labelLarge)
                    permissions.missing.forEach { item ->
                        Button(
                            onClick = { onFixPermission(item) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("Grant: $item") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Today", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (state.today?.morningCompletedAt != null) "Morning: done"
                    else "Morning: not yet",
                )
                Text(
                    if (state.today?.eveningCompletedAt != null) "Evening: done"
                    else "Evening: not yet",
                )
            }
        }

        if (state.recent.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge)
                    state.recent.forEach { log ->
                        val marks = buildString {
                            append(if (log.morningCompletedAt != null) "AM " else "—  ")
                            append(if (log.eveningCompletedAt != null) "PM" else "—")
                        }
                        Text("${log.date}   $marks")
                    }
                }
            }
        }

        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Settings")
        }
    }
}
```

- [ ] **Step 6: Write `MainActivity.kt`**

```kotlin
package com.anchor.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.AnchorDate
import com.anchor.service.AnchorAccessibilityService
import com.anchor.service.AnchorForegroundService
import com.anchor.service.MorningAlarmScheduler
import com.anchor.ui.home.DashboardScreen
import com.anchor.ui.home.DashboardViewModel
import com.anchor.ui.home.PermissionState
import com.anchor.ui.settings.InstalledAppsRepository
import com.anchor.ui.settings.SettingsScreen
import com.anchor.ui.settings.SettingsViewModel
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var dailyLogDao: DailyLogDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var killSwitch: KillSwitch
    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var installedApps: InstalledAppsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnchorForegroundService.start(this)

        val settingsViewModel = ViewModelProvider(
            this,
            factory {
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    questionDao = questionDao,
                    onScheduleChanged = {
                        // Re-arm on the next settings read; schedule() reads
                        // the persisted value itself.
                        rescheduleMorningAlarm()
                    },
                )
            },
        )[SettingsViewModel::class.java]

        val dashboardViewModel = ViewModelProvider(
            this,
            factory {
                DashboardViewModel(
                    dailyLogDao = dailyLogDao,
                    anchorDate = anchorDate,
                    killSwitch = killSwitch,
                    settingsRepository = settingsRepository,
                    readPermissions = { readPermissions() },
                )
            },
        )[DashboardViewModel::class.java]

        setContent {
            AnchorTheme {
                val navController = rememberNavController()

                val folderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    if (uri != null) {
                        // Persist across reboots — without this the URI is
                        // useless the next morning.
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        settingsViewModel.setExportTree(uri.toString())
                        dashboardViewModel.refresh()
                    }
                }

                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { dashboardViewModel.refresh() }

                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        val state by dashboardViewModel.state.collectAsState()
                        LaunchedEffect(Unit) { dashboardViewModel.refresh() }
                        DashboardScreen(
                            state = state,
                            onOpenSettings = { navController.navigate("settings") },
                            onFixPermission = { item ->
                                when (item) {
                                    "Accessibility service" ->
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    "Display over other apps" -> startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName"),
                                        )
                                    )
                                    "Usage access" ->
                                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    "Notifications" -> notificationPermission.launch(
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    )
                                    "Markdown export folder" -> folderPicker.launch(null)
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            installedApps = installedApps,
                            onPickExportFolder = { folderPicker.launch(null) },
                        )
                    }
                }
            }
        }
    }

    private fun rescheduleMorningAlarm() {
        // Fire-and-forget: the scheduler reads the freshly persisted settings.
        val pending = goAsyncScope()
        pending.launch { scheduler.schedule(settingsRepository.current()) }
    }

    private fun goAsyncScope() = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    private fun readPermissions() = PermissionState(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        overlayGranted = Settings.canDrawOverlays(this),
        usageStatsGranted = hasUsageStatsPermission(),
        notificationsGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        exportFolderChosen = runCatching {
            contentResolver.persistedUriPermissions.isNotEmpty()
        }.getOrDefault(false),
    )

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AnchorAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Builds a one-off ViewModel factory. [build] is named distinctly from
     * the overridden `create` so the call below is not self-recursive.
     */
    private fun <T : ViewModel> factory(build: () -> T) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = build() as V
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.ui.home.PermissionStateTest'`
Expected: PASS (8 tests).

- [ ] **Step 8: Run the whole suite and build the APK**

Run: `./gradlew :app:test :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests passing.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add dashboard, onboarding and navigation"
```

---

## Phase 7 — Device Verification

### Task 23: Install and verify on the real device

The unit suite covers every decision, but nothing so far proves the
accessibility service actually fires, the lock is hard to escape, or SAF
writes land in the right folder. This task is manual and requires a physical
Android 13+ device with USB debugging on.

**Files:**
- Create: `docs/manual-verification.md` (the checklist, so it can be re-run
  after future changes)

- [ ] **Step 1: Install the debug build**

```bash
./gradlew :app:installDebug
adb shell am start -n com.anchor/.ui.MainActivity
```

- [ ] **Step 2: Complete onboarding**

On the dashboard, grant each item the "Setup incomplete" card lists:
Accessibility service, Display over other apps, Usage access, Notifications,
and choose a Markdown export folder (e.g. `Documents/Anchor`).

Verify: the "Setup incomplete" card disappears and a persistent "The Anchor —
Protocol active" notification is present.

- [ ] **Step 3: Verify the evening block**

In Settings: set the evening window to a range containing right now, and check
one blocked app (e.g. YouTube).

```bash
adb shell am start -n com.google.android.youtube/.HomeActivity
```

Verify: the Evening Anchor screen appears within a second, with three
questions. Answer them; the app you opened is reachable afterwards. Re-open it
— it should now open straight through (block lifted for the night).

- [ ] **Step 4: Verify the Markdown file**

```bash
adb shell 'run-as com.anchor ls -la'   # app data, for reference
# The real check: open the chosen folder in a file manager, or:
adb shell content query --uri content://com.android.externalstorage.documents/document/primary%3ADocuments%2FAnchor
```

Verify: `YYYY-MM-DD.md` exists, contains `# Daily Anchor - <today>` and an
`## Evening` section with the three labelled bullets.

- [ ] **Step 5: Verify the morning lockdown and its allowlist**

In Settings set the morning window to contain now, then:

```bash
adb shell am broadcast -n com.anchor/.service.MorningAlarmReceiver
```

Verify:
- The Morning Anchor screen appears.
- Pressing **back** does nothing.
- Pressing **home** returns to the lock screen within roughly a second.
- Opening **recents** and swiping does not remove it.
- `adb shell am start -a android.intent.action.DIAL` reaches the dialer and is
  **not** interrupted. This is the safety check — if it fails, stop and fix
  `ForegroundAppDecider` before using the app for real.
- Answering both questions dismisses the lock, and it does not return.

- [ ] **Step 6: Verify the Home Assistant fail-open paths**

Turn Wi-Fi off, then repeat Steps 3 and 5.

Verify:
- Morning: the lock does **not** appear (fail-open).
- Evening: the 5-second delay screen appears instead of the three questions.

- [ ] **Step 7: Verify the remote kill switch**

Create `input_boolean.anchor_override` in Home Assistant, configure it in
Settings, and turn it on.

Verify: the notification changes to "Override active — blocking is disabled",
the dashboard shows the override banner, and opening a blocked app during the
evening window is not intercepted. Turn it off and confirm blocking resumes
without restarting the app.

- [ ] **Step 8: Verify persistence across a reboot**

```bash
adb reboot
```

Verify after boot: the persistent notification returns, and the next morning
alarm still fires (check with `adb shell dumpsys alarm | grep -i anchor`).

- [ ] **Step 9: Write the checklist to `docs/manual-verification.md` and commit**

Copy Steps 2–8 into that file as a re-runnable checklist.

```bash
git add -A
git commit -m "docs: add manual device verification checklist"
```

---

## Phase 8 — Optional: True Kiosk Lockdown

### Task 24: Device Owner enforcer

Only do this if the accessibility relaunch proves too easy to escape. It
requires a **factory reset** and a device with no other accounts provisioned.

**Files:**
- Create: `app/src/main/java/com/anchor/domain/DeviceOwnerLockdownEnforcer.kt`
- Create: `app/src/main/java/com/anchor/service/AnchorDeviceAdminReceiver.kt`
- Create: `app/src/main/res/xml/device_admin.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/anchor/di/EnforcerModule.kt`
- Modify: `app/src/main/java/com/anchor/ui/lock/MorningLockActivity.kt`
- Test: `app/src/test/java/com/anchor/domain/EnforcerSelectionTest.kt`

**Interfaces:**
- Consumes: `LockdownEnforcer`, `LockdownState` (Task 16).
- Produces:
  - `class AnchorDeviceAdminReceiver : DeviceAdminReceiver()`
  - `class DeviceOwnerLockdownEnforcer(context, dpm, fallback) : LockdownEnforcer`
  - `object EnforcerSelection { fun preferred(isDeviceOwner: Boolean): EnforcerKind }`, `enum class EnforcerKind { DEVICE_OWNER, ACCESSIBILITY }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/anchor/domain/EnforcerSelectionTest.kt`:

```kotlin
package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EnforcerSelectionTest {

    @Test
    fun `device owner is preferred when provisioned`() {
        assertThat(EnforcerSelection.preferred(isDeviceOwner = true))
            .isEqualTo(EnforcerKind.DEVICE_OWNER)
    }

    @Test
    fun `falls back to accessibility when not provisioned`() {
        assertThat(EnforcerSelection.preferred(isDeviceOwner = false))
            .isEqualTo(EnforcerKind.ACCESSIBILITY)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests 'com.anchor.domain.EnforcerSelectionTest'`
Expected: FAIL — `Unresolved reference: EnforcerSelection`.

- [ ] **Step 3: Write `AnchorDeviceAdminReceiver.kt`**

```kotlin
package com.anchor.service

import android.app.admin.DeviceAdminReceiver

/** Required for Device Owner provisioning; no behaviour of its own. */
class AnchorDeviceAdminReceiver : DeviceAdminReceiver()
```

`app/src/main/res/xml/device_admin.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

Add to the manifest's `<application>`:

```xml
        <receiver
            android:name=".service.AnchorDeviceAdminReceiver"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Write `DeviceOwnerLockdownEnforcer.kt`**

```kotlin
package com.anchor.domain

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.anchor.service.AnchorDeviceAdminReceiver
import com.anchor.ui.lock.MorningLockActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class EnforcerKind { DEVICE_OWNER, ACCESSIBILITY }

object EnforcerSelection {
    fun preferred(isDeviceOwner: Boolean): EnforcerKind =
        if (isDeviceOwner) EnforcerKind.DEVICE_OWNER else EnforcerKind.ACCESSIBILITY
}

/**
 * True kiosk lockdown via lock task mode. Requires the app to be provisioned
 * as Device Owner:
 *
 *   adb shell dpm set-device-owner com.anchor/.service.AnchorDeviceAdminReceiver
 *
 * on a factory-reset device with no accounts added. If provisioning is absent
 * this delegates to [fallback], so the app degrades rather than failing.
 */
@Singleton
class DeviceOwnerLockdownEnforcer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fallback: AccessibilityLockdownEnforcer,
) : LockdownEnforcer {

    private val dpm: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin = ComponentName(context, AnchorDeviceAdminReceiver::class.java)

    private val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Allowlisting the lock activity's package for lock task must happen
     * while we are Device Owner; the Activity itself calls startLockTask().
     */
    override fun begin() {
        if (!isDeviceOwner) return fallback.begin()
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        LockdownState.begin()
        fallback.reassert()   // launches MorningLockActivity, which locks the task
    }

    override fun end() {
        LockdownState.end()
        // stopLockTask() is called by the Activity, which owns the task.
    }

    override val isActive: Boolean get() = LockdownState.active

    override fun reassert() {
        if (!isDeviceOwner) return fallback.reassert()
        // In lock task mode nothing can leave the task, so there is nothing
        // to reassert. Kept for interface parity.
    }
}
```

- [ ] **Step 5: Teach `MorningLockActivity` to enter lock task**

Add to `MorningLockActivity.onCreate`, after `setTurnScreenOn(true)`:

```kotlin
        // No-op unless the app is Device Owner and the package is allowlisted
        // for lock task; safe to call unconditionally.
        runCatching { startLockTask() }
```

And in the `LaunchedEffect(state.submitted)` block, before `finish()`:

```kotlin
                        runCatching { stopLockTask() }
```

- [ ] **Step 6: Switch the binding in `EnforcerModule.kt`**

```kotlin
    @Binds
    @Singleton
    abstract fun bindLockdownEnforcer(
        impl: DeviceOwnerLockdownEnforcer,
    ): LockdownEnforcer
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :app:test --tests 'com.anchor.domain.EnforcerSelectionTest'`
Expected: PASS (2 tests).

- [ ] **Step 8: Provision and verify on device**

```bash
# On a factory-reset device with NO Google account added:
adb shell dpm set-device-owner com.anchor/.service.AnchorDeviceAdminReceiver
adb shell am broadcast -n com.anchor/.service.MorningAlarmReceiver
```

Verify: home and recents are fully inert (not merely countered), and the
status bar is pinned. Re-run the dialer allowlist check from Task 23 Step 5 —
under lock task the dialer will **not** be reachable, which is a real
behaviour change. If that is unacceptable, stay on the accessibility enforcer.

To undo: `adb shell dpm remove-active-admin com.anchor/.service.AnchorDeviceAdminReceiver`

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add optional Device Owner lock task enforcement"
```

---

## Spec Coverage

| Spec section | Requirement | Task |
|---|---|---|
| §1 | Morning window trigger (configurable) | 8, 12, 18 |
| §1 | Location gate, At Home vs Specific Rooms | 6, 12 |
| §1 | Fail-open when HA call fails | 5, 6, 12 |
| §1 | Full-screen non-dismissable Activity | 16, 20, 24 |
| §1 | Fully customizable questions | 3, 19, 21 |
| §1 | Allowlist: phone, messages, emergency, custom | 14, 17, 21 |
| §1 | Save to Room + Markdown, lock once per day | 15, 12 |
| §2 | Blocked apps during evening window | 13, 17, 21 |
| §2 | Location check → strict overlay vs 5s delay | 6, 13, 20 |
| §2 | Three customizable evening questions | 3, 19, 21 |
| §2 | Save, proceed, reset daily | 8, 13, 15 |
| §3 | HA URL / token / device id in Settings | 4, 21 |
| §3 | `GET /api/states/device_tracker.<id>` | 5 |
| §4 | Kill switch entity + override state | 4, 7, 21 |
| §4 | Checked at the moment of blocking | 7, 12, 13 |
| §4 | UI indication when override is active | 18, 22 |
| §5 | Local `YYYY-MM-DD.md` in a chosen folder | 10, 21 |
| §5 | Exact Markdown format, append evening | 9, 10 |
| §5 | Optional Joplin `POST /notes`, silent fallback | 11 |
| §6 | Permissions, foreground service | 16, 18, 22 |
| §6 | Accessibility service with allowlist | 14, 17 |
| §6 | Room `DailyLog` + `CustomQuestions` | 2, 3 |
| §6 | Compose, dark, minimalist | 19, 20 |
| §6 | Settings covering all eleven items | 21 |

Two spec details are implemented differently from the literal wording, both
noted inline where they occur:

1. **`DailyLog` columns vs. custom questions.** The five named columns exist
   as specified; user-added questions are stored alongside them in
   `extraAnswersJson` (Phase 1 preamble).
2. **Export path.** The spec shows a literal path
   (`/storage/emulated/0/Documents/Anchor/`); Android 13 scoped storage makes
   that unwritable without All Files Access, so Settings offers a folder
   picker whose persisted URI plays the same role (Task 10, Task 21).

---

## Execution Order Summary

```
Phase 0  Task 1                 scaffold
Phase 1  Tasks 2–4              Room, questions, settings
Phase 2  Tasks 5–7              Home Assistant, location, kill switch
Phase 3  Tasks 8–11             time, Markdown, SAF, Joplin
Phase 4  Tasks 12–15            morning gate, evening gate, allowlist, submit
Phase 5  Tasks 16–18            enforcer, accessibility service, alarms
Phase 6  Tasks 19–22            theme, lock screens, settings, dashboard
Phase 7  Task 23                manual device verification
Phase 8  Task 24                optional Device Owner kiosk
```

The app is first genuinely usable after Task 22. Tasks 1–15 build and test
without any Android device at all.
