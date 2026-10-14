# Snake Tracker

A fully local, offline Android app (Kotlin + Jetpack Compose + Room) for tracking your snakes:
feeding, refusals, sheds, weight, and food stock, with local reminder notifications.

**All data stays on your device.** The app has no internet permission, no analytics, no
cloud/account sign-in, and no sync code of any kind. Everything is stored in a private
SQLite database (via Room) inside the app's own data folder.

## Features

- **Multiple snakes**: name, species, morph, enclosure, notes, custom feeding interval.
- **Feeding log**: date, food type/size, accepted or refused, assist-fed flag, notes.
- **Shed log**: date, complete/incomplete, notes (stuck caps, retained eye caps, etc.).
- **Weight log**: date + grams, with notes, per snake.
- **Food stock**: track inventory of frozen feeders with quantity and low-stock warning.
- **Reminders**: the app arms a single exact Android alarm for the next feeding
  due instant (last feeding date + the snake's feeding interval, normalized to
  09:00 local time) and posts a local notification when it fires. On Android 12+
  it uses the exact-alarm API when the `SCHEDULE_EXACT_ALARM` permission is
  granted, and falls back to the inexact alarm API (~15-minute tolerance, still
  the correct day) when it is not. Reminders survive device reboot
  (`BootReceiver` re-arms the alarm). See
  `docs/adr/0001-exact-alarms-over-workmanager.md` for why.

## How to open and run it

1. Install [Android Studio](https://developer.android.com/studio) (Koala or newer recommended).
2. Choose **Open** and select this `SnakeTracker` folder (the one containing `settings.gradle.kts`).
3. Let Android Studio sync Gradle (it will download the Gradle wrapper/distribution and
   dependencies the first time — you'll need internet access for this one-time setup, the
   same as any Android project).
4. Connect an Android device (or start an emulator), then click **Run ▶**.
5. On first launch, allow the notification permission when Android asks, so feeding
   reminders can be shown.

Minimum supported Android version: Android 8.0 (API 26).

## Project structure

```
app/src/main/java/com/snaketracker/app/
├── data/                  Room entities, DAOs, database, repository (local storage only)
├── reminders/             due-time engine + exact-alarm scheduler + receivers + notification helper
├── navigation/            Jetpack Navigation Compose graph
└── ui/
    ├── screens/           Compose screens: snake list, snake detail, add/edit, dialogs, food stock
    ├── viewmodel/         SnakeViewModel exposing Flow-based state to the UI
    └── theme/             Compose Material3 theme
```

## Customizing

- **Change the default feeding interval**: edit `feedingIntervalDays` default in
  `data/entities/Snake.kt`, or just set it per-snake in the Add/Edit Snake screen.
- **Change when feeding reminders fire**: the due-time engine
  `reminders/ReminderPlanner.kt` (currently 09:00 local on the interval's due
  date).
- **Add photos per snake**: not included yet, but the `Snake` entity can be extended with an
  image file path (stored locally, e.g. in the app's internal files directory) and displayed
  with Coil or `BitmapFactory`.
- **Export/backup your data**: since everything lives in the local Room database file
  (`snake_tracker.db` inside the app's private storage), you could add an "Export to CSV/JSON"
  button that writes to the device's Downloads folder using the Storage Access Framework —
  this keeps you in full control of if/when any data ever leaves the device.

## Why no cloud sync

Per your request, this app is intentionally offline-only: no network permission is declared
in `AndroidManifest.xml`, and there is no networking code anywhere in the project. Your snake
records never leave your phone unless you manually export/back them up yourself.
