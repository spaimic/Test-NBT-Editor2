# Build 164 — actual speed/progress fix

Important: this revision changes the actual source, unlike the earlier
Build 164 package whose intended speed edit did not match the source's
RENDER_THREADS implementation.

## Speed
- RENDER_THREADS is now based on available CPU cores, bounded to 2..12.
- Build 163's PNG encoder/filtering is untouched because its ~6 MB output is
  the successful size baseline.

## Progress
- Removed the hardcoded 20% starting offset.
- Chunk-loading progress occupies 0..15%.
- Row generation/encoding progresses from 15..99%.
- 100% is still emitted only after the complete operation is finalized.
