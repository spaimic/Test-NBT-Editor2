# Build 250

## Why loading was still slow
The database layer (a vendored, zlib-patched copy of the pure-Java iq80 LevelDB) had **no block cache**: every single lookup re-read a block from disk and re-inflated it. Bedrock keeps a chunk's records next to each other and neighbouring chunks share blocks, so loading a map view re-did the same work over and over. Native LevelDB (what Blocktopograph uses) caches blocks, which is a big part of why it feels instant.

## Changes
- **Block cache for the database** (new `BlockCache`, 24–64 MB depending on device memory, segmented LRU). Immutable SSTable blocks are cached after the first read; it is cleared whenever a world is opened and keyed by file path + size + modification time, so a restored/replaced file is never confused with an old one.
- **Far-zoom drawing is much cheaper.** The list of visible chunks is built once per visible rectangle instead of on every frame (no more tens of thousands of allocations per frame at 1×), and tiles are drawn without per-tile allocations.
- **Icons load only when zoomed in.** Entity / tile-entity icon reads (several extra LevelDB reads per chunk) are skipped below 3× zoom and loaded for the visible chunks when you zoom in, so zoomed-out loading is terrain only.
- **Map Options → Loading Stats (new).** Shows chunks loaded/skipped, average worker time per chunk, chunks per second, queue length, how many chunks are in memory, the DB block-cache hit rate, and memory use. A Reset button restarts the counters. If loading is still slower than you want, a screenshot of this tells me exactly where the time goes.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
