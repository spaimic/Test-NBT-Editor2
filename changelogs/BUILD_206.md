# Build 206 — Interactive MCStructure 3D Preview

## Changes
- Replaced the old fixed-angle MCStructure preview in the analyzer with a dedicated full-screen 3D preview activity.
- Added real 3D cube rendering with yaw/pitch rotation by dragging.
- Added two-finger pinch zoom.
- Added Reset View.
- Added Y-layer selection and Show All Layers controls directly in the 3D viewer.
- Added accurate screen-space block picking; tapping a visible block opens its details immediately.
- Block details include local coordinates, ZYX linear index, palette index/name/version, exact palette states, block-position-data NBT, and the raw palette-entry NBT.
- Added support for opening imported .mcstructure files directly in the analyzer/3D viewer even when no LevelDB bridge is available.
- Normal tap on an MCStructure still opens the raw NBT editor; long press opens the analyzer.
- The viewer is strictly read-only and never changes the world.

## Notes
The Build 205 decoder remains the source of truth for structure hierarchy and ZYX indexing. This build focuses on making the reconstructed structure genuinely inspectable in 3D.
