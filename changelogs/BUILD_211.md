# Build 211

## MCStructure placement fixes

- Fixed the world Y/subchunk-Y placement bug in `McStructurePlacer`. Placement records now carry both local Y (0-15) and the actual subchunk Y coordinate, so the target subchunk key is derived from `floorDiv(worldY, 16)` instead of the local Y.
- Kept the 3D preview coordinate system unchanged; the preview and final placement now use the same world coordinates correctly.
- Fixed accidental parent `ScrollView` scrolling while rotating the 3D placement preview by temporarily disallowing parent touch interception during preview gestures. Two-finger pinch zoom remains supported.
- Left/right controls remain X -1/+1 and X -5/+5.
- No world data is changed while moving or rotating the preview; only final placement writes to the world.
