# BUILD 127

Build 127 completes the **Import Image to Map** feature.

## What changed

Build 126 only replaced the color payload of an existing `map_<id>` record. Build 127 now creates a complete playable map:

- Finds an unused numeric `map_<id>` LevelDB key.
- Uses the currently opened map as a metadata template.
- Generates a 128×128 Bedrock map color payload from the selected image.
- Creates a `minecraft:filled_map` inventory item.
- Links the item to the generated map with `tag.map_uuid`.
- Sets `map_name_index`, `map_display_players`, `map_is_init`, `map_is_scaling`, and `map_scale`.
- Finds the first free player inventory slot from 0–35.
- Loads `~local_player` automatically when needed.
- Saves both the new `map_<id>` record and modified `~local_player` through the normal Save flow.
- The original map record is not overwritten.

## User flow

1. Open an existing `map_*` entry.
2. Choose **Import Image to Map**.
3. Select an image.
4. NBT Tool generates a new map record and adds a filled map to the player's inventory.
5. Tap **Save**.
6. Open the world in Minecraft and use the generated filled map.

## Notes

This is an independent implementation based on the Bedrock map/filled-map data relationship. It does not copy Nibnya's source code.

The giant multi-map puzzle/mosaic mode is not included yet.
