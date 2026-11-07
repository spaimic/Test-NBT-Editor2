# Build 244

## 2D Map Viewer
- **Biome overlay:** drawer → Map Options → *Show Biome Overlay*. Every column is colored by its biome (relief shading is kept). While it is on, the bottom-left readout gets a second line with the biome under the centre of the map, updating as you pan. Biomes are read per chunk in the background (one small LevelDB read), so the toggle is near-instant.
- **Slime chunk view:** drawer → Map Options → *Show Slime Chunks*. Slime chunks are green and every other chunk is black. Overworld only (it switches itself off in the Nether/End). The readout shows whether the centre chunk is a slime chunk.
  - Bedrock slime chunks do not depend on the world seed: a chunk is a slime chunk when the first MT19937 output seeded with `(chunkX * 0x1f1f1f1f) xor chunkZ` is divisible by 10, so the result is the same in every world.
- The bottom-left readout now sits on a light background so it stays readable on dark views.

## Notes
- Chunks saved in the old pre-1.18 biome format (no `Data3D` record) have no biome data, so they keep their normal colors while the overlay is on.
- Biome names/colors cover the Bedrock ids up to 185; an unknown id shows as "Biome #N" with a generated color.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device. The slime-chunk math was checked against a reference MT19937 implementation.
