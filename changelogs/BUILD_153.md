# Build 153 — Fix Map Picture Preview Rendering

## Fixed
- Reworked the Selection Tool's map-picture preview to use the exact same PNG rendering pipeline as the real **Save PNG** operation.
- The preview no longer writes map rows directly into an independently constructed Bitmap, which was producing a visually corrupted preview even when the saved PNG was correct.
- Preview resolution remains capped to keep large selections safe in memory.
- The preview now decodes the rendered PNG, so what is shown in the dialog is the same image produced by the exporter.

## Preserved
- Build 152 compilation fixes.
- Build 151 entity icons, paste-menu work, and map-picture fixes.
- Build 149 Selection Tool and area operations.
- Build 148 3D viewer / Terrain Editor functionality.
