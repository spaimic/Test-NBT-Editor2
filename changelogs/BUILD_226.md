# Build 226

## MCStructure entity placement compatibility fix

- Changed MCStructure entity placement to use Bedrock's legacy per-chunk `Entity` LevelDB records.
- Kept fresh `UniqueID` generation and source-entity reference remapping.
- Entity positions are transformed from MCStructure coordinates into world coordinates as before.
- Existing entity records in the target chunk are preserved; newly placed records are appended as concatenated little-endian NBT compounds.
- Added `ChunkKeys.entityKey()` for Overworld and non-Overworld dimensions.
- Removes stale modern `internalComponents.EntityStorageKeyComponent` data from placed legacy entities.
- Added validation that every source entity produces exactly one stored record.

This intentionally avoids the modern `actorprefix`/`digp` path used by Builds 223-225, because those records were successfully written but Minecraft loaded zero entities from the test structure.
