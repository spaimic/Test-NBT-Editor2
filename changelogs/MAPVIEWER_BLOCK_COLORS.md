# Map Viewer — Build 75 block colors

Build 75 updates the map renderer to prefer Bedrock Edition base map colors rather than the older Java-style approximation table.

The implementation is based on the Bedrock map-color reference data (MCBE 1.21.3) and Microsoft Bedrock documentation for `minecraft:map_color`.

Important behavior:
- Known Bedrock colors are preferred for terrain, foliage, wood, water, stone, cherry blocks, etc.
- `minecraft:cherry_leaves` is rendered pink (`#f27fa5`) instead of being treated as generic green foliage.
- Blocks whose Bedrock map color is effectively transparent (glass panes, rails, torches, buttons, air, etc.) are skipped while searching downward for the visible map surface.
- The lazy viewport chunk-loading architecture remains unchanged.

Biome-dependent tinting is not yet synthesized from biome temperature/rain data; the current implementation uses the Bedrock base map colors. Bedrock supports tint methods such as `default_foliage`, `birch_foliage`, `evergreen_foliage`, `dry_foliage`, `grass`, and `water`.
