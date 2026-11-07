# Build 214 — MCStructure Placement EOF Fix

## Fixed Placement Failed: EOFException
- Removed the entity serialization -> deserialization round-trip that Build 213 performed while preparing every structure entity. That validation could hit EOF on otherwise readable MCStructure NBT, especially when a list's declared element type did not match an in-memory item type.
- Entities are now deep-copied recursively in memory before placement, so the original MCStructure analysis is never mutated without re-parsing its NBT.
- Entity validation is performed directly on the in-memory actor data before any LevelDB write.
- Added strict NBT list validation during serialization so malformed list element types fail with a clear message instead of producing a stream-desynchronization EOFException.
- Actor storage wrapper, storage-key/digp handling, one-time placement backup, Y/subchunk placement, and atomic pre-write validation are preserved from Build 213.
