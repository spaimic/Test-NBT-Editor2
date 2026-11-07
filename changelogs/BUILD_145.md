# Build 145

## 3D Chunk Viewer loading fix

- Fixed a major Build 144 regression in the exposed-face mesh generation path.
- Build 144 recalculated `blocks.keys.maxOf { it.y }` inside `airReachable()` for every exposed face.
- On normal chunks with thousands of blocks, this turned mesh construction into an extremely expensive repeated full-map scan and could make the 3D Chunk Viewer appear to never finish loading.
- Build 145 calculates the chunk's `minY` and `maxY` once and passes `maxY` into `airReachable()`.
- The Build 143 two-wall geometry fix is preserved; Build 145 does not revert the six-face / greedy surface mesh behavior.
- Outside-air flood fill and greedy merging remain enabled.

## Compatibility

- Keeps the existing LevelDB session reuse behavior.
- Keeps BlockChunk selection, Hide unselected areas, Open Selected Area, coordinate display, and block colors.
- Keeps the Build 144 surface-only greedy renderer and outside-air visibility rules.

## Verification

- Source archive extracted successfully from Build 144.
- Build 145 source was patched to remove the repeated `maxOf` scan from the per-face hot path.
- Android Gradle compilation could not be run in this sandbox because the uploaded source archive does not contain `gradlew`/Gradle wrapper.
