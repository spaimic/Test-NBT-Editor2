# Build 171 — whole-map renderer pipeline rewrite

This is the first real architectural change to the slow exporter.

The previous implementation submitted chunks in small Z-strip batches, waited
for the strip, then rendered/encoded its rows. Build 171 replaces that with a
bounded sliding window across the ENTIRE map:

- A small pool continuously loads/decodes chunks.
- Completed chunks are stored by coordinate.
- The writer consumes chunks in deterministic map order.
- New chunk jobs are submitted immediately as workers finish.
- There is no Z-strip barrier that leaves workers idle.
- Memory stays bounded by a small in-flight window.

The successful Build 163 PNG encoder is preserved unchanged.
Progress/cancellation behavior is preserved.
