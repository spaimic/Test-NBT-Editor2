# Build 105

## High-resolution map rendering

Build 105 upgrades the map renderer toward the detailed Blocktopograph-style appearance.

- Allows map zoom up to **32 screen pixels per world block**.
- Adds a high-resolution **512×512 source bitmap per chunk** (32×32 pixels per block) when the camera is close enough to see the detail.
- Keeps the existing 16×16 chunk bitmap at overview zooms to avoid excessive memory usage in large worlds.
- Adds deterministic 4×4 material-detail cells inside each 32×32 block footprint, producing subtle texture/shade variation instead of simply enlarging one flat pixel.
- Detail patterns are based on absolute world coordinates and block material, so they remain stable while panning and across chunk boundaries.
- Existing terrain colors, Nether layered rendering, entity/tile-entity markers, lazy chunk loading, and map controls remain unchanged.
