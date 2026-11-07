# Build 104

## Map Viewer visual upgrade

Build 104 focuses entirely on making the map viewer look more like the detailed Blocktopograph-style map shown in the reference screenshots.

- Replaced the single flat RGB appearance of each rendered terrain pixel with a deterministic five-shade material palette.
- Added material identity to rendered map columns so color variation follows the actual block that produced the map pixel.
- Variation is deterministic from absolute world X/Z coordinates, so patterns continue smoothly across chunk boundaries instead of restarting in every chunk.
- Applies to Overworld, End, and the layered Nether renderer.
- Keeps special Nether/POI colors stable instead of randomly changing marker colors.
- Preserves existing biome tinting, elevation shading, lazy chunk loading, entity markers, tile-entity markers, and all existing map behavior.
- No new map UI controls were added; this is a visual rendering improvement only.

The result is intended to make forests, cherry leaves, grass, stone, and other terrain materials contain subtle neighboring shades instead of every block being exactly the same RGB value.
