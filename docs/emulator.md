# Running The Anchor on an emulator

`scripts/emulator.sh` boots a headless emulator, installs the debug build with
every permission already granted, and screenshots each screen. It is how the UI
gets reviewed without a phone in hand.

```bash
scripts/emulator.sh all       # boot, install, screenshot
scripts/emulator.sh up        # just boot
scripts/emulator.sh install   # build, install, grant permissions
scripts/emulator.sh shots     # just the screenshots
scripts/emulator.sh down      # stop the emulator
```

Screenshots land in `docs/screenshots/`.

## It will never touch a real phone

Every `adb` call in the script is pinned with `-e`, which means "the running
emulator". This matters: with a phone plugged in over USB and no emulator
running, plain `adb` would pick the phone, and the script would install to it,
rewrite its accessibility settings and screenshot its screen. The `-e` flag
makes that impossible.

If you *do* want to drive a real device, do it deliberately with
`adb -s <serial> …` rather than by loosening the script.

## One-time setup

Requires JDK 17 and the Android command-line tools.

```bash
brew install openjdk@17
brew install --cask android-commandlinetools

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "emulator" "system-images;android-35;google_apis;arm64-v8a"
```

Budget about 6 GB of disk: roughly 1 GB for the emulator, 1.5 GB for the system
image, and a couple more for the virtual device's own storage once it boots.

The image above is `arm64-v8a` for Apple Silicon. On an Intel machine use
`x86_64` instead.

## How the screens are reached

The lock, pause, meditation and confirmation screens are not exported in the
real app, so `adb shell am start` cannot open them. `app/src/debug/AndroidManifest.xml`
exports them **in debug builds only**, which lets the script open each one
directly with the right intent extras instead of trying to reproduce the
conditions that trigger it. The release APK keeps all of them private.

For example, the pause screen:

```bash
adb -e shell am start -n com.anchor/.ui.lock.PauseActivity \
  --ei com.anchor.extra.PAUSE_SECONDS 30 \
  --es com.anchor.extra.BLOCKED_PACKAGE com.android.settings
```

## What the script grants

The emulator would otherwise show the Setup card instead of the dashboard:

| Granted with | What |
|---|---|
| `appops set GET_USAGE_STATS allow` | Usage access, for the limits |
| `appops set SYSTEM_ALERT_WINDOW allow` | Display over other apps |
| `pm grant POST_NOTIFICATIONS` | The status notification |
| `settings put secure enabled_accessibility_services` | The accessibility service |

The export folder is not granted, because it is a Storage Access Framework
folder the user must pick by hand. The Setup card will still list it.

## Measuring the floating button

`screencap` does not capture `TYPE_ACCESSIBILITY_OVERLAY` windows, so the
button never appears in a screenshot even when it is plainly on screen. It is
also absent from the window list in `dumpsys window windows`. The measure that
does work is the app's own window session:

```bash
adb -e shell dumpsys window sessions | grep -B1 mPackageName=com.anchor
# numWindow=1  -> the button is up
# numWindow=0  -> it is not
```

Chasing this with the wrong probe cost real time: the button looked absent
when it was present, and present when it was not.

## Two traps

**Force-stopping the app disables its accessibility service.** Android will not
restart it on its own. Anything that runs `am force-stop com.anchor` must
re-enable the service afterwards and wait for it to bind:

```bash
adb -e shell settings put secure enabled_accessibility_services \
  com.anchor/com.anchor.service.AnchorAccessibilityService
adb -e shell settings put secure accessibility_enabled 1
adb -e shell dumpsys accessibility | grep -c 'label=The Anchor'   # 1 when bound
```

**Pick the test app carefully.** `com.android.settings` is on the always-allowed
list in `ForegroundAppDecider`, so it is never evaluated and no limit screen
will ever appear for it. YouTube opens a permission dialog on first launch,
which sits on top of everything. The Clock is a good neutral choice.

## Limits of this approach

- An emulator has no Home Assistant, so location gating always reads as
  unreachable. Turn on **Ask even without Home Assistant** to exercise the
  strict paths, or point the app at a real instance.
- `UsageStatsManager` on a fresh emulator has almost no history, so time and
  open counts read near zero until you use apps there.
- The floating lock button and the morning lockdown's resistance to home and
  recents are worth checking on real hardware too. Launchers differ.
