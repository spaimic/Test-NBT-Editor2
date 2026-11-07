# Build 233 — 64 KiB Chunked LevelDB Writes for MCStructure Placement

## MCStructure placement

- Replaces the single large LevelDB `WriteBatch` used by MCStructure placement with ordered, bounded-size write batches.
- Uses a **64 KiB maximum estimated batch payload** (`key + value + small record-framing allowance`).
- Individual LevelDB entries are never split across batches; an entry larger than the limit is written in its own batch.
- Keeps the existing write order: terrain/subchunks → actorprefix records → digp/digest records → block entities.
- Keeps the existing modern `actorprefix`/`digp` storage format, entity ID generation, StorageKey handling, and NBT serialization unchanged.
- The placement result now reports the number of LevelDB write batches and estimated total write payload.

## Why this build exists

Build 232 fixed the `Invalid palette size 134217738` parser bug, but the 144-entity structure still did not load its entities in Minecraft. The next hypothesis is that a large multi-record WriteBatch is causing write pressure or inconsistent on-disk state during large placement operations.

The 1-, 21-, and 54-entity tests remain regression targets. The 144-entity test is the primary batch-scaling test, and the 193-entity test remains the world-corruption stress test.

## Unchanged

- Existing general-purpose `LevelDbBridge.writeBatch()` behavior is unchanged.
- No replacement of the vendored `org.iq80` LevelDB implementation.
- No speculative changes to actor key/storage-key byte order or actor NBT format.
