# Build 253

## 2D Map Viewer
- **Zoom up to 42×** (was 38×). The per-block grid and block editor still start at 34×.
- **Tap an entity / tile-entity icon to open its LevelDB record.** Below 34× zoom, tapping an icon opens the record directly: an entity opens its own `actorprefix` record in the key editor (Save available there); a tile entity opens the chunk's block-entity record and jumps straight to that block entity's editor (go back to the list and use Save to write changes). If several icons overlap where you tap, you pick which one from a list.
- **Block editor (34×–42×): "Entities / tile entities here (n)" button.** Shown only when an entity or tile entity is on that block column; lists them with their coordinates and opens the chosen record.
- **Undo / redo for block edits made from the map.** New ↶ Undo / ↷ Redo buttons in the block dialog (with counts), plus *Undo Block Edit* / *Redo Block Edit* in Map Options. Each edit stores the exact before/after of the 16-block section it changed (and the height map when that changed), up to 200 edits per map session. If something else changed that section since the edit, you are asked before it is overwritten. The history lasts until the map is closed.
- Entity icon positions are now rounded correctly for negative coordinates (they were one block off west/north of the origin).

## .mcstructure export
- **Entities are now exported** (mobs, items, armor stands, boats, …): every entity whose position is inside the area, with its full data. Positions are absolute and the file's `structure_world_origin` is set, the same convention the app's own placer uses. A new checkbox lets you turn entity export off; the result dialog shows the entity count.
- Verified by exporting a test world with entities (one outside the area, correctly left out) and reading the file back.

## Validation
- The exporter was compiled and tested outside the app; the UI code is source-checked only. Not compiled as a whole; run the GitHub Actions build and test on device.
