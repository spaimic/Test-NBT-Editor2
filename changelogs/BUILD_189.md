# Build 189

Build 189 fixes two issues in the entire-map picture workflow.

## Fix 1: Entire Map Picture Preview buttons hidden on large maps

The preview dialog no longer gives the full-height map bitmap directly to the AlertDialog. The map image is placed inside a bounded vertical ScrollView, with a fixed display area based on the device screen height. Large/tall previews can therefore be scrolled while the **Save PNG** and **Cancel** buttons remain visible.

The generated bitmap itself is unchanged, and the final PNG save path still reuses the already-rendered preview without scanning LevelDB again.

## Fix 2: Incorrect whole-map preview percentage

The renderer now reports chunk completion as a true 0..100 percentage before the UI maps it into the unified progress range. The unified preview progress is:

- 0–20%: LevelDB/world scan
- 20–70%: chunk decoding/render preparation
- 70–99%: output row assembly
- 100%: preview generation fully complete

This fixes the previous mismatch where `renderRows()` emitted only 0–45 for chunk progress while the caller treated that callback as 0–100, causing the displayed percentage to be misleading and leaving an incorrect gap in the progress scale.

## Preserved behavior

- Same whole-map renderer and surface-map output as Build 186/188.
- Same preview scaling behavior.
- Same final PNG encoding from the generated preview.
- Same cancellation behavior.
- No changes to ore searching.
