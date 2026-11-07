# Build 151

## Fixed
- Selection Map Picture preview is now defensive about output row bounds and
  cannot call Bitmap.setPixels with a y position/height outside the preview bitmap.
- The preview row width is clamped to the actual bitmap width.

## Paste Chunk menu
- Replaced the plain text-only chooser with a scrollable visual chooser.
- Each copied chunk shows its saved Chunk Clipboard PNG preview on the left.
- Clicking the row still opens the existing Paste Chunk confirmation.
- Chunks without a preview show a fallback icon.

## Entity icons
- Replaced the existing entity icon PNG set with the supplied 133-icon ZIP.
- EntityIcons was regenerated to map current icon names plus compatibility aliases.

## Preserved
- Build 149 Selection Tool, Map Picture export, and Delete Area.
- Build 148 direct BlockChunk opening.
- 3D Chunk Viewer fixes and Terrain Editor naming.
