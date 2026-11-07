# Build 195

## Fix: missing/skipped section in Take Entire Map Picture

Build 194 introduced the fast one-pass exporter that renders the world once while simultaneously creating the small preview and streaming the full-resolution PNG.

The speed was correct, but `PngWriter.writeSourceRow()` forgot to advance the internal PNG row counter after emitting the `pixelsPerBlock` rows. Because `close()` only finalized the zlib stream and wrote `IEND` when the internal count matched the PNG height, large captures could end up with an incomplete PNG stream. Some Android/image decoders recovered enough of the file to display most of the map while leaving a small trailing/missing section.

Build 195 keeps the Build 194 architecture and performance, but:

- increments the PNG row counter by exactly `pixelsPerBlock` for every source row;
- checks for PNG row overflow so an incorrect row count fails immediately instead of silently producing a partial image;
- leaves the one-pass preview + full-resolution streaming path unchanged;
- keeps instant Save PNG behavior because the final PNG is still prepared during the render pass.

No intentional change was made to map rendering, bounds, chunk ordering, or output resolution.
