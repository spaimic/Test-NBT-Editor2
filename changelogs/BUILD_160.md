# Build 160

- Reworked whole-map PNG output to indexed-color PNG using NBT Tool's fixed map-color palette.
- Keeps PNG lossless while reducing per-pixel storage from RGB to 8-bit palette indices.
- Restored the normal zlib compression path from Build 158; removed the Build 159 regression caused by the RGB Sub-filter approach.
- Whole-map progress is now split into chunk decoding (0–45%) and ordered row encoding (45–99%); 100% remains reserved for completed output finalization.
- Preview uses the same exporter and reports the same monotonic progress phases.
- Keeps the 1-billion-pixel safety limit and dynamic 2–24 px/block scale calculation.
- Progress dialogs remain non-dismissible except through Cancel.
