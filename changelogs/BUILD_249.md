# Build 249

## Faster map loading (the Blocktopograph approach)
Blocktopograph is quick because, per chunk, it reads the chunk's height map first and then fetches only the one or two subchunks that hold the surface. This build does the same for the Overworld:
- **Surface loads read far less.** Each chunk now costs one `Data3D` read (height map + biomes) plus only the subchunks near each column's recorded surface (one subchunk of margin above), fetched by key. Before, every subchunk of the chunk (~20) was read and copied. If a chunk's height map is missing or incomplete, it automatically falls back to the old full read.
- **Biomes come for free.** The same `Data3D` read supplies the biome overlay data, so there is no second read per chunk, and the biome decoding no longer expands all 24 layers (only the ones actually looked up).
- **Y/layer view is unchanged.** While a layer or Y view is active, chunks still use the full read (they need every subchunk).
- **Layer prefetch is polite.** The background Y-level prefetch now waits until the visible chunks have finished loading, instead of competing with them.
- Worker threads scale with the device (4–8).
- The Nether and End use their previous loaders.

## Edit consistency
- The map's block editor now keeps the chunk's stored height map in step with your edit (placing a block above the surface raises it; removing the top block lowers it), so edited chunks keep rendering correctly with the faster loader.

## Notes
- Chunks edited by other tools that do not update the height map can show stale tops if blocks sit more than about one subchunk above the recorded height.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
