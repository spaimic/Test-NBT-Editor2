# Build 239

## 2D Map Viewer
- Added a **↑ Layer** button to step back up one layer (↓ Layer goes down, Surface jumps to the top).
- Layer data is now decoded lazily: it is only read when you first press ↓ Layer, then loaded for newly visible chunks while layer view is active. Normal map loading no longer parses every subchunk a second time, which should speed up large worlds.

## Validation
- Source-level brace check only. Not compiled; run the GitHub Actions build and test on device.
