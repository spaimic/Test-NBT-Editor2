# Build 177 — fixes deadlock introduced by Build 176's OOM fix

Build 176 fixed the OOM by bounding raw-bytes-in-flight with a semaphore,
but released each permit only when the row-writing loop consumed that
exact coordinate, in strict row-major order (top-left chunk first).

The scan thread discovers chunks in LevelDB's raw key order, which is
scrambled by little-endian coordinate encoding — not row-major order. So
the scan would fill all `maxInFlight` permits with whatever scattered
coordinates it found first, almost certainly none of which was the
specific top-left-most coordinate the row-writer needed next. The scan
then blocked forever on the next `inFlight.acquire()`, waiting for a
release that could only come from the row-writer consuming a coordinate
the scan hadn't discovered yet — which it never would, because it was
stuck. Both threads waited on each other permanently: the progress bar
froze at 0% because no chunk after the first `maxInFlight` could ever
complete.

Fix: release the permit the moment a chunk's Callable finishes parsing
(inside a `finally` block), not when the row-writer later consumes its
result. This still bounds what actually needed bounding — raw subchunk
bytes in flight, the cause of Build 175's OOM — with zero dependency on
the order the row-writer happens to need coordinates in. The already-
parsed (small) results are free to buffer in `completedByCoord` for
however long it takes the row-writer to reach them; that buffer holds
16×16 color grids, not raw compressed NBT, so it's a much smaller
footprint than what caused the original crash.

Output PNG bytes, progress reporting, and cancellation are unchanged.
