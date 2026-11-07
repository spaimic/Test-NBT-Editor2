# Build 107 — Fix Build 106 map renderer performance

Build 107 keeps Build 106's 32×32 source-pixel-per-block renderer, but fixes the ANR/freezing introduced by synchronous 512×512 chunk generation.

## Changes
- Moved expensive 512×512 chunk bitmap generation off the Android UI thread.
- Added a small two-worker rendering pool for visible chunks.
- `onDraw()` now only draws already-rendered bitmaps and schedules missing chunks.
- Added protection against duplicate rendering of the same chunk.
- Added render-generation invalidation so cleared/refreshed maps cannot receive stale background bitmaps.
- Added chunk identity checks so an outdated chunk render is discarded if the chunk data is replaced while rendering.
- Kept the 32×32 source resolution at every zoom level.
- Reused cached material colors/pattern calculations within each block render.
- Kept the bounded 24-chunk bitmap cache.
- Chunk clipboard previews can still render a missing chunk from the existing background clipboard thread.

## Expected result
Opening a map should no longer freeze the UI or trigger Android's "Nbt Tool isn't responding" dialog merely because a 512×512 chunk needs to be rendered. Chunks should appear progressively as their background renders complete.
