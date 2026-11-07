# Build 213 — MCStructure Actor Record Fix

## Entity placement
- Keeps Build 212's modern actor storage-key and `digp` logic.
- Fixes the actorprefix value format: the LevelDB actor record now stores the structure entity NBT inside a root `Entity` compound.
- Keeps fresh raw `ActorUniqueID` / `UniqueID` values inside that `Entity` compound.
- Adds an NBT round-trip validation before any write batch is committed.
- Validates that `Entity`, `Pos`, `UniqueID`, and `ActorUniqueID` survive serialization.
- If any actor record fails validation, the complete placement is aborted before the database write.

## Backup
- Preserves the Build 212 one-time per-world MCStructure placement backup.
- A normal backup does not count as the dedicated placement backup.
