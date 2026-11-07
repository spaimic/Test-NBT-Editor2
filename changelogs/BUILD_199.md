# Build 199 — UI Cleanup, White Theme, Map Options & Editor Actions

Build 199 continues the Build 198 drawer redesign with a cleaner white UI and the updated icon set.

## Drawer

The Map Viewer drawer is white with black text and is reorganized into:

- World Editor
  - Player Editor
  - Multiplayer Editor
  - World Editor
  - MCStructure Editor
  - Ticking Areas Editor
  - Village Editor
- Map Options
  - Hide Grid / Show Grid
  - Hide Entities on the Map / Show Entities on the Map
  - Hide Tile Entities on the Map / Show Tile Entities on the Map
- Clipboard
  - NBT Clipboard
  - Chunk Clipboard
- Other NBTs
  - Overworld NBT Data
  - Nether NBT Data
  - The End NBT Data
- Advanced
  - Open LevelDB Key by Name
  - Browse Extra LevelDB Keys

## Updated icons

Build 199 imports the updated `Test2_Updated.zip` icons:

- `camera.png` — whole-map picture FAB/action
- `other_nbt.png` — Other NBTs and extra LevelDB browser
- `search_ancient_debris.png` — Ancient Debris finder
- `search_diamond.png` — Diamond finder
- `tickingarea.png` — Ticking Areas Editor
- `world_setting.png` — World Editor / level.dat
- `toggle.png` — entity/tile-entity visibility options
- `grid.png` — Show/Hide Grid

Existing supplied icons remain in use for player, multiplayer, MCStructure, clipboard, search and map/dimension actions.

## Map Options

`MapView.showGrid` controls both the normal chunk borders at close zoom and the 64×64-block overview grid at wide zoom. The drawer labels update between Hide and Show while the map redraws immediately.

## Whole-map picture control

The Take Entire Map Picture action was removed from the drawer. A small circular camera button is now fixed at the bottom-right of the Map Viewer. Tapping it reveals a compact white `Take Entire Map Picture` action; tapping that starts the existing Build 197 whole-map exporter.

## Gamemode display

Gamemode IDs 0–4 now display their names and IDs. ID 5 is treated as the Bedrock default-gamemode marker and reads `Default Gamemode: <name> (<id>)`, using `level.dat`'s `GameType` value.

## Editor action icons

Save is now a persistent toolbar icon using `save.png` on the NBT/world editor screens. The LevelDB entry editor also has a persistent `search.png` toolbar icon for Global NBT Search instead of hiding those actions in the overflow menu.

## Theme

The application theme is switched from `Theme.AppCompat.DayNight` to `Theme.AppCompat.Light`, giving the app white surfaces/backgrounds and dark text throughout the normal editor UI. The Map Viewer controls and drawer explicitly use white backgrounds with black text as well.

## Performance

No changes were made to the established Build 197 whole-map rendering/export pipeline.
