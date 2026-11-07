# Build 136 — Chunk Block Editor + 3D Chunk Viewer

Base: Build 135 source (uploaded archive filename is build134_source.zip).

## Changes
- Search, Replace, and Replace All are now scoped to the current BlockChunk only (one X/Z column, Y=320..-64).
- Chunk Block Editor headers now show Chunk Coordinates, BlockChunk Coordinates, and exact world X/Z coordinates.
- Added View Entire Chunk in 3D from the 16x16 BlockChunk grid.
- Added View Chunk in 3D from a BlockChunk editor with the current BlockChunk automatically selected and outlined in gold.
- 3D viewer supports drag rotation, pinch zoom, reset view, BlockChunk selection, Open Selected Area, and Hide unselected areas.
- 3D rendering omits air and culls fully enclosed blocks where possible.
- Reuses the active LevelDB session when available to avoid overlapping file locks.
