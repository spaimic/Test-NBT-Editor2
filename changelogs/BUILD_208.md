# Build 208 — First MCStructure Placement Prototype

Build 208 starts the first direct MCStructure-to-world placement pipeline.

## Placement workflow
- MCStructure Analyzer has **Place Structure in World (Prototype)**.
- Direct placement requires Experiment Mode.
- A complete `.nbtbackup` world backup is created immediately before any LevelDB writes.
- The user supplies world X/Y/Z (local structure coordinate 0,0,0) and a dimension.
- **Use Current Player Position** fills the coordinates and dimension from `~local_player`.
- Placement is confirmed in a warning dialog before writing.

## Block placement
- Structure block palette compounds are copied exactly, including `name`, `states`, and `version`.
- Bedrock subchunk persistent ordering remains `index = (x << 8) | (z << 4) | y`.
- World coordinates are converted with floorDiv/floorMod so negative coordinates work correctly.
- Existing subchunks are decoded, the highest storage layer is used for placed cells, and the subchunk is re-encoded.
- A missing subchunk inside an existing terrain chunk is created as a version-8 single-storage subchunk.
- Entirely new terrain chunks are intentionally **not** created in this first prototype.

## Entity placement
- `structure.entities` are copied into real world actor storage.
- Each entity receives a fresh unique actor ID.
- `Pos` is translated from structure-local coordinates to world coordinates.
- `UniqueID` is assigned to the new actor ID; an existing `ActorUniqueID` long is updated too.
- The affected `digp` records are extended rather than replacing existing entities.

## Deliberate limitation
- `structure.palette.default.block_position_data` / block-entity data is counted and reported but **not written yet**. This remains separate work because it needs to be validated against the world's block-entity list format before changing real world data.
- Rotation/transform controls are not implemented yet.

This build intentionally focuses on proving the direct block + actor placement path while keeping a complete backup and avoiding creation of malformed entirely-new terrain chunks.
