# Build 210 — MCStructure Placement Preview

Build 210 upgrades the Build 209 MCStructure placement prototype into an interactive placement-preview screen.

## Structure placement fixes
- Uses the exact integer coordinates selected in the placement screen for the final write.
- Current-player position loading is more defensive and supports `Dimension`, `DimensionId`, and `dimension` variants.
- Current-player X/Y/Z are stored together and shown back to the user after loading.
- Placement confirmation explicitly reports the coordinates that will be written.
- No silent coordinate fallback is introduced when player position cannot be read.

## 3D placement preview
- Added `McStructurePlacementPreviewView`.
- Shows nearby existing terrain surface columns for chunks intersecting the structure plus a one-chunk border.
- Existing terrain is read-only in the preview; preview movement never modifies the world.
- The structure footprint is shown as a golden 3D bounding box.
- Normal 3D gestures are preserved:
  - one finger drag rotates the camera
  - two fingers pinch to zoom
- Structure movement is controlled only by buttons, avoiding conflicts between camera movement and placement movement.

## Eight movement buttons
- ⬆️ = Y + 1
- ⬇️ = Y - 1
- ⬅️ = X - 1
- ➡️ = X + 1
- ⬆️⚡ = Y + 5
- ⬇️⚡ = Y - 5
- ⬅️⚡ = X - 5
- ➡️⚡ = X + 5

## Controls
- Coordinate fields remain editable for arbitrary placement positions.
- `Refresh 3D Placement Preview` reloads the nearby terrain explicitly.
- Dimension changes refresh the terrain preview.
- Final placement remains behind Experiment Mode and retains the full-world backup step from Build 209.

## Intentionally unchanged
- No freehand dragging of the structure in 3D.
- No rotation of the structure.
- Block-position-data / block entities remain pending.
- Existing terrain chunk requirement remains.
