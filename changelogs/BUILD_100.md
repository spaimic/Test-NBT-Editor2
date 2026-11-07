# Build 100 — Milestone

## Build 99 compile fix
- Fixed the `MapViewerActivity.kt` LevelDB key name dialog compile error.
- Replaced the invalid `singleLine = true` property assignment with Android's `setSingleLine(true)` API on the `EditText`.

This preserves the Build 99 `Open LevelDB Key by Name` behavior while making the project compile against the existing Android APIs.

## Milestone
Build 100 marks the first milestone release after the Build 99 fixes. No unrelated feature changes were introduced in this milestone fix.
