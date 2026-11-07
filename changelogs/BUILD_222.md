# Build 222

## MCStructure placement — entity identity separation

This build continues from Build 221 and focuses on the remaining MCStructure multi-entity placement problem.

### Entity changes
- Keeps the working modern `actorprefix` / `digp` storage path.
- Separates the entity's world/runtime `UniqueID` from the opaque actor storage key used by `actorprefix` and `digp`.
- Generates a fresh `UniqueID` for each placed structure entity.
- Generates a separate fresh storage ID for each placed actor.
- Rewrites source entity-to-entity `Long` references from old structure `UniqueID` values to their new world `UniqueID` values.
- The binary `internalComponents.EntityStorageKeyComponent.StorageKey` continues to be serialized from the exact storage key used by the corresponding `actorprefix`/`digp` records.
- Keeps duplicate storage-key, duplicate UniqueID, digest-shape, actor/digest consistency, and collision validation before commit.
- Does not generate the non-standard `ActorUniqueID` NBT field.

### Preservation
- Existing successful block placement and Y/subchunk handling are unchanged.
- Existing 12-button X/Y/Z placement controls are unchanged.
- Existing one-time structure-placement backup behavior is unchanged.
- Android SDK workflow remains the Build 221 repository workflow fix (`packages: platform-tools`).

## Goal
The key test is to compare the already-working one-entity placement against the 144-entity structure now that `UniqueID` and actor storage identity are no longer forced to be the same value.
