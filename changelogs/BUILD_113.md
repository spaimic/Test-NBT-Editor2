# Build 113

## Accurate, clean map colors

- Reworked map rendering so each Minecraft block is a single clean 12x12 pixel tile.
- Removed intra-block checker/noise patterns that made terrain look like concrete powder.
- Kept only a tiny deterministic whole-block color variation (3 levels) for subtle visual richness.
- Added a dedicated concrete palette so concrete no longer falls through to terracotta colors.
- Added stronger explicit colors for common colored building blocks, including pink/black/white/gray/red/blue/green/yellow/orange/purple/cyan/lime/brown/magenta/light-blue concrete.
- Added `netherite_block`/`netherite` as a near-black map color instead of the generic gray fallback.
- Preserved Build 112 pinch-zoom camera-drift fix and Build 111's 12px/block fast renderer.
