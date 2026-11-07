# Build 161

## Whole-map PNG export palette fix

- Fixed the indexed PNG exporter crash caused by Android ARGB map colors such as `0xFF373737` not matching the RGB palette entry `0x373737`.
- PNG palette lookup now normalizes every rendered color with `color & 0xFFFFFF` before lookup.
- The existing NBT Tool map colors are preserved.
- The indexed PNG optimization from Build 160 is retained.
- Unexplored chunks (`0x373737`) are now encoded correctly.

## Existing Build 160 behavior retained
- Whole-map export uses the chunk-based renderer.
- Dynamic 2 px/block minimum and calculated maximum.
- Less-than-1-billion-pixel safety limit.
- Cancel-only progress dialogs.
- Monotonic preview/export progress handling.
