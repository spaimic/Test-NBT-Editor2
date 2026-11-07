# Build 76

## Map viewer
- Adds Bedrock Data3D biome-aware map tinting for common foliage, grass and water tint classes.
- Adds elevation-based map shading.
- Uses nearest-neighbour chunk bitmap rendering to keep map pixels crisp while zooming.
- Fixes two-finger pinch gesture handling so a pinch cannot trigger a chunk tap and the camera remains at the final pinch position.
- Keeps lazy viewport chunk loading and the existing rendered-chunk bitmap cache.

## World names
- Keeps the exact display name separately from the sanitized filesystem folder name.
- Preserves formatting codes such as `§l§c...` in the My Worlds list.
- Existing worlds fall back to the `LevelName` value in `level.dat` if no display-name metadata exists.
