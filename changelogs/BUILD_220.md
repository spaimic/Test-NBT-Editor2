# Build 220

## MCStructure placement improvements

- Improved multi-entity structure placement by allocating all destination actor IDs before writing any actor records.
- Rewrites entity-to-entity Long references when a structure entity `UniqueID` is remapped, helping rider/owner/relationship fields continue to point at the newly created actors.
- Stops generating the extra `ActorUniqueID` NBT tag; modern actor storage identity is represented by the `actorprefix` key and `internalComponents.EntityStorageKeyComponent.StorageKey`.
- Preserves the existing binary StorageKey serializer and actorprefix/digp validation.
- Verifies duplicate IDs and digest collisions before any LevelDB write is committed.

## Placement controls UI

Replaced the previous GridLayout movement control with four explicit horizontal rows so all 12 controls are visible inside the existing ScrollView:

X: ⬅️       | Y: ⬆️⚡   | X: ➡️
X: ⬅️⚡      | Y: ⬆️     | X: ➡️⚡
Z: ⬅️       | Y: ⬇️     | Z: ➡️
Z: ⬅️⚡      | Y: ⬇️⚡   | Z: ➡️⚡

Each control changes only its corresponding placement coordinate.
