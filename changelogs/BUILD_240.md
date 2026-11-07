# Build 240

## 2D Map Viewer
- **View at Y level:** new Y… button (also in the ⋮ menu). Enter a Y from -64 to 320 and every column shows its highest block at or below that Y. While active, ↑ / ↓ move the level by 1. Surface returns to normal.
- **Go to X / Z:** ⋮ menu → Go to X / Z. Can also save the typed position as a locator.
- **Locators:** tap the map → 📍 Add Locator Here (or save from Go to X / Z). Choose a name, X/Z and one of 8 colors. Locators are saved per world and per dimension, drawn on the map, and tapping one lets you Edit, Copy Coordinates or Delete it. ⋮ menu → Locators lists all of them (tap to jump, long-press to edit); jumping to one in another dimension switches dimension.

## Resources
- Added locator_1..locator_8 drawables.

## Validation
- Source-level brace check only. Not compiled; run the GitHub Actions build and test on device.
