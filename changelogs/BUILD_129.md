# Build 129 — Safe Image Map Generation / Targeted Saves

Build 129 is based directly on the uploaded Build 128 source.

## Fixes

- **Targeted LevelDB saves:** the NBT editor now tracks explicitly dirty entries and saves only the changed records from that editor session. This prevents unrelated cached entries from being rewritten when generating an image map.
- **Image-map records are isolated:** image generation marks only the new `map_<id>` record and `~local_player` as dirty.
- **Atomic LevelDB write batch:** changed records are serialized completely before the database is touched, then committed using one LevelDB write batch.
- **Inventory detection from Build 128 retained:** `Slot` identifies the physical slot and a missing/empty `Name` means empty; non-empty `Name` means occupied, limited to slots 0–35.
- **Filled-map generation retained:** generated `minecraft:filled_map` items point at the generated map through `map_uuid`.
- **Build 123/124/125 functionality retained:** gray-chunk fix, block-color improvements, and large JSON import remain unchanged.

## Safety note

Test image-map generation on a copy/backup of a world first. This build reduces the scope of writes from the image-map editor path and uses a single LevelDB batch, but it has not been Android/Gradle compiled in this environment because the uploaded source archive does not include a Gradle wrapper and no Android SDK build environment is available here.
