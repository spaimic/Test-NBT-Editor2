# Build 201 — Build 200 Compile Fix

Build 201 is a corrective build for Build 200. It preserves the Build 200 UI, intro animation, map controls, NBT tree animations, and all other functionality, while fixing the Kotlin compilation errors in `IntroActivity.kt`.

## Fixes

- Converted the elapsed-time value to `Float` when passing it to `drawLogoSequence()`.
- Corrected Android `Movie` API access from Kotlin properties to the required `width()` / `height()` method calls.
- Updated the GIF scaling calculations to use the corrected `Movie` dimensions.
- No behavior changes to the Build 200 intro timing or UI features.
