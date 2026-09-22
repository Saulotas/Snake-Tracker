# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- JSON backup and restore. A new Settings tab with a gear icon joins Calendar, Snakes and Food Stock in the bottom navigation bar, offering two actions. "Export backup" saves everything the app knows — your snakes, feedings, sheds, weights, and food stock — as one portable JSON backup file, in a location you choose (the app suggests a name carrying today's date). "Import backup" restores everything from such a file: the app first warns, plainly, that importing replaces all your current data, then restores in one step and lists exactly what came back — or explains exactly why an untrusted file (unreadable, made by a newer version, incomplete, or far too large to be a backup) was rejected whole, leaving your data untouched. After a successful import, your feeding reminders are immediately brought up to date with the restored data.

### Changed
- The app now targets Android 17 (API level 37), the newest stable Android release, so it runs under the current system rules on new phones. There is no visible change to how the app looks or works.
- The app's build tools and libraries (Kotlin symbol processing, Jetpack Compose, Navigation, Room, coroutines test) were updated to their latest stable versions. There is no visible change to how the app looks or works.
- On phones that don't allow exact alarms, a feeding reminder now arrives within a stated ten-minute window of its scheduled time, instead of at an unspecified moment.
- When a feeding reminder fires, the shade now shows the due snakes immediately: the due list travels inside the alarm itself, so the notification no longer waits on the data store to be read first. The list shown is exactly the snakes due at that alarm's moment — a renamed snake appears under its new name — and an alarm that carries no due snakes posts nothing while still setting up the next reminder.
- Feeding reminders now follow your clock right away. Set the time by hand, or travel into a different time zone, and the next reminder is recalculated for the new local time — a reminder set for 9:00 still arrives at 9:00 where you are now, instead of at the old time.
- Reopening the app now shows feeding reminders right away. On launch, every snake already due is listed immediately in the notification shade, and the next reminder is set right after — no more blank stretch where an overdue snake sits unnotified until the next alarm fires.
- Feeding reminders now pop up as a banner at the top of the screen, even while another app is open, instead of waiting quietly in the notification shade. Tapping the banner opens the app straight away, and a repeated reminder replaces its earlier notice rather than stacking duplicates.

## [1.1] - versionCode 3

### Added
- Feeding reminders: when a snake comes due, the app now sends a notification at 9:00 that morning, and repeats it every morning until the feeding is logged.
- On Android 12 and newer phones where exact alarms are turned off, the app now asks once whether to allow exact alarms, so feeding reminders can arrive at the exact scheduled minute. Choosing "Not now" remembers your answer and the app won't ask again.

### Changed
- Reminders now arrive right on time on phones that allow exact alarms. Where the phone doesn't allow it, a reminder may arrive up to about a quarter of an hour later — but always on the correct day.
- Reminders reschedule instantly whenever feeding information changes or the phone restarts, so the next reminder always matches the latest schedule.
- If the phone was off at reminder time, the missed reminder is delivered as soon as the phone is back on, instead of being skipped until the next morning.

## [1.0] - versionCode 2

### Changed
- The app's underlying build configuration was reorganized, with no change to the app's features or behavior.
- The app's internal build tools and libraries were updated to their latest supported versions. There is no visible change to how the app looks or works.
- The app's upgraded build setup was verified end-to-end and automated tests were added to confirm it works. There is no visible change to how the app looks or works.
- The repository's developer tooling for agents was adjusted internally. There is no visible change to how the app looks or works.
- The app's build process now runs on Java 17, a newer version of the Java runtime. There is no visible change to how the app looks or works.
- The build now automatically downloads a compatible Java development kit if one is missing, so the project builds without extra manual setup. There is no visible change to how the app looks or works.
