# Build 114

## Map Viewer improvements
- Removed coordinate-based per-block color variation from the map renderer.
- Flat worlds now remain visually flat instead of showing mottled color patches.
- Terrain shading is now driven only by surface-height differences.
- Uses west/east/north/south neighboring columns, including neighboring chunks when available, for smoother elevation relief.
- Relief is deliberately subtle and capped so block colors remain recognizable.
- Keeps the 12x12 pixels-per-block source resolution and Build 111+ fast loading/caching.
- A failed direct LevelDB chunk read is no longer permanently marked as loaded, preventing a transient read failure from becoming a permanent gray chunk.
