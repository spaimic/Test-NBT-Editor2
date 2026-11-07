# Build 139 — Chunk Block Editor crash fix

Build 139 is based on Build 138.

## Fixes
- Replaced the expensive full 16x16x16 String-cube parser used while opening the Chunk Block Editor with compact subchunk scans.
- Opening the 256 BlockChunk grid now decodes only packed indices + palette names needed to calculate the highest non-air Y for each column.
- Opening a BlockChunk decodes only the selected X/Z column rather than materializing all 4096 blocks of every subchunk.
- Reduced the 16x16 grid cell size while retaining horizontal scrolling, reducing Android view/layout memory pressure.
- Background load thread is named and avoids showing an error dialog after the Activity is finishing/destroyed.
- Existing BlockChunk Search, Replace/Replace All, Hide Air, Undo/Redo, coordinate display, and 3D viewer functionality are retained.
