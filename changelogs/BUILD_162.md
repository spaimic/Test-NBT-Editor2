# Build 162 — Fix dynamic map colors in whole-map PNG export

## Root cause
Build 160/161 used a fixed indexed PNG palette. NBT Tool's MapShading can
create colors dynamically, so a color such as 0x142d04 could fall outside
the predefined palette and abort the export.

## Fix
The whole-map PNG writer now uses standard lossless RGB PNG output. It no
longer depends on a hardcoded color palette, so every RGB color produced by
the renderer is valid.

## Preserved
- Dynamic 2..maximum pixels/block logic
- 1,000,000,000-pixel safety limit
- Chunk-based whole-map renderer
- Progress/cancellation behavior
- Download/Pictures save fallback
