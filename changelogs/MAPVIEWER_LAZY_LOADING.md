# Map Viewer architecture

The Map Viewer is intentionally viewport-driven. It does not scan the entire LevelDB before showing the map.

- The camera starts near `~local_player`.
- Only chunk coordinates intersecting the current viewport are requested.
- Each requested chunk is loaded with a direct LevelDB seek.
- Loaded terrain is cached and remains available while panning.
- Pinch zoom and +/- controls use a camera scale of about 3.75 to 20 pixels per block.
- At the widest zoom, individual 16x16 chunk borders are suppressed and 64x64 block (4x4 chunk) grid lines are shown, matching the MineTuner-style overview.
- No world-wide chunk index is built during Map Viewer startup.
- Entity/block-entity marker loading remains separate from the terrain startup path.

This is designed so a world with thousands of stored chunks only reads the small viewport instead of decoding the entire world.
