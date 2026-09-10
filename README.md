# The Anchor

A personal, self-hosted Android app that enforces a daily mindfulness protocol:

- **Morning Anchor** — between 05:00 and 12:00 (configurable), if you are at home,
  the phone locks to a full-screen check-in until you answer your morning questions.
  Phone, messages, emergency and system apps are never blocked.
- **Evening Anchor** — opening a blocked app between 20:00 and 05:00 while at home
  shows three reflective questions first. Answer once and the evening is open.
- **Usage limits** — per-app daily minutes, daily opens, cooldowns, session caps and
  a pre-open pause, derived from `UsageStatsManager` so nothing drifts. Leaving an app
  and returning inside its session cap continues the same open. The accessibility
  button locks an app early; the next open then costs half.
- **Home Assistant** — location gating (at home / specific rooms) and a remote kill
  switch (`input_boolean`) checked at the moment of every block.
- **Markdown export** — one `YYYY-MM-DD.md` per day (plain or Obsidian format) into a
  folder you pick, with an optional push to Joplin.

Every failure mode fails **open**: an unreachable Home Assistant means less blocking,
never more, unless you turn on "Ask even without Home Assistant" in Settings.

## Build

Requires JDK 17 and an Android SDK with platform 35.

```bash
./gradlew :app:testDebugUnitTest      # JVM unit tests (Robolectric, no device)
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # faster build for daily use, signed with the debug key
./gradlew :app:installDebug           # sideload to a connected device
```

If Gradle cannot find the SDK, set `sdk.dir` in `local.properties`.

## First run

Open the app and work through the **Setup** card on the dashboard:

1. Accessibility service (required)
2. Display over other apps (required)
3. Usage access (for limits)
4. Notifications (for the status notification)
5. Markdown export folder

Then open Settings and configure the schedule, blocked apps, questions, Home
Assistant and export options. See `docs/manual-verification.md` for the on-device
checklist.

## Layout

```
app/src/main/java/com/anchor/
  data/db        Room: DailyLog, CustomQuestion, AppLimit
  data/settings  DataStore-backed AnchorSettings
  data/ha        Home Assistant client, LocationGate, KillSwitch
  data/export    Markdown renderer, SAF exporter, Joplin push
  data/usage     UsageStatsManager seam and the pure UsageCalculator
  domain         MorningGate, EveningGate, LimitGate, ForegroundAppDecider, SubmitCheckIn
  service        Accessibility service, foreground service, alarms, boot receiver
  ui             Compose: dashboard, settings, lock screens, theme
app/src/test     JVM unit tests mirroring the above
docs/            Spec, plan and verification checklist
```

The decision logic is pure Kotlin behind small seams (`SettingsProvider`,
`UsageStatsSource`, `DocumentStoreFactory`) so it is unit-tested without a device;
the Android components are thin adapters over it.
