# Build 178 — whole-map capture performance/correctness pass

## Fixes and improvements

### Preview
The preview no longer creates a PNG and then decodes that PNG just to display a
1024px-ish preview. It now writes rendered rows directly into a small ARGB
Bitmap. This removes an entire PNG encode/decode cycle from preview generation.

### LevelDB scan
`LevelDbBridge.scanChunkRecordsStreaming()` now:
- performs one iterator pass,
- parses supported chunk keys inline without constructing `ChunkKeys.Info` for
  every database entry,
- ignores unrelated entity/metadata keys before allocating chunk structures,
- reports scan progress based on discovered chunks.

### Export progress
Progress is now explicitly staged:
- 0..30% database scan
- 30..80% chunk decoding/rendering
- 80..99% row/PNG generation
- 100% after the operation finishes

This prevents the previous 0%-for-minutes appearance.

### Chunk parsing
The common Overworld/End raw-chunk renderer uses a fixed 24-slot subchunk array
instead of allocating an associative map for every chunk.

### Preserved
- Build 163's successful ~6 MB PNG encoder/filtering
- Download/Pictures save fallback
- cancellation and non-dismissible dialogs
- dynamic pixels/block limits
- whole-map bounds and rendering colors
