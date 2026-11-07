# Build 212 — MCStructure Entity Storage + One-Time Placement Backup

## MCStructure entity placement fixes
- Generates fresh raw `ActorUniqueID` values for every structure entity.
- Derives the Bedrock actor storage key from the raw ID.
- Serializes the storage key big-endian for both `actorprefix` and `digp`.
- Keeps `UniqueID` and `ActorUniqueID` in the entity NBT as the raw actor ID.
- Never mutates the decoded MCStructure entity in memory; entities are round-tripped through NBT before editing.
- Validates entity positions, storage-key uniqueness, actor-key collisions, digest sizes, and actor/digest counts before the write batch.
- If entity validation fails, the placement write is aborted before `LevelDbBridge.writeBatch()`.

## Backup behavior
- Adds a dedicated per-world backup named `<world>_structure_placement.nbtbackup`.
- It is created only the first time MCStructure placement needs a safety backup.
- Later structure placements reuse that exact backup and do not create another one.
- Existing normal/user-created backups do **not** count as the dedicated placement backup.
- Deleting the dedicated backup allows the next structure placement to create a new one.

## Other behavior
- Existing Build 211 structure block placement and Y/subchunk fixes are preserved.
- Existing Experiment Mode requirement is preserved.
- Block entities remain intentionally pending.
