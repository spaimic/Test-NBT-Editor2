# Build 147

- Reversed horizontal 3D drag rotation: dragging right rotates the model left.
- Fixed Open Selected Area database lifecycle: the viewer now releases its own LevelDB handle before launching Chunk Block Editor, preventing OverlappingFileLockException and follow-on database-closed errors.
- Preserves Build 146 touch handling, selection, Build 143 geometry fix, and Build 145 loading optimization.
