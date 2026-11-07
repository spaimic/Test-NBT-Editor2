# Build 109

## Fix map renderer chunk disappearing/flickering

- Replaced the fixed 24-bitmap visible cache policy with visibility-aware eviction.
- Bitmaps belonging to chunks currently inside the viewport are never evicted or recycled.
- Only off-screen rendered chunk bitmaps participate in the bounded warm cache.
- Prevented repeated `setChunkData()` callbacks from recycling an unchanged chunk bitmap.
- Kept 32×32 source pixels per world block and background rendering from Build 107/108.
- Added safeguards against visible bitmap recycling during drawing.

The goal is to prevent the Build 107/108 behavior where chunks appeared after background rendering and then disappeared as another chunk was rendered.
