# Build 108 — Fix disappearing background-rendered map chunks

Build 108 fixes a Build 107 map viewer regression where chunks could appear briefly and then disappear.

## Fix

Build 107 accidentally shared one `loading` set between two unrelated systems:

- LevelDB viewport chunk loading
- 512×512 background bitmap rendering

A chunk already marked as LevelDB-loading could therefore be rejected by the bitmap renderer when its data arrived, leaving it without a rendered bitmap.

Build 108 separates these states:

- `MapViewerActivity.loadingChunks` — LevelDB/data loading only
- `MapView.rendering` — background bitmap rendering only

The map view no longer receives or clears the activity's database-loading state.

## Result

The 32×32-pixels-per-block renderer from Build 106/107 is retained, while loaded chunks are reliably scheduled for background rendering and remain visible once rendered.
