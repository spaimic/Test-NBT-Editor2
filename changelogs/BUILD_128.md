# BUILD 128

## Inventory slot detection fix

Fixed the Image → Filled Map inventory-full bug from Build 127.

The importer now checks both:
- `Slot` (Byte) to identify which inventory slot an entry belongs to.
- `Name` (String) to determine whether that entry actually contains an item.

Rules:
- `Name` missing or empty → slot is considered empty.
- `Name` non-empty → slot is considered occupied.
- Only slots `0..35` participate in the free-slot search.
- Sparse inventory lists are handled correctly; the importer no longer assumes the list contains 36 entries.

The Build 127 image-to-filled-map generation remains unchanged.
