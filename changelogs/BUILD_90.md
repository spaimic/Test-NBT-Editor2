# Build 90 — Nether layered map renderer

## Changes
- Added a dedicated Nether map renderer based on the decompiled legacy Blocktopograph `NetherRenderer` algorithm supplied for this project.
- Nether map rendering no longer selects the highest Y block as its surface.
- Each visible Nether chunk is decoded through its vertical subchunks and rendered as stacked cave/terrain layers using `getCaveYUnderAt` / `getHighestBlockYUnderAt`-style scans.
- Added per-layer relief shading and cave-slice thickness shading based on the supplied Blocktopograph algorithm.
- Added Nether point-of-interest color overrides for spawners, chests, Nether wart and portals.
- Nether entity and tile-entity markers continue using dimension-specific keys from Build 89.
- Overworld and The End map rendering paths are unchanged.

## Important implementation note
The current app's `SubChunkParser` does not expose Bedrock block-light values, so the Nether renderer uses the legacy renderer's non-light-aware fallback (`2.0f`) for block-light shading. The rest of the supplied layered algorithm is reproduced with the app's existing Bedrock map-color system.
