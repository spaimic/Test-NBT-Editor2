# Build 187 — Player-centered ore finder

Build 187 moves the diamond and ancient-debris finders from the per-chunk menu into the Map Viewer's hamburger menu and searches a full 9×9 chunk square centered on the player's chunk.

## Changes
- **Hamburger menu tools:** added `💎 Find Diamonds Near Player` and `🏺 Find Ancient Debris Near Player`.
- **Player-centered search:** the search is centered on the current local player's chunk and covers offsets -4..+4 on both X and Z — 81 chunks total, including the entire filled square rather than only the arms of the cross.
- **Dimension-aware coordinates:** diamond searches use the player's Overworld position; if the player is in the Nether, the corresponding Overworld X/Z is used. Ancient-debris searches use Nether coordinates; when the player is in the Overworld, X/Z are converted by the normal 8:1 Nether relationship.
- **Parallel chunk scanning:** up to six workers read and scan the 81 target chunks concurrently.
- **Compact block decoding:** ore searches now use the packed subchunk representation instead of allocating a 16×16×16 String cube for every subchunk.
- **Overlay correctness:** later Bedrock block-storage layers override earlier layers, so an ore hidden by a non-air overlay is not falsely reported.
- **Ore grouping:** touching ore blocks are merged into groups using 26-neighbor connectivity, meaning face, edge, and corner contact all count as "near". Chains of touching blocks merge into one group.
- **Distance sorting:** groups are ordered by their closest ore block to the player's X/Y/Z coordinate, and coordinates within each group are also ordered by distance to the player.
- **Results UI:** displays player position, center chunk, number of searched chunks, number of ore blocks, group counts, each group's distance, and every coordinate. All coordinates can be copied at once.
- **Build 186 map-export performance is unchanged:** whole-map preview generation/saving code was not modified.

## Notes
The player position is read fresh from the world's `~local_player` record when the hamburger action is tapped. Searches preserve the existing Nether↔Overworld coordinate relationship. Actual search time depends on world size, loaded subchunks, and device storage/CPU.
