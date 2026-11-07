# Build 132 — Chunk Block Editor

Build 132 is based on Build 131 / the supplied Build 130-fixed source.

## New
- Added **Chunk Block Editor** to the Map Viewer hamburger menu.
- Enter a chunk X/Z coordinate and open a 16×16 grid containing all 256 X/Z columns.
- Each column exposes every Y level from **320 down to -64**.
- Tap a Y row to edit its block identifier.
- Changes are kept in memory until Save.
- Save rewrites only changed subchunk records using a LevelDB batch.
- Existing palette compounds are preserved where possible; new block names are added to the palette.

## Planned future improvements
- Search within a column/chunk.
- Replace and Replace All.
- Hide-air mode.
- Better block picker with block states.
- Multi-chunk editing.
- More precise preservation of layered block storage/state data.

## Safety
Always test block edits on a world backup first. Chunk storage is a binary Bedrock format and compatibility can vary by game version.
