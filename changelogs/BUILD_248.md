# Build 248

## 2D Map Viewer
- **Zoom range is now 1× to 38×** (was 2×–32×). Because far zoom-outs show many thousands of chunks, tiles use a smaller source resolution below 2× and at most the nearest 6,000 chunks are loaded at once; chunks beyond that stay empty until you zoom in or pan.
- **Per-block grid from 34× up.** Every block gets its own 1×1 cell.
- **Block editor (terrain editing on the map).** At 34× or more, tapping a cell opens a dialog for that column:
  - It opens on the highest block of the column and always shows the block name in an editable box (a missing `minecraft:` prefix is added automatically). **Apply** writes the change to the world and reloads that chunk on the map.
  - **−1 / +1** change the Y level. Holding a button repeats, and speeds up the longer you hold. The Y box can also be typed into.
  - **Hide air blocks** is checked by default. While checked, −1/+1 jump to the next non-air block; unchecked, they move exactly one block at a time (air included, so you can place blocks in the air above the terrain; a missing subchunk is created automatically).
  - **Chunk Menu** opens the normal chunk dialog (locators, selection, etc.), which is no longer reachable by tapping at 34×+.
- Below 34× tapping works as before.

## Notes
- Like the Terrain Editor, this edits block names only: heightmaps and lighting are not recalculated and there is no undo yet. Back up the world first.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
