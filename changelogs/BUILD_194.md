# Build 194 — Whole-Map Renderer Performance

Build 194 is based on the current Build 193 code and focuses on making **Take Entire Map Picture** substantially faster, especially at 2 px/block.

## Main optimization

- The previous safe-preview implementation rendered the world once to make the <=1024px preview and then rendered the entire world a second time when Save PNG was pressed.
- Build 194 now renders the world **once** and simultaneously:
  - creates the small on-screen preview, and
  - streams the requested full-resolution PNG into a temporary file.
- Save PNG now copies the already-prepared PNG into Android MediaStore instead of scanning and rendering the world again.
- This restores the important single-render behavior while keeping the large-world preview memory-safe.

## PNG performance

- PNG Deflater compression is changed from the default compression level to `BEST_SPEED` to reduce CPU time.
- Full-resolution output remains lossless PNG. The trade-off is potentially larger files in exchange for faster export.
- Rows are expanded directly from the 1-pixel/block rendered source row to avoid allocating giant intermediate pixel arrays.
- Repeated pixels-per-block rows use PNG Up filtering with a reusable zero row.

## Safety

- No full-resolution Android Bitmap is created.
- The preview remains capped at 1024 × 1024.
- The existing one-billion-pixel export safety limit remains active.
- Temporary full-resolution PNGs are deleted on cancellation or save completion.
