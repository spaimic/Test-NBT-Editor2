# Build 186 — Heightmap-guided whole-map preview

Build 186 keeps Build 185's preview-reuse saving path and targets the remaining preview-generation gap versus Blocktopograph.

## Changes
- **DATA_3D heightmap shortcut:** the fast Overworld whole-map renderer now reads Bedrock's 16x16 heightmap directly from the first 512 bytes of DATA_3D.
- **Surface-directed subchunk scan:** for columns with a valid heightmap value, subchunks entirely above the surface are skipped and the surface subchunk starts at the hinted local Y.
- **Safe fallback:** Bedrock's `-64` heightmap sentinel keeps the previous exhaustive scan. The normal packed-block selection/color/biome logic is unchanged.
- **Build 185 save path retained:** saving still encodes the already-generated preview, so no second LevelDB render is introduced.

## Expected impact
The biggest expected improvement is preview generation on normal Overworld terrain, where many vertical subchunks can be skipped. Actual speed and visual equivalence should be verified on-device against Build 185.
