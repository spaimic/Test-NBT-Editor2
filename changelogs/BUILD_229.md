# Build 229 — MCStructure Export, Direct LevelDB Key Deletion, and Placement Improvements

## MCStructure export

- Adds `Export .mcstructure` to the raw NBT editor for `structuretemplate_` records.
- Adds `Export .mcstructure` directly to the MCStructure Analyzer.
- Writes the current Bedrock little-endian root compound directly as the `.mcstructure` file payload; no JSON conversion is used.
- Imported `.mcstructure` files can also be exported again from the Analyzer.

## Direct LevelDB key deletion

- Adds `Delete Direct LevelDB Key` to the normal NBT entry editor.
- This covers direct `structuretemplate_` records and individual modern `actorprefix` entity records.
- The confirmation dialog shows the raw key bytes in hexadecimal.
- Entity deletion intentionally removes only the direct `actorprefix` record; the chunk's `digp` digest is not silently rewritten.
- Adds direct deletion for a chunk's `BlockEntity` key. Because Bedrock stores multiple tile/block entities concatenated under that one key, deleting it removes the complete block-entity key for that chunk.

## MCStructure placement improvements

- `block_position_data[*].block_entity_data` is now materialized into destination `BlockEntity` chunk keys during placement.
- Destination block-entity coordinates are transformed using the same structure origin and placement coordinates as blocks/entities.
- Existing block entities at the same absolute coordinates are replaced instead of duplicated.
- Terrain, modern actor records/digests, and block-entity writes are committed in one LevelDB batch after validation.
- Existing actor digest validation now also rejects duplicate IDs inside a digest.
- Existing working modern `actorprefix` + `digp` entity placement path is preserved.
- Placement still refuses completely nonexistent target chunks rather than fabricating an incomplete chunk format; the failure is detected before the write batch begins.

## Known regression target

The 1-, 21-, and 54-entity tests from Build 228 must remain green. The 144-entity structure remains the primary actor-storage scaling test, and the 193-entity/very-large-structure test remains the large-world corruption investigation target.
