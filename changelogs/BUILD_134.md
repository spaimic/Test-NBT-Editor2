# Build 134 — Chunk Block Editor improvements

Build 134 improves the working Chunk Block Editor from Build 133.

## Changes
- The 16×16 (256 position) chunk grid is now horizontally scrollable so all columns remain reachable on narrow screens.
- The selected X/Z column preserves its vertical scroll position after editing a block, undoing, redoing, hiding/showing air, and other refreshes where possible.
- Added block search across the current chunk.
- Search results show X/Y/Z and block name; tapping a result opens its column.
- Added Replace / Replace All for a block identifier across the current chunk.
- Added a Hide Air toggle for vertical column views; toggling it back restores air blocks.
- Added Undo and Redo using grouped edit actions, including Replace All as one undoable action.
- Existing Build 133 LevelDB bridge reuse and chunk-writing behavior is retained.

## Notes
- Replace All operates on the current loaded chunk only.
- Search is case-insensitive substring matching.
- Undo/Redo is in-memory until Save; reopening without saving discards edits.
