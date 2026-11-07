# Build 228 — Modern Bedrock Entity Placement Fix

## MCStructure entity placement

- Restores the modern `actorprefix` + `digp` entity storage path used by current Bedrock worlds.
- Fixes the actor storage-key byte order: the derived storage ID is serialized as two little-endian 32-bit words.
- Uses the exact same 8-byte storage-key payload for the `actorprefix` key, `digp` digest entry, and `internalComponents.EntityStorageKeyComponent.StorageKey`.
- Keeps fresh world-unique `UniqueID` generation and entity-to-entity reference remapping.
- Keeps entity positions transformed into the target world coordinates and assigns each entity to the chunk containing its final position.
- Preserves existing actors in each target chunk by appending new digest IDs instead of replacing the digest.
- Adds post-write verification that every actorprefix record is readable, decodes as NBT, contains `UniqueID`/`identifier`/`StorageKey`, and is referenced by the correct chunk's `digp` digest.
- Fixes the placement result counter to report the actual number of actor records written.

## Regression target

Primary test: the 144-wolf `.mcstructure`.

Expected placement report:

- Entities in structure: 144
- Entities placed: 144
- Entity verification: 144 actor records + 144 digest references

Minecraft acceptance remains the final compatibility test: the target world should load the 144 placed wolves instead of silently discarding them.
