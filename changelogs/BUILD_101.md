# Build 101 — Diamond Coordinate Scanner

## Added
- Added **💎 Show All Diamond Coordinates** to the chunk action menu.
- Scans only `minecraft:diamond_ore` and `minecraft:deepslate_diamond_ore`.
- Scans all available subchunks belonging to the selected chunk and dimension.
- Converts chunk-local coordinates into absolute world `(x, y, z)` coordinates.
- Displays each result as `💎 (x, y, z)`.
- Added **Copy All Coordinates** to copy the complete coordinate list to the clipboard.
- If no matching diamond blocks are present, the dialog reports that none were found.

## Safety
This feature is read-only: it does not modify LevelDB or the world.
