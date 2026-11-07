# Build 245

## Fixes
- **Biome overlay missing on most chunks:** Bedrock stores a repeated biome section as a single `0xFF` byte meaning "same as the previous section". The parser treated `0xFF` as the end of the record, so any column whose surface lay in such a section got no biome. Those sections are now resolved from the previous one. This also fixes the same gap in the grass/foliage biome tint, which could previously be missing in some chunks, so tinted terrain may look more consistent now.
- **Terrain Editor → 3D Chunk viewer:** the "Hide unselected areas" checkbox stayed disabled after you tapped an area. It is created before any selection exists and was never re-enabled; it now enables as soon as an area is selected (like "Open Selected Area").

## 2D Map Viewer
- Tapping the map now shows the biome at that spot (in the info box and in the chunk dialog title). If its biome data has not loaded yet, it is read on demand.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
