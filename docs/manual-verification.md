# Manual device verification

The unit suite covers every decision, but only a real Android 13+ device proves
the accessibility service fires, the lock is hard to escape, and SAF writes land
in the right folder. Re-run this after any change to the services or lock screens.

## Install

```bash
./gradlew :app:installDebug
adb shell am start -n com.anchor/.ui.MainActivity
```

## 1. Onboarding

On the dashboard, grant each item the **Setup** card lists: Accessibility
service, Display over other apps, Usage access, Notifications, and choose a
Markdown export folder (e.g. `Documents/Anchor`).

Verify: the Setup card disappears and a persistent "The Anchor — Protocol active"
notification is present.

## 2. Evening block

In Settings, set the evening window to a range containing right now, and add one
blocked app (e.g. YouTube).

```bash
adb shell am start -n com.google.android.youtube/.HomeActivity
```

Verify: the Evening Anchor screen appears within a second with three questions.
Answer them; the app you opened is reachable afterwards. Re-open it: it should now
open straight through (block lifted for the night).

## 3. Markdown file

Open the chosen folder in a file manager.

Verify: `YYYY-MM-DD.md` exists, contains `# Daily Anchor - <today>` and an
`## Evening` section with the three labelled bullets. With the Obsidian format
selected, the file starts with YAML frontmatter and a `Previous: [[...]]` link.

## 4. Morning lockdown and its allowlist

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
  **not** interrupted. This is the safety check: if it fails, stop and fix
  `ForegroundAppDecider` before using the app for real.
- Answering both questions dismisses the lock, and it does not return.

## 5. Home Assistant fail-open paths

Turn Wi-Fi off, then repeat steps 2 and 4.

Verify:
- Morning: the lock does **not** appear.
- Evening: the 5-second pause screen appears instead of the three questions.

## 6. Remote kill switch

Create `input_boolean.anchor_override` in Home Assistant, configure it in
Settings, and turn it on.

Verify: the notification changes to "Override active", the dashboard shows the
override banner, and opening a blocked app during the evening window is not
intercepted. Turn it off and confirm blocking resumes without restarting the app.

With "Treat an outage as override" on, turn Wi-Fi off and confirm blocking is
skipped; with it off, confirm the evening pause still appears.

## 7. Usage limits

1. In Settings → App limits add YouTube with: 2 min/day, 3 opens/day, 1 min
   cooldown, 1 min sessions, 10s pause.
2. Open YouTube. Verify the 10-second pause screen appears, counts down, and then
   lets you through.
3. Close it and immediately reopen. Verify "Not just yet" appears with a
   minutes-remaining message (the cooldown).
4. Wait out the cooldown, reopen, and stay in the app. Verify it is interrupted
   after roughly one minute with "That's the session".
5. Keep opening until the daily time or open budget is spent. Verify the block
   names the right reason and reports the reset time.
6. Check the dashboard: "Today's limits" shows used/limit for YouTube and turns red
   when exhausted.
7. Turn the kill switch on. Verify a limited app opens freely. Turn it off again
   and verify blocking resumes.
8. Revoke Usage access in system settings. Verify nothing is blocked by a limit
   and the app does not crash.
9. Set "Usage limits reset at" to two minutes from now. Verify the counters return
   to zero at that time and the app becomes available again.

## 7b. Session rejoin and early lock

1. Give an app a 5-minute session cap and 1 open/day. Open it, leave after a
   minute, come back within two more. Verify no pause and no block: it is the
   same open, and the dashboard still shows 1 open.
2. Stay past five minutes from the *first* open. Verify "That's the session".
3. Open the limited app. Verify the floating lock button appears **only**
   now: not on the home screen, not in unlimited apps, and not in the
   Anchor's own screens. Leave it alone for five seconds and verify it fades
   to nearly transparent; touch it and verify it brightens. Drag it and
   verify it stays where you put it, including after leaving and returning.
4. Tap it. Verify a "Lock <app> now?" sheet appears. "Not yet" dismisses it
   and the button comes back. "Lock it" sends the phone home, and the
   dashboard shows 1.5 opens after you reopen.
5. Tap it again on a later open. Verify it prompts **every** time, not just
   the first.
6. Start a video in the limited app. Verify the button is still reachable but
   faded, and that turning off "Floating lock button" in Settings removes it
   entirely.
7. If you previously enabled the system accessibility shortcut for The
   Anchor, turn it off under Android Settings → Accessibility → The Anchor →
   shortcut. The app no longer uses it.
8. With a cooldown set, verify the early lock starts it immediately.

## 7d. The pause cannot be walked around

1. Give an app a 30-second pre-open pause. Open it: the pause appears and shows
   what is left of the budget.
2. **Before the countdown ends**, press home, then open the app again. Verify the
   pause reappears with a **full** countdown, not a shortened one, and that you
   never reach the app without finishing it. Repeat twice: it must re-prompt
   every time, not just the first.
   This is verified on the emulator by `/tmp/verify_pause.sh` in the same shape:
   open, home, reopen, home, reopen.
3. Let it reach zero and tap Continue. Verify the app opens and is not paused
   again immediately.
4. Open it once more and tap **Close the app**. Verify you land on the home
   screen and the app did not come to the front.

## 7e. Breathing

1. From a pause, tap **Breathe for a minute instead**. Pick 1 minute and Begin.
2. Verify the circle grows on "Breathe in", holds, and shrinks on "Breathe out",
   the phase counter never shows 0, and a small haptic tick marks each change.
3. Let it finish. Verify it offers "I'm good" and "Open <app> anyway", and that
   choosing to open is allowed without another pause.
4. Start another and tap **That's enough** after a few seconds. Verify it ends
   and that a sit under 20 seconds is not recorded.
5. Check the dashboard: the Breathing row shows today's minutes and sit count.
   **Sit now** starts one with no app involved.
6. Hit a hard limit. Verify the blocked screen also offers the breath.

## 7c. Asking without Home Assistant

Turn on "Ask even without Home Assistant", clear the HA URL, and repeat steps
2 and 4. Verify the questions appear instead of the pause / skip. Turn it off
and verify the fail-open behaviour returns.

## 8. Persistence across a reboot

```bash
adb reboot
```

Verify after boot: the persistent notification returns, and the next morning alarm
is still armed:

```bash
adb shell dumpsys alarm | grep -i anchor
```
