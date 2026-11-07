# Build 168 — pipelined whole-map rendering

This build targets the architectural bottleneck identified from the
Blocktopograph comparison.

## Main change
Instead of:
1. load one Z-strip
2. wait for every chunk
3. encode/write its rows
4. repeat

Build 168 submits chunk loads for a small batch of 4 Z-strips at once. While
the current strip is being consumed by the PNG writer, later strips can already
be loading/decoding on the worker pool.

## Safety
- Only 4 strips are in flight, avoiding a huge Future queue.
- No full-world Bitmap is allocated.
- Build 163's successful PNG encoding is untouched.
- Progress remains the existing 0..45 chunk phase + row phase.
- Map colors and output dimensions are unchanged.

The goal is to overlap LevelDB/chunk work with PNG row processing without
sacrificing the ~6 MB output achieved by Build 163.
