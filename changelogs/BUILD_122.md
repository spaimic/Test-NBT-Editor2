# Build 122

## Map Viewer surface detection
- Map chunk columns now scan the complete Overworld build-height range from Y=320 down to Y=-64.
- Existing subchunks are processed strictly from highest subchunk to lowest.
- Missing subchunks inside the range are treated as air.
- For each X/Z column, scanning stops immediately at the first block that is neither air nor map-transparent.
- The Build 114 map renderer itself is unchanged.

## Multiplayer Editor
- The Multiplayer Players picker now selects only `player_server_*` LevelDB keys.
- `player_*` keys are no longer included in the multiplayer list.
