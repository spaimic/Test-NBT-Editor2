# Build 181

## Whole-map preview hang fix

Build 180 could stall during whole-map preview because synthetic empty chunks (coordinates inside the rectangular bounds with no chunk records in LevelDB) acquired an `inFlight` semaphore permit but never released it when their worker finished. Once enough empty chunks were queued, the scan thread could block forever on `inFlight.acquire()`, while the row consumer could wait forever for a later chunk. This is especially visible on sparse worlds and can make the progress bar appear stuck around the low 30% range.

Build 181 releases the permit in a `finally` block for synthetic empty-chunk tasks, matching the release behavior of real chunk tasks. This removes the semaphore leak/deadlock.

## Progress fix

Build 180 mapped the database scan to 0–30% and rendering to 30–99%, which made the preview visibly start at 30% after the earlier bounds phase. Build 181 uses one unified 0–100% scale:

- database scan: 0–20%
- chunk completion: 20–80%
- row assembly: 80–99%
- preview completion: 100%

Progress updates are monotonic so overlapping scan/render callbacks cannot move the displayed percentage backward.

## Other behavior

The Build 180 compact Overworld/End renderer, compact biome lookup, single LevelDB streaming scan, bounded worker pipeline, and buffered PNG writer are retained.

Nether rendering remains on the Build 179 path for now.

## Build 181 correction after device test
- Fixed the critical preview-image bug: streaming LevelDB subchunks were passed to the fast renderer in raw key order instead of highest-Y-first order.
- This caused the Overworld renderer to select low/negative subchunks first, producing an underground/cave-style image instead of the surface map.
- The fast renderer now defensively sorts subchunks by descending subchunk Y before surface selection, matching the normal 2D Map Viewer.
- Progress now explicitly starts at 0% and uses one 0..100 scale: scan 0-15%, chunk processing 15-75%, row assembly 75-99%, completion 100%.


- **Corrected artifact:** Build 181 was regenerated after the first device test; the ZIP now includes the subchunk-order fix and unified 0–100 progress changes described above.
