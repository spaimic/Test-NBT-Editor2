# Build 215

- Fixed modern Bedrock actor placement format.
- ActorPrefix now stores the entity compound directly, with `internalComponents.EntityStorageKeyComponent.StorageKey` written as the exact binary storage key.
- ActorPrefix and `digp` storage IDs are little-endian 64-bit values, matching current Bedrock tooling.
- Structure entity coordinates now follow `.mcstructure` loading semantics: source `Pos - structure_world_origin + placement origin`.
- Added strict entity validation before the LevelDB batch is committed.
- Reworked MCStructure placement movement controls to 12 buttons in a 3-column × 4-row layout, including Z movement:
  - X: left/right by 1 and 5
  - Y: up/down by 1 and 5
  - Z: left/right by 1 and 5
- Block-position-data remains pending.
