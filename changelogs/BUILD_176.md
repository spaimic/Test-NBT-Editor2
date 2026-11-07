# Build 176 — fixes OOM introduced by Build 175's speed fix

Build 175 fixed the slow export by scanning the whole database once
instead of once per chunk — correct diagnosis, but the implementation
(`LevelDbBridge.scanChunkRecords`) materialized every chunk's raw
subchunk bytes for the ENTIRE export region into one big in-memory map
before rendering a single pixel. `preview()` covers the same chunk
bounds as `export()` (only the output pixel scale is downsampled), so
this OOM'd there too, not just on a full-size export — matches the
"Failed to render entire map preview" crash.

Fix: replaced the batch scan with `LevelDbBridge.scanChunkRecordsStreaming()`,
which still does exactly ONE sequential LevelDB pass (same speed win as
Build 175 — no more expensive per-chunk iterator/snapshot setup), but now
emits each chunk's records to a callback the moment that chunk's block of
keys ends, instead of buffering the whole world. LevelDB keys sharing a
chunk-coordinate prefix are always contiguous under byte-wise comparison,
so this never needs more than one chunk's raw bytes at a time.

`WholeMapExporter.renderRows` now runs that scan on its own thread in
parallel with the existing row-writing/consumer loop, connected by a
`Semaphore` sized to the same `maxInFlight` cap the old per-chunk-job
queue used. The scan thread blocks on `inFlight.acquire()` whenever too
many chunks' raw bytes are already queued/completed-but-unconsumed, so
memory is bounded to roughly `maxInFlight` chunks' worth at any moment —
same footprint class as the original one-job-per-thread design, just
without the per-chunk database round trips.

Chunk coordinates the scan never sees at all (genuinely no data — an
unloaded/unexplored chunk) still get a synthetic empty result submitted
after the scan finishes, so every coordinate in bounds keeps getting
exactly one result, same as before; missing entries would otherwise
crash the row loop's `"Missing chunk result for $cx,$cz"` check.

Output PNG bytes, progress reporting, and cancellation are unchanged.
