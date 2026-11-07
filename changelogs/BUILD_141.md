# Build 141

- Optimized Chunk 3D Viewer rendering by drawing only exposed/surface blocks instead of every non-air block.
- Reduced rotation sensitivity and prevented selection when the user is dragging, so rotating/panning no longer accidentally selects a BlockChunk.
- Selection updates now refresh the existing viewer instead of rebuilding the whole Activity UI.
- Added colors for newer `cinnabar`, `cinnabar_block`, `sulfur`, and `sulfur_block` IDs, plus generic cinnabar/sulfur fallback coverage. These colors are shared by the 2D map renderer and 3D Chunk Viewer through BlockColors.
