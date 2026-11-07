# Build 238

## Terrain Editor
- Fixed chunk-wide Search results: matches are now collected from every X/Z column across all stored subchunks in the supported Y range (-64 to 320).
- Fixed Replace All to scan the same full chunk volume and apply pending edits before matching, so it works without requiring a BlockChunk to be opened first.

## 2D Map Viewer
- Added per-column layer-down viewing. Each press reveals the next stored non-air block below each column's current displayed layer.
- Added a Surface button to return to the original top layer. Layer data is loaded alongside visible chunk data, so the world does not need to be reopened.

## 3D Viewers
- Chunk 3D and MCStructure 3D previews use exposed-face culling and greedy merging of coplanar faces with matching colors, reducing redundant geometry and draw operations.
- Transparent and common partial/special blocks are excluded from opaque-cube occlusion decisions.

## Preserved
- Includes the Build 236 MCStructure entity-placement and LevelDB compaction fixes, plus the Build 237 changes.

## Validation
- Source-level checks only. Android compilation, GitHub Actions APK build, and in-game regression tests have not been run here.
