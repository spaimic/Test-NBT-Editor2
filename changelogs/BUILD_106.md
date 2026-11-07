# Build 106

## Blocktopograph-inspired map renderer rewrite

Build 106 rewrites the map rendering layer as an original implementation inspired by the behavior visible in the modern `rukiroki/blocktopograph` renderer, without copying its source code.

### Changes
- Every chunk is rendered at a fixed **512×512 source resolution**: 32×32 source pixels per world block at **every zoom level**.
- Removed the old 16×16 overview bitmap / 512×512 close-zoom switch.
- Added bounded LRU-style bitmap caching so large worlds do not retain hundreds of 512×512 chunk bitmaps simultaneously.
- Added deterministic fine-grained material texture variation that remains stable across chunk boundaries and zoom changes.
- Added local relief shading based on west/north surface-height differences, following the same general cartographic approach used by Blocktopograph.
- Kept the existing modern Bedrock chunk parser, lazy viewport loading, dimension handling, entity markers, block-entity markers, Nether renderer, and map interaction behavior.

### Design note
This is an independent renderer implementation. Blocktopograph was used as a technical/visual reference; no Blocktopograph source code is copied into this renderer.
