# Build 223

Build 223 adds native editor support for NBT `ByteArray`, `IntArray`, and `LongArray` tags and hardens `.mcstructure` placement.

## NBT arrays

- `ByteArray`, `IntArray`, and `LongArray` are expandable in the tree, with one scalar row per element.
- Individual array elements can be edited with the normal scalar editor.
- Array elements can be added or deleted, and matching scalar/array values can be pasted from the NBT clipboard.
- Global NBT search can navigate into array elements.
- `LongArray` uses the existing `Long` NBT icon because no dedicated LongArray drawable is provided.

The underlying NBT model and little-endian serializer already supported all three tag IDs, so no new wire format was introduced.

## mcstructure placement

- Keeps the existing `format_version: 1` placement path intact.
- The decoder also accepts the newer direct `IntArray` form of `structure.block_indices`, so v2 files can be inspected without changing the v1 placement path.
- Actor placement now allocates one fresh world actor `UniqueID` per entity and derives the actor storage identity from that same ID instead of generating two unrelated identities.
- Actor/digest writes are validated before the LevelDB batch is committed, preventing partial entity placement from being written.
- Existing `digp` entries are preserved and new actor IDs are appended, allowing repeated structure placement in the same chunk without replacing the previous actors.
- The binary `internalComponents.EntityStorageKeyComponent.StorageKey` payload is written explicitly rather than being UTF-8 encoded.
- Existing multi-layer subchunks are edited in the primary terrain layer; secondary layers are preserved.

## Verification

- NBT array round-trip test passed for ByteArray, IntArray, and LongArray values.
- Static Kotlin compilation passed for the modified NBT tree/editor code.
- Static Kotlin compilation passed for the modified mcstructure model/placement code.
- Regression test passed for a 144-entity placement repeated twice (288 actor records total) and for preserving a two-layer subchunk.
- A full Android Gradle/APK build was not run in this environment because the project has no Gradle wrapper and no `gradle` executable is installed here.
