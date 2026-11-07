# Build 196 — Exact 2D Map Viewer Rendering

Build 196 keeps the Build 194/195 one-pass export architecture and fixes a visual-fidelity gap: **Take Entire Map Picture now uses the exact same per-column rendering/shading function as the normal 2D Map Viewer.**

## What changed

### One shared map renderer
Added `MapViewerRenderStyle.kt` as the single source of truth for the visible map pixel calculation.

`MapView` now calls `MapViewerRenderStyle.shadeColumn(...)` instead of carrying its own private shading implementation.

`WholeMapExporter` also calls the same function for every exported block/column.

This keeps the following identical between the live 2D map and exported whole-map picture:
- block base color from `ColumnInfo`
- biome-tinted block color already stored in `ColumnInfo`
- four-direction relief calculation
- relief clamp and final RGB conversion
- flat solid color for each rendered block cell

### Exact neighboring-column shading at chunk boundaries
The old whole-map exporter only wrote `ColumnInfo.color`, so it missed the MapView's elevation shading entirely.

Build 196 now supplies the same west/east/north/south surface-height neighbors used by `MapView.renderChunkBitmap()`.

To preserve Build 194/195 performance and memory behavior, it does **not** keep the entire world in memory. It keeps the current chunk row plus only the north/south boundary information needed for cross-chunk shading, and prefetches the next chunk row while the worker pool is already decoding.

### Speed preserved
The Build 194/195 architecture remains intact:
- one LevelDB scan
- one chunk decode pass
- one source render pass
- preview and final PNG generated together
- no second full world render during Save
- no full-resolution Bitmap allocation
- streaming PNG output
- `Deflater.BEST_SPEED`

The only additional work is a very small per-column shading calculation and boundary-height lookups.

## Target result

`Take Entire Map Picture` should now visually match the base terrain rendering of the 2D Map Viewer instead of using a separate flat-color approximation.

UI-only overlays such as entity icons, the player marker, selection handles, coordinate text, and map grid lines are intentionally not included in the exported terrain image.
