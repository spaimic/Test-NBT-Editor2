# Build 170 — fix Build 169 pipeline compile errors

Fixed the malformed worker-pool expression introduced in Build 169 and the
remaining Int/Long `minOf` mismatch.

- Defines `chunkWidth` explicitly from chunk bounds.
- Calculates `poolSize` with consistent Int operands.
- Converts `b.maxChunkZ` to Long before the batch `minOf`.
- Preserves Build 168's pipelined rendering architecture.
- Preserves the ~6 MB PNG encoder from Build 163.
- Preserves the working progress behavior.

No feature behavior was intentionally changed beyond making the exporter compile.
