# Build 130 — Fix inventory slot writes and Bedrock map colors

## Fixes

### Inventory
- Reuses the existing empty compound in the player's `Inventory` list when the list already contains a fixed 36-slot representation.
- Does not append a 37th inventory compound for a 36-entry inventory.
- Uses `Slot` to identify the physical slot and a non-empty `Name` to identify an occupied slot.
- Preserves all existing inventory entries, including hotbar items.
- Only appends when the inventory is genuinely sparse and the requested slot is absent.

### Image-generated maps
- Corrected the image-to-map palette encoding for Bedrock.
- Bedrock map color IDs are written as the direct Bedrock palette IDs used by Nibnya's palette (0–61), rather than Java-style `baseColor * 4 + shade` IDs.
- This prevents scrambled colors caused by treating Bedrock map color IDs as Java map-color IDs.
- Normalizes generated map width/height to 128×128.

## Preserved
- Build 123 gray-chunk fix.
- Build 124 block-color improvements.
- Build 125 large JSON importer.
- Build 127/129 image-map generation and safe dirty-entry saving.
