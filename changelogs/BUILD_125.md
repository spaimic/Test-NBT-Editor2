# BUILD 125

Build 125 adds **Large JSON Import** for very large NBT JSON exports such as huge `~local_player` files containing hundreds of thousands or millions of written-book pages.

## Changes
- Replaced the import path's whole-file `readText()` + `JSONObject` parse with Android's streaming `JsonReader`.
- JSON is read directly from a buffered `Reader`, avoiding a second giant in-memory copy of the entire file.
- Large JSON parsing runs on a background thread so the UI thread is not blocked while reading/parsing.
- Added streaming support for Compound, List, String, numeric, and array NBT tags.
- Preserves the existing typed NBT JSON format and validates list element types.
- Very large imports skip the normal full-tree undo snapshot when the existing NBT tree exceeds 100,000 tags, preventing an import from temporarily duplicating hundreds of MB of memory.
- The previous Build 124 block-color improvements and Build 123 gray-chunk fix remain unchanged.

## Important limitation
Streaming import removes the enormous temporary JSON `String`/`JSONObject` tree, but the resulting NBT still has to exist in memory because NBT Tool edits it in memory. A one-million-page book can therefore still require substantial RAM.
