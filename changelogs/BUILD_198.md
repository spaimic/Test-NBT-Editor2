# Build 198 — World Tools Drawer UI

## UI redesign
- Removed the overcrowded hamburger menu from the main 2D Map Viewer.
- Added a real left-side swipe drawer using AndroidX DrawerLayout.
- Swiping from the left edge opens the drawer; swiping it back closes it.
- The drawer is vertically scrollable and groups tools into categories instead of flooding the screen with one large menu.

## Drawer header
- Shows the world's `world_icon.jpeg` when available.
- Shows the current world display name.
- Shows the Bedrock `RandomSeed` from `level.dat`.
- Shows the player's current gamemode from `~local_player` when available.

## Categories
- Map & Search
  - Find Diamonds Near Player
  - Find Ancient Debris Near Player
  - Take Entire Map Picture
  - Player Editor
- World Editors
  - World Settings Editor
  - Multiplayer Editor
  - MCStructure Editor
  - Ticking Areas Editor
  - Village Editor
- Map Markers
  - Toggle Entities on Map
  - Toggle Tile Entities on Map
- NBT & Clipboards
  - Overworld NBT Data
  - Nether NBT Data
  - The End NBT Data
  - NBT Tag Clipboard
  - Chunk Clipboard
- Advanced
  - Open LevelDB Key by Name
  - Browse Extra DB Keys

## Main map controls
- Dimension changing remains on the main screen as requested.
- The dimension button now uses the supplied `map.png` icon instead of the old vertical-dots button.
- No hamburger button remains on the map screen.

## Assets
Added the supplied icons from `Test2.zip` to `res/drawable/`:
- clipboard.png
- copy.png
- error.png
- map.png
- mcstructure.png
- multiplayer.png
- paste.png
- player.png
- save.png
- search.png

## Compatibility
- Added `androidx.drawerlayout:drawerlayout:1.2.0`.
- Existing map rendering/export code, including Build 197's Take Entire Map Picture behavior, was not changed.
- Experiment Mode remains one-way and continues to control destructive chunk/selection actions.
