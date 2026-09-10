#!/usr/bin/env bash
# Drive The Anchor on a headless emulator and capture screenshots.
#
#   scripts/emulator.sh up        create the AVD if needed and boot it
#   scripts/emulator.sh install   build and install the debug APK, grant permissions
#   scripts/emulator.sh shots     screenshot every screen into docs/screenshots/
#   scripts/emulator.sh all       up, install, shots
#   scripts/emulator.sh down      stop the emulator
#
# Screens are reached with `am start`, which works because the debug build
# exports them (see app/src/debug/AndroidManifest.xml). The release APK does not.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
# avdmanager and friends look for `java` on PATH, not in JAVA_HOME.
export PATH="$JAVA_HOME/bin:$PATH"

# Every adb call is pinned to the emulator with -e. Without this, a phone
# plugged in over USB would be the only device adb sees, and this script would
# install to it and screenshot it. It must never touch a real device.
ADB_BIN="$ANDROID_HOME/platform-tools/adb"
# A function, not a string: the -e flag cannot survive being quoted as one word.
adb_e() { "$ADB_BIN" -e "$@"; }
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

AVD="${AVD:-anchor-test}"
IMAGE="${IMAGE:-system-images;android-35;google_apis;arm64-v8a}"
PKG="com.anchor"
SHOT_DIR="${SHOT_DIR:-docs/screenshots}"
# A real, always-present app to stand in for something you would limit.
VICTIM="${VICTIM:-com.android.settings}"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

create_avd() {
  if [ -f "$HOME/.android/avd/$AVD.avd/config.ini" ]; then
    log "AVD $AVD already exists"
    return
  fi
  log "Creating AVD $AVD"
  rm -rf "$HOME/.android/avd/$AVD.avd" "$HOME/.android/avd/$AVD.ini"
  echo no | "$AVDMANAGER" --silent create avd -n "$AVD" -k "$IMAGE" >/dev/null
  local ini="$HOME/.android/avd/$AVD.avd/config.ini"
  # Set the screen and memory directly rather than relying on a device
  # profile, since the device catalogue is not always present in the SDK.
  {
    echo "hw.ramSize=2048"
    echo "vm.heapSize=512"
    echo "hw.keyboard=yes"
    echo "hw.lcd.width=1080"
    echo "hw.lcd.height=2400"
    echo "hw.lcd.density=420"
    # The image's default data partition is 12 GB, which is far more than one
    # sideloaded app needs and often more than the disk has spare.
    echo "disk.dataPartition.size=4096M"
  } >> "$ini"
}

booted() {
  [ "$(adb_e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

up() {
  create_avd
  if booted; then
    log "Emulator already running"
    return
  fi
  log "Booting $AVD headless"
  "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect >/tmp/anchor-emulator.log 2>&1 &
  adb_e wait-for-device
  log "Waiting for boot to finish"
  local tries=0
  until booted; do
    tries=$((tries + 1))
    [ "$tries" -gt 180 ] && { echo "Boot timed out; see /tmp/anchor-emulator.log" >&2; exit 1; }
    sleep 2
  done
  # Skip the setup wizard and kill animations so screenshots are stable.
  adb_e shell settings put global device_provisioned 1 || true
  adb_e shell settings put secure user_setup_complete 1 || true
  adb_e shell settings put global window_animation_scale 0 || true
  adb_e shell settings put global transition_animation_scale 0 || true
  adb_e shell settings put global animator_duration_scale 0 || true
  adb_e shell wm dismiss-keyguard || true
  log "Booted"
}

install() {
  log "Building the debug APK"
  ./gradlew :app:assembleDebug --no-daemon -q
  log "Installing"
  adb_e install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null

  log "Granting what the app needs"
  # Usage access and the overlay are appops, not runtime permissions.
  adb_e shell appops set "$PKG" GET_USAGE_STATS allow || true
  adb_e shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
  adb_e shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  # Turning the accessibility service on by hand, as the user would in Settings.
  adb_e shell settings put secure enabled_accessibility_services \
    "$PKG/$PKG.service.AnchorAccessibilityService" || true
  adb_e shell settings put secure accessibility_enabled 1 || true
  sleep 2
  log "Installed"
}

# Give $VICTIM a limit, so the pause screen has remaining time and opens to
# show. Uses run-as, which works because the debug build is debuggable. If the
# image has no sqlite3 the screenshots still work, just without budget lines.
seed_limit() {
  local db="/data/data/$PKG/databases/anchor.db"
  log "Seeding a limit on $VICTIM"
  adb_e shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null 2>&1 || true
  sleep 4
  adb_e shell am force-stop "$PKG" || true
  local sql="INSERT OR REPLACE INTO app_limit (packageName, enabled, dailyMinutes, dailyOpens, cooldownMinutes, sessionMinutes, preOpenDelaySeconds) VALUES ('$VICTIM', 1, 30, 5, 20, 10, 30);"
  if adb_e shell "run-as $PKG sqlite3 $db \"$sql\"" 2>/dev/null; then
    log "limit seeded"
  else
    log "no sqlite3 on this image; pause screen will show no budget lines"
  fi
}

shot() {
  local name="$1"
  sleep "${2:-2}"
  mkdir -p "$SHOT_DIR"
  adb_e exec-out screencap -p > "$SHOT_DIR/$name.png"
  log "shot $name.png"
}

start_activity() {
  local activity="$1"; shift
  adb_e shell am start -W -n "$PKG/$activity" "$@" >/dev/null 2>&1 || true
}

shots() {
  rm -rf "$SHOT_DIR"; mkdir -p "$SHOT_DIR"

  seed_limit

  log "Dashboard"
  start_activity ".ui.MainActivity"
  shot "01-dashboard" 4

  log "Morning check-in"
  start_activity ".ui.lock.MorningLockActivity"
  shot "02-morning-lock" 3

  log "Evening check-in"
  start_activity ".ui.lock.EveningLockActivity"
  shot "03-evening-lock" 3

  log "Pre-open pause"
  start_activity ".ui.lock.PauseActivity" \
    --ei com.anchor.extra.PAUSE_SECONDS 30 \
    --es com.anchor.extra.BLOCKED_PACKAGE "$VICTIM"
  shot "04-pause" 3

  log "Limit reached"
  start_activity ".ui.lock.LimitBlockedActivity" \
    --es com.anchor.extra.LIMIT_REASON DAILY_TIME \
    --el com.anchor.extra.RESETS_AT "$(( $(date +%s) * 1000 + 36000000 ))" \
    --es com.anchor.extra.BLOCKED_PACKAGE "$VICTIM"
  shot "05-limit-blocked" 3

  log "Meditation, choosing a length"
  start_activity ".ui.lock.MeditationActivity" \
    --es com.anchor.extra.BLOCKED_PACKAGE "$VICTIM"
  shot "06-meditate-choose" 3

  log "Meditation, breathing"
  tap_text "Begin" || true
  shot "07-meditate-breathing" 4

  log "Confirm early lock"
  start_activity ".ui.lock.ConfirmLockActivity" \
    --es com.anchor.extra.LOCK_PACKAGE "$VICTIM"
  shot "08-confirm-lock" 3

  log "Settings"
  start_activity ".ui.MainActivity"
  sleep 3
  tap_desc "Settings" || true
  shot "09-settings-top" 4
  swipe_up; swipe_up
  shot "10-settings-apps" 3
  swipe_up; swipe_up; swipe_up
  shot "11-settings-questions" 3
  swipe_up; swipe_up; swipe_up
  shot "12-settings-home-assistant" 3

  log "Screenshots are in $SHOT_DIR"
  ls -1 "$SHOT_DIR"
}

# --- Poking the UI by what it says, rather than by coordinates ---

dump_ui() {
  adb_e shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb_e shell cat /sdcard/ui.xml 2>/dev/null
}

# Tap the centre of the first node whose attribute matches.
tap_attr() {
  local attr="$1" value="$2"
  local bounds
  bounds=$(dump_ui | tr '>' '\n' | grep -F "$attr=\"$value\"" | head -1 |
    sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  if [ -z "$bounds" ]; then
    log "could not find $attr=$value"
    return 1
  fi
  # shellcheck disable=SC2086
  set -- $bounds
  adb_e shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 1
}

tap_text() { tap_attr "text" "$1"; }
tap_desc() { tap_attr "content-desc" "$1"; }

swipe_up() {
  adb_e shell input swipe 540 1600 540 700 250
  sleep 1
}

down() {
  log "Stopping the emulator"
  adb_e emu kill 2>/dev/null || true
}

case "${1:-all}" in
  up) up ;;
  install) install ;;
  shots) shots ;;
  all) up; install; shots ;;
  down) down ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
