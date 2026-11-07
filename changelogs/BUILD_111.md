# Build 111 — fast 12x12 map renderer

Build 111 changes the map renderer from 32 pixels per Minecraft block to 12 pixels per block.

## Changes
- 12x12 source pixels per Minecraft block at every zoom level.
- 192x192 bitmap per 16x16 chunk instead of 512x512.
- Four background bitmap-rendering workers.
- Six background terrain-loading workers.
- Visible chunks are prioritized by distance from the camera so the center appears first.
- Terrain loading no longer waits for entity/tile-entity marker reads.
- Entity and tile-entity markers are loaded asynchronously after terrain appears.
- Added a fast texture-variation path that avoids repeated material normalization/hash work for every pixel.
- Existing visible/off-screen bitmap cache protection from Build 110 is retained.

The goal is near-instant initial map display while preserving the detailed Blocktopograph-inspired appearance.
