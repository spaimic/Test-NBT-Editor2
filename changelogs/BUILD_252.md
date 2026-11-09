# Build 252

## Export selection as .mcstructure
- New chunk-menu entry when a selection exists: **📦 Selection: Export as .mcstructure**.
- **Size limit like a structure block:** a `.mcstructure` can be at most **64 × 384 × 64** blocks (X × Y × Z). Selections wider than 64 in X or Z are refused with a clear message (resize the selection to 64 × 64 or smaller); nothing is split into multiple files.
- The export dialog asks for a file name, the lowest/highest Y (defaults to the whole world height of the current dimension) and whether to include block entities (chests, signs, furnaces…). The file is saved to the public **Download** folder.
- Written in the real format: `format_version`, `size`, `structure` (two block layers so waterlogged blocks keep their water, palette with each block's full states and version, block-entity data per position) and `structure_world_origin`. Missing/void parts are exported as air.
- Mobs and dropped items are not exported.
- The writer was verified by exporting a test world and decoding the result with the app's own MCStructure decoder (block order, palette, block-entity positions and the size limit all checked).

## Validation
- The exporter itself was compiled and tested outside the app; the menu/dialog code is source-checked only. Not compiled as a whole; run the GitHub Actions build and test on device. Importing the exported file into a structure block in the game is the final check.
