# Build 190

## Fixes

- Fixed the Build 189 Kotlin compilation error in `MapViewerActivity.kt`:
  - `ScrollView.LayoutParams` was not a valid reference in this Android/Kotlin context.
  - Replaced it with `FrameLayout.LayoutParams`, which is the correct layout-params type for a `ScrollView` child.
- Preserved the Build 189 entire-map preview improvements:
  - The map image remains inside a bounded `ScrollView` so very tall previews do not push the Save PNG / Cancel buttons off-screen.
  - Preview progress uses the corrected 0–100% display mapping.
- Preserved all Build 188 ore-search and Build 186 whole-map performance improvements.

## Verification

- Confirmed there are no remaining `ScrollView.LayoutParams` references in the Java/Kotlin source tree.
- Confirmed the changed Kotlin file has balanced braces/parentheses.
- The repository source archive does not include a usable Gradle wrapper/Android SDK in this environment, so a full Android compilation could not be run locally.
