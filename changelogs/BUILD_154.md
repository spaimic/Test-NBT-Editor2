# Build 154 — Entire Map Picture + Gallery registration

## New
- 🌍 Take Entire Map Picture from the Map Viewer menu.
- Scans the current dimension's LevelDB chunk keys to find the complete stored chunk bounds.
- No 2048×2048 Selection Tool limit is applied to whole-map capture.
- Choose 4–24 pixels per block.
- Analyze and show the exact world/image dimensions before rendering.
- Generate a scaled preview for large worlds.
- Render the complete map using terrain colors and the streaming PNG renderer.
- Save to Android Downloads through MediaStore.

## Gallery fix
- PNG exports are now registered with Android MediaStore on Android 10+.
- `IS_PENDING` is cleared only after the PNG is completely written.
- Older Android versions explicitly trigger MediaScanner.
- Selection map pictures use the same Gallery registration path.

## Preserved
- Build 153 map-picture preview fix.
- Selection Tool and all existing area operations.
