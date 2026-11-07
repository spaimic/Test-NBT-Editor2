# Build 205 — MCStructure Analyzer Fixes

## What was fixed
- Corrected the .mcstructure hierarchy: `structure`, `structure.block_indices`, `structure.entities`, and `structure.palette` are now decoded from the correct nested compound.
- Corrected `block_position_data` to read from `structure.palette.default.block_position_data`.
- Corrected Bedrock block-index ordering to ZYX: `index = z + sizeZ * (y + sizeY * x)`.
- Entity lists are now detected and displayed in the analyzer, including a basic identifier and `Pos` preview plus a tap-to-inspect dialog.
- `Show All Layers` now properly clears the layer filter and updates the preview once decoded blocks are present.
- Normal tap on a structure record opens the existing raw NBT editor.
- Long press on a structure record opens the MCStructure Analyze screen.

## Safety
This build remains read-only from the analyzer. It does not place structures or modify world terrain.
