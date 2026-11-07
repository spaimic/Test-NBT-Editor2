# Build 138

Fix Chunk Block Editor startup crash by making chunk block loading lazy and avoiding materializing all 98,560 block positions at launch. Existing BlockChunk editing, search, replace, hide-air, undo/redo, coordinates, and 3D viewer entry points are preserved.

Base: Build 137.
