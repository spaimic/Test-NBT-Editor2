# Build 235

## Fixes

- **LevelDB compaction bug (world corruption).** `TableBuilder.getFileSize()` added a 4-byte
  empty-block estimate after `finish()`, so every compaction output was recorded in the
  MANIFEST 4 bytes larger than the real file. Large placements/copies that triggered a
  compaction left a database Minecraft (and other LevelDB readers) could not open. It now
  returns the exact written size once the table is finished.
- **Wolves not loading from MCStructure placement.** Actors with `IsAutonomous = 1` never
  loaded in-game. `McStructurePlacer.normalizeActorBaseFields` now always writes
  `IsAutonomous = 0` for placed actors.

## Unchanged

- 64 KiB chunked batching, actor/digp key generation and actor NBT layout.
