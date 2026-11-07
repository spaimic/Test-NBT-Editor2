# Build 184 — Reuse the Generated Preview for Final PNG

## Why Build 184 exists

Build 183 showed that the renderer is now correct and fast enough, but the final save still took about 80 seconds and the PNG grew from about 4.94 MB to 14.82 MB after the faster zlib setting.

The important architectural issue was bigger than compression: after generating the preview, the Save PNG button called `WholeMapExporter.export()` again. That meant the app performed another LevelDB scan, chunk decode, biome lookup, row assembly, and PNG encode even though the preview had already rendered the same block colors.

Blocktopograph generates a Bitmap once and then saves that Bitmap. Build 184 follows the same reuse principle without allocating a giant full-resolution Bitmap.

## Changes

- Save PNG now reuses the already-generated whole-map preview bitmap.
- The preview bitmap contains one uniform color per Minecraft block (or a small uniform preview block), so Build 184 samples one pixel per block and expands it to the requested pixels-per-block output.
- The final PNG is still generated as a streaming PNG; no multi-gigabyte full-resolution Bitmap is created.
- The second LevelDB scan and second chunk-render pass are eliminated when the user saves immediately from the preview.
- Saving progress is now labeled `Encoding PNG` and runs from 0 to 100 based on output rows. 100% is emitted only after the PNG writer has finalized and closed.
- The previous `BEST_SPEED` zlib setting is not used. Build 183's larger 14.82 MB output did not produce the desired time reduction, so Build 184 returns to `DEFAULT_COMPRESSION` for a smaller lossless PNG.

## Expected result

The preview generation time should remain approximately the same because the preview renderer is unchanged.

The Save PNG phase should be substantially shorter because it no longer reads LevelDB or decodes chunks a second time. It should mainly be PNG encoding plus final output close/finalization.

The resulting image should match the Build 181/183 map because the same already-rendered block colors are used.
