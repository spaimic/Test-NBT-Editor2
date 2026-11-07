# Build 203 — MCStructure Analyzer

Build 203 begins the MCStructure investigation phase. It is deliberately **read-only** and does not place, edit, delete, or write any MCStructure data.

## Added

- Dedicated **MCStructure Analyzer** opened from the existing MCStructure Editor drawer entry.
- Lists `structuretemplate_` LevelDB records without scanning unrelated keys.
- Imports an external `.mcstructure` file through Android's document picker for analysis.
- Reads a selected record as Bedrock little-endian NBT.
- Displays:
  - `format_version`
  - `size` X/Y/Z
  - `structure_world_origin`
  - root keys and unrecognized root keys
  - palette entries, block names, states count, and `version`
  - decoded non-empty block positions and palette indices
  - entity count
  - `block_position_data` entry count
- Two `block_indices` layers are supported; layer 1 overrides layer 0 when its index is non-negative.
- Current decoding model shown explicitly in the UI:
  - `index = x + sizeX * (z + sizeZ * y)`
- Added an interactive read-only isometric block preview.
- Added a Y-layer slider for inspecting one layer at a time.
- Tapping a block row/preview opens its local position, linear index, palette index, version, and block states.
- Added a safety limit of 5,000,000 structure positions for the Build 203 inspection decoder.

## Important scope

Build 203 is a research/analyzer build. It does **not** implement placement or editing yet. The purpose is to use real `.mcstructure` data to verify the indexing, palette, block-state, entity, and position-data assumptions before any world-writing feature is attempted.

## Existing features intentionally untouched

- Whole-map capture/rendering/export path was not changed.
- Experiment Mode behavior was not changed.
- Existing NBT/Chunk editors were not changed.
