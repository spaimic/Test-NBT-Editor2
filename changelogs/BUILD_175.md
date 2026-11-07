# Build 175 — whole-map screenshot export speed

Diagnosis: the vendored `org.iq80.leveldb` port (see `DbImpl.get()` /
`DbImpl.iterator()`) takes a global lock and rebuilds a full merging
iterator across the memtable + every SST file on **every single call**.
The old exporter called `bridge.readSubchunks()` (which creates a fresh
iterator) plus `bridge.read()` for biome data **once per chunk**, from
inside the worker thread pool. With thousands of chunks in a full map,
that meant thousands of expensive iterator/snapshot rebuilds — done
essentially serially regardless of pool size, since they all funnel
through the same internal lock. That dwarfed the actual per-chunk
decoding/coloring CPU work, which is why more render threads never made
this proportionally faster.

Fix: `LevelDbBridge.scanChunkRecords()` does ONE sequential pass over the
whole database (same pattern `findBounds()` already used for bounds
detection) and buckets every subchunk + biome record for the export
region into memory by chunk coordinate — paying the iterator/lock setup
cost exactly once for the whole export instead of once per chunk.

`ChunkScanner.loadChunkFromRawData()` is a new entry point that renders a
chunk's grid purely from already-fetched bytes, with zero LevelDB access.
`WholeMapExporter.renderRows()` now scans once up front, then the worker
pool only does genuinely parallel CPU work (subchunk parsing, palette
resolution, coloring) with no per-chunk database round trips left at all.

Output PNG bytes, progress reporting, and cancellation behavior are
unchanged. `loadChunkDirect()` (used by the interactive Map Viewer's
on-demand viewport loading, where per-chunk LevelDB reads are the whole
point) is untouched.
