# Build 89

## Fixes
- Fixed a chunk-copy crash caused by passing an already-extracted 8-byte actor ID to `EntityKeys.actorIdHex`, which expects a full 19-byte `actorprefix` LevelDB key.
- Nether and The End entity lists now read the dimension-specific `digp` key instead of always reading the Overworld key.
- Nether and The End block-entity lists now read the dimension-specific chunk block-entity key instead of always reading the Overworld key.
- Map Viewer now passes the selected dimension into the entity and block-entity editors.
- Chunk Information now counts entities from the currently selected dimension instead of only Overworld.

## Testing
- Verify copying Nether and The End chunks no longer throws `IndexOutOfBoundsException: toIndex (19) is greater than size (8)`.
- Verify Nether/The End chunks with entity and tile-entity icons open their actual records when selecting `Show Entities` / `Show Block Entities`.
- Verify Overworld behavior remains unchanged.
