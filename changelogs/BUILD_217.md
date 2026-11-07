# Build 217

## MCStructure entity placement improvements

Build 217 continues the working entity-placement path from Build 216 and focuses on making multi-entity placement safer.

### Changes
- Preserved the successful Build 216 actor-storage format and direct entity placement path.
- `internalComponents` is now preserved from the source structure entity instead of being replaced wholesale.
- Only `EntityStorageKeyComponent.StorageKey` is replaced/created for the newly generated actor storage key.
- Added a final actor-to-digest identity validation before the LevelDB write batch is committed.
- Kept duplicate actor storage-key checks and malformed existing `digp` checks.
- No entity writes are committed if the preflight validation fails.
- Kept the Build 216 12-button X/Y/Z structure movement controls unchanged.

The goal is to preserve entity-specific runtime data while making large multi-entity placements safer. The 1-entity Build 216 behavior remains the baseline.
