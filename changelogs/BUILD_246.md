# Build 246

## Fix
- **Biomes missing on most chunks (real cause):** a biome section that holds a single biome (the normal case for any uniform chunk) is stored as a 0-bit palette with *no palette-size field*, just the one biome id. The parser always read a size there, which shifted every following byte, so the whole chunk failed to decode. Only chunks near biome boundaries (which use multi-biome sections) decoded, which is why biomes showed up in patches. 0-bit sections are now read correctly.
- The record is decoded strictly (it must be consumed exactly) and a second layout is tried as a fallback; repeated sections (`0xFF` = same as previous) and records that end early are still handled.
- The grass/foliage biome tint uses the same parser, so tinted terrain will now be consistent across all chunks (it was missing in the same uniform chunks before), and the biome overlay, tap-to-show-biome and the bottom biome line cover them too.

## Validation
- Source-level checks only. Not compiled; run the GitHub Actions build and test on device.
