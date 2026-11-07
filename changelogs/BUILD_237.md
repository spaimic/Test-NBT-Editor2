# Build 237 — Terrain, Preview, Ore Finder, and Chunk QoL

Based on Build 236. The Build 236 MCStructure placement fixes are preserved, including the LevelDB compaction table-size correction and the `IsAutonomous = 0` actor normalization.

## Changes

- **Terrain Editor — Replace All:** now searches the full 16×16 chunk across Y -64 through 320, rather than only the selected X/Z BlockChunk column. It decodes one column at a time to limit temporary memory use and routes the results through the existing edit/undo workflow.
- **Selection map preview:** the preview image is height-constrained and the dialog is sized to the screen, keeping the Save PNG and Cancel buttons accessible for tall images.
- **Ore Finder — Copy Group Coordinates:** uses a text-compatible keyboard so the comma separator in inputs such as `1,3,5` is available.
- **Chunk operations:** added a LevelDB seek-based chunk record lookup keyed by binary X/Z prefix. Chunk Information and chunk copy/paste no longer enumerate the entire world database to find records for one chunk. Deleting a chunk now writes all 24 air subchunks in one batch and respects the selected dimension.

## Validation status

Source-level sanity checks were performed on the modified Kotlin files (balanced braces and targeted code inspection). This environment does not have the Android SDK/Gradle wrapper available, so an Android APK build has not been run here. Build and on-device testing should be performed through the project's GitHub Actions workflow.
