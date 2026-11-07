# Build 180 — fast whole-map screenshot renderer

Build 180 replaces the expensive whole-map block decoding path with an
allocation-light renderer inspired by the useful architecture in Blocktopograph
2.0.3.

### Main changes

- Added a compact Bedrock subchunk representation that keeps packed block-state
  words instead of expanding every subchunk into a `16x16x16 String` cube.
- Whole-map Overworld/End rendering now resolves only the blocks needed for the
  256 visible X/Z columns.
- Block colors and transparency are resolved once per palette entry instead of
  repeatedly performing block-name lookups for every block cell.
- Biome rendering now requests only the 256 biome cells needed by the final
  surface columns instead of expanding every biome subchunk to 4096 integers.
- Kept the existing single LevelDB streaming pass and bounded worker pipeline
  from Builds 175–179.
- PNG IDAT output is buffered into ~1 MiB chunks, reducing the number of small
  IDAT chunks/CRC operations during large exports.
- Nether capture deliberately keeps the existing renderer for this build because
  its cave-layer rendering needs a different optimization strategy.

### Compatibility / safety

The existing normal `SubChunkParser.parse()` path is unchanged for editing and
other tools. Build 180's compact parser is used only by whole-map capture.
Malformed later block-storage layers are treated like the existing parser: a
valid base layer can still be rendered.

### Goal

The primary goal is to reduce the time spent decoding and allocating block data
for large worlds. Benchmark Build 180 against Build 179 using the same world,
dimension, pixels/block and device before judging the result.
