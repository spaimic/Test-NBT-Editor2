# Build 144

## 3D Chunk Viewer performance

- Reworked the 3D renderer around an exposed-face mesh instead of drawing a separate three-face cube for every visible block.
- Uses outside-air reachability so enclosed interior blocks/cavities are not exposed by the normal full-chunk view.
- Generates the six physical cube faces independently: +X, -X, +Y, -Y, +Z and -Z.
- Greedily merges adjacent coplanar exposed faces with the same rendered block color into larger rectangular quads.
- The merged mesh is generated once after chunk loading; camera rotation/zoom only projects the existing quads.
- Keeps the fast isolated BlockChunk path when "Hide unselected areas" is enabled.
- Keeps the golden BlockChunk outline separate from the terrain mesh.
- Blocks remain unit cubes (1 x 1 x 1); merging only combines coplanar faces and does not distort block dimensions.

## Compatibility

- Retains the working Chunk Block Editor, BlockChunk-scoped Search/Replace, Hide Air, Undo/Redo, coordinate display, and newer block colors from previous builds.
- Retains the LevelDB session reuse behavior to avoid overlapping file locks.

## Verification

- Source archive verified with `unzip -t`.
- Android Gradle compilation was not available in this sandbox.
