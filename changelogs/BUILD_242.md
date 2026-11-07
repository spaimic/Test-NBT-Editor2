# Build 242

## Speed
- **Y-level view no longer flashes the surface.** Chunks that load while a Y/layer view is active decode their layer data in the same pass and appear already at the right level.
- **First Y switch is much faster / near-instant.** Layer data is now prefetched quietly in the background after each chunk loads (reusing the already-read bytes, no second LevelDB read), and is stored run-length encoded (a fraction of the old memory). Switching levels keeps the old tiles on screen until the new ones are rendered instead of blanking the map.
- **Go to X/Z (and fast panning) lag:** chunks are now loaded nearest-to-camera first and chunks that scrolled out of view before their turn are skipped. Visible-chunk requests are only recomputed when the visible chunk rectangle actually changes, and marker updates are batched.
- **Nether loads faster:** the Nether renderer no longer allocates a new string per block while scanning columns, and only scans the occupied part of each column.
- Far zoom-outs use lower-resolution tiles (4 / 8 / 12 px per block by zoom) so thousands of chunks fit in memory.

## UI
- Minimum zoom lowered from 3.75× to 2×.
- The top-left "<dimension> • X/Z" box is merged into the bottom-left line, which now reads "<dimension> • X: … Z: …   Zoom: …×". The top-left box only appears for tap info, selections and the loading message.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
