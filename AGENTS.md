I need you to act as a senior Android developer. I want to build a personal, self-hosted Android app called "The Anchor". This app is designed to enforce a daily mindfulness and integration protocol. It is a personal project, so it does not need to be published on the Play Store. It must be written in Kotlin using Jetpack Compose for the UI, and follow the MVVM architecture.

CORE FUNCTIONALITY:

The app has two distinct modes: Morning Anchor and Evening Anchor. It integrates with Home Assistant for location awareness, supports Markdown export for data portability, and includes a remote kill switch via Home Assistant for override control.

1. MORNING ANCHOR (The Lockdown)

Trigger: The app detects that it is between 5:00 AM and 12:00 PM (configurable) and the user has not yet completed the morning check-in.
Location Gate (Home Assistant - Customizable): Before triggering the lockdown, the app must query Home Assistant to check the user's location.
Configuration: In Settings, the user can define:
Location Mode: At Home (anywhere in the house) OR Specific Rooms (e.g., "Bedroom", "Office").
Allowed Rooms: A comma-separated list of room names (e.g., Bedroom, Office).
Logic:
If At Home mode: Check if the HA entity state is home. If yes, proceed with lockdown. If not_home or API fails, skip.
If Specific Rooms mode: Check the HA entity's friendly_name attribute. If it contains ANY of the allowed room names, proceed with lockdown. If not, skip.
Override: If the API call fails entirely, default to skipping the lockdown to avoid trapping the user.
Behavior: When triggered, the app launches a full-screen, non-dismissable Activity that completely blocks the phone. The user cannot press back, home, or switch apps. The only way to dismiss it is to answer the required questions.
The Questions (Fully Customizable): The user must be able to define their own questions in Settings. The default questions are:
"What is my mission today?" (Single-line text input)
"What am I currently avoiding?" (Single-line text input)
Settings: The user can add, remove, or edit questions. Each question is a simple text field. The app will dynamically render the questions based on the user's configuration.
Allowlist (Critical): During the Morning Lockdown, the following apps must NOT be blocked:
Phone (Dialer)
Messages (SMS/MMS)
Emergency apps (e.g., Flashlight, Camera, or any app the user adds to the allowlist).
The user must be able to add custom apps to this allowlist in the Settings screen.
Logic: Once the user submits all answers, they are saved to a local Room database and exported to Markdown (see Section 4). The lock screen is dismissed. The app will not lock again until the next morning.
2. EVENING ANCHOR (The App Blocker)

Trigger: The user attempts to open a specific "blocked" app (e.g., YouTube, Instagram, TikTok) during a specific time window (e.g., 8:00 PM to 5:00 AM).
Behavior: Before the blocked app opens, The Anchor intercepts it and shows a full-screen overlay.
Location Check (Home Assistant Integration - Customizable): Before showing the overlay, the app must query the user's Home Assistant instance to check if the user is in a restricted location.
Configuration: In Settings, the user can define:
Location Mode: At Home OR Specific Rooms.
Allowed Rooms: Comma-separated list (e.g., Bedroom, Living Room).
Logic:
If At Home mode: If the user is home, show the strict overlay. If not_home, show the simple 5-second delay.
If Specific Rooms mode: If the user is in ANY of the restricted rooms, show the strict overlay. If not, show the simple delay.
Override: If the API call fails, default to the simple 5-second delay to avoid trapping the user.
The Three Questions (Fully Customizable): The user can define their own questions in Settings. Defaults are:
"One moment I led: (What decision did I make without seeking approval?)"
"One moment I softened: (When did I express a feeling or show genuine appreciation?)"
"One moment I faked it: (When did I act to get approval rather than express truth?)"
Logic: Once answered, the answers are saved to the Room database and exported to Markdown. The user is then allowed to proceed to the blocked app. The block resets daily.
3. HOME ASSISTANT INTEGRATION

Configuration: The user must be able to enter their Home Assistant URL (e.g., http://192.168.1.10:8123) and a Long-Lived Access Token in the app's Settings screen.
API Call: The app will make a REST API call to {HA_URL}/api/states/device_tracker.{device_id} to get the current state. The device_id is also configurable in Settings.
Logic:
For Morning: Based on the user's Location Mode configuration (At Home vs. Specific Rooms), determine if the lockdown should proceed.
For Evening: Based on the user's Location Mode configuration, determine if the strict overlay or simple delay should be shown.
4. REMOTE KILL SWITCH (Home Assistant Integration)

This is a critical feature. The user wants the ability to disable the app's blocking behavior from an external source (Home Assistant) so they cannot simply uninstall or disable the app on a whim.

Configuration: In Settings, the user can optionally enable the "Remote Kill Switch" and provide:
HA Entity ID: A input_boolean entity in Home Assistant (e.g., input_boolean.anchor_override).
Override State: The state that disables the app (e.g., on or off).
Logic:
Before ANY blocking action (Morning or Evening), the app must query the HA entity.
If the entity state matches the configured "Override State", the app skips all blocking for that instance.
If the entity state does NOT match, the app proceeds with normal blocking.
Important: The app should check this override at the moment of blocking, not just at app startup. This allows the user to remotely disable the app from Home Assistant if they need to (e.g., during an emergency).
UI Indication: If the override is active, the app should show a small notification or status indicator so the user knows the app is temporarily disabled.
5. MARKDOWN EXPORT (Joplin Integration)

Local File Export: After every successful check-in (Morning or Evening), the app must write a Markdown file to a configurable local directory (e.g., /storage/emulated/0/Documents/Anchor/).
File Name: YYYY-MM-DD.md (e.g., 2024-05-20.md).
File Content: A clean, readable Markdown format:
# Daily Anchor - 2024-05-20

## Morning
- **Mission:** [User's answer]
- **Avoiding:** [User's answer]

## Evening
- **Led:** [User's answer]
- **Softened:** [User's answer]
- **Faked:** [User's answer]
If the file already exists for that date, append the Evening section to it.
Optional Joplin API Integration: In Settings, the user can optionally provide a Joplin API URL (e.g., http://192.168.1.10:41184) and an API token. If provided, the app will push the Markdown note directly to Joplin using its REST API (POST /notes). If this fails, the app should silently fall back to local file export only.
6. TECHNICAL REQUIREMENTS

Permissions: Use SYSTEM_ALERT_WINDOW (overlay permission) and PACKAGE_USAGE_STATS (to detect when a blocked app is opened). Use a foreground service to monitor app usage.
Blocking Mechanism: Use an Accessibility Service to detect when the foreground app changes. If it matches a blocked app, launch the blocking Activity. Exception: If the foreground app is in the user-defined allowlist (Phone, Messages, etc.), do NOT block it.
Database: Use Room for storing daily entries. Create a table DailyLog with columns: id, date, mission, avoiding, led, softened, faked. Also create a table CustomQuestions for user-defined questions.
UI: Use Jetpack Compose. The blocking screens should be visually clean, dark-themed, and minimalist. No unnecessary buttons or distractions.
Settings Screen: A simple Compose screen to configure:
Morning lock time (start/end)
Evening lock time (start/end)
Blocked apps list (for Evening)
Morning allowlist apps (Phone, Messages, custom)
Custom questions for Morning and Evening
Home Assistant URL, Token, and Device ID
Location Mode (At Home vs. Specific Rooms) and room list
Remote Kill Switch entity ID and override state
Local Markdown export path
Optional Joplin API URL and Token
DELIVERABLES: Please provide the complete project structure, including:

The build.gradle.kts dependencies.
The AndroidManifest.xml with all permissions and services.
The main Activity and the blocking Activities.
The Accessibility Service class (with allowlist logic).
The Home Assistant API service (using Retrofit or Ktor).
The Room database and DAO (including custom questions table).
The Markdown export utility (local file + Joplin API).
The Remote Kill Switch logic (HA entity check).
The ViewModels and Compose UI screens (including dynamic question rendering).
Make sure the code is complete, compilable, and well-commented. Assume the user is running this on a personal Android device (API 33+).

Key notes on the new features:

Custom Questions: The app should store questions in a Room table. The blocking Activity reads the questions from the database and dynamically renders them. This allows the user to change questions without rebuilding the app.
Remote Kill Switch: This is a simple HA API call before any blocking action. The user can toggle an input_boolean in Home Assistant to temporarily disable the app. This is powerful because it requires the user to actively go to HA to disable it, which adds friction and prevents impulsive disabling.
Room Detection: The app checks the friendly_name attribute of the HA entity. If the user has multiple device_tracker entities (e.g., one for phone, one for watch), they can configure which one to use. The room list is a simple comma-separated string in Settings.