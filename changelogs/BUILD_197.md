# Build 197 — PNG size, grass color, and progress fixes

Build 197 is based on Build 196 and keeps its exact shared 2D Map Viewer rendering path and Build 194/195 one-pass export architecture.

## 1. Smaller PNG files without changing map pixels

The whole-map PNG writer now uses zlib compression level 3 instead of `BEST_SPEED` level 1.

This is a deliberate middle ground:
- PNG remains lossless.
- No colors are quantized or removed.
- The one-pass renderer is unchanged.
- No second LevelDB scan/render is introduced.
- The adaptive Sub/Up row filtering chooses the cheaper filter using Minecraft-block-level deltas, avoiding expensive per-output-pixel filter scoring.
- Repeated pixels-per-block rows still use the very cheap Up/zero-delta representation.

The goal is substantially smaller PNGs while retaining the Build 194/195 speed advantage instead of switching to slow maximum compression.

## 2. Grass Block color now matches the flat 2D Map Viewer

The previous Bedrock grass base color (`146,188,88`) produced the noticeably pale green seen in exported maps.

Build 197 changes the Bedrock grass map color to the darker flat-map green used by the 2D Map Viewer (`82,137,33`).

`BiomeTint` no longer applies a biome-dependent tint to `grass_block`. This removes the random-looking switches between slightly light and very light green caused by different biome records.

Terrain relief is still applied afterward by the shared `MapViewerRenderStyle`, so hills and slopes can legitimately produce nearby shades while flat grass stays consistent.

## 3. Percentage bar now represents actual work

The old progress split treated the raw LevelDB scan as a full 0–20% stage even though the scan has no cheap total-key count and may discover only a small fraction of the chunks. This could make the bar appear to crawl through 20%, 21%, 22%, 23%, then suddenly jump to 100%.

Build 197 changes the weighting:
- 0%: render started
- 0–5%: LevelDB discovery phase
- 5–70%: actual completed chunk decoding/loading, including synthetic unloaded chunks
- 70–99%: actual map-row rendering/PNG generation
- 100%: complete render and finalized PNG

The scan no longer falsely claims 20% of the total work. The row stage remains tied to the actual number of rendered world rows.

## Performance constraint

Build 197 intentionally does **not** revert the Build 194/195 architecture:

`LevelDB scan → parallel chunk decode → one shared rendering pass → preview + final PNG simultaneously → instant Save`

There is still no full-resolution Bitmap and no second world render when Save PNG is pressed.
