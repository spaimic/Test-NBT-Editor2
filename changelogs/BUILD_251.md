# Build 251

## Smooth panning at far zoom (1×–3×)
At 1× the screen shows thousands of chunks, and each one was its own bitmap/draw call every frame, so panning lagged once most of the screen was loaded.
- **Composite tiles:** below ~3.2× zoom the chunk tiles are copied (on a background thread) into 256 px super-tiles, like Blocktopograph's tiles, so a full screen needs a couple of hundred draw calls instead of ~10,000. Chunk tiles stay the source of truth, super-tiles refresh automatically when chunks load, change (block editor, Y view, biome view) or the zoom tier changes, and they are skipped/evicted outside the view. Until a super-tile exists the chunks are drawn individually, so nothing goes blank.
- **Cheaper frames:** unloaded chunks no longer cost a draw call each (the background is already that color), per-chunk tile access ordering and the off-screen bitmap cleanup no longer run every frame, and panning no longer sorts ~10,000 boxed chunk positions every time the view crosses a chunk border.

## Notes
- Super-tiles use 16-bit color to halve their memory, which is invisible at far zoom. Nothing changes at 3.2× and closer.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
