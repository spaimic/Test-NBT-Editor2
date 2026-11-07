# Build 133

## Fixes
- Fixed the Build 132 Kotlin compile error caused by assigning `0x80000000` to `EditText.inputType`, which is outside the signed Kotlin `Int` literal range.
- Removed manual Chunk X/Z entry from Chunk Block Editor.
- Chunk Block Editor now opens directly from a selected chunk in the chunk action menu.
- The selected chunk's X/Z coordinates are passed directly to the editor.
- The selected dimension is passed through and used for reading/saving subchunks.
- Removed the old hamburger-menu entry that opened the editor without a selected chunk.

## Usage
Map Viewer -> tap a chunk -> Chunk Block Editor.
