# Build 158 — Blocktopograph-style Whole Map Renderer

## Entire Map PNG export
- 🌍 Reworked the whole-map exporter around chunk-by-chunk rendering, similar to Blocktopograph's picture renderer.
- ⚡ Up to five chunk decoders can run in parallel while PNG rows remain ordered and streamed.
- 🧠 Avoids allocating a giant world-sized Bitmap; the final PNG is streamed row-by-row.
- ♻️ Reuses the PNG raw-row buffer instead of allocating a new byte array for every row.
- 🗜️ Uses normal zlib compression rather than BEST_SPEED to produce a substantially smaller PNG.
- 🎚️ Whole-map pixels/block now use a 2–32 px/block UI range, with the actual maximum calculated from the world dimensions and the strict <5,000,000,000-pixel safety limit.

## Progress and cancellation
- 📊 Preview now shows a real percentage progress bar.
- 📊 Final PNG export reports chunk decoding and row encoding progress.
- 🚫 Preview/save progress dialogs cannot be dismissed by tapping outside them or pressing Back; only the **Cancel** button can stop the operation.
- 🛑 Cancelling aborts the renderer and prevents an incomplete PNG from being kept.
- 🔒 Only one entire-map export can run at a time.

## Preserved
- Build 157 gallery save fallback: Download → Pictures.
- Existing NBT Tool terrain colors.
- Existing whole-map bounds analysis and dynamic safe-scale calculation.
- Existing Selection Tool and map-picture functionality.
