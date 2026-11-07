# Build 241

## Fix
- Fixed the Build 240 compile error (`Unresolved reference: loadedChunkGrids`). The helper was accidentally dropped from `MapView.kt` while adding Y-level viewing; it is restored.

## Includes everything from Build 240
- View at Y level, Go to X / Z, and colored Locators (see BUILD_240.md).

## Validation
- Checked that every `mapView.*` member used by `MapViewerActivity.kt` exists in `MapView.kt`. Not compiled locally; run the GitHub Actions build.
