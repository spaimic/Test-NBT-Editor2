# BUILD 123

## Gray-chunk fixes

- `SubChunkParser` now merges every storage layer in a subchunk instead of dropping layers after layer 0.
- If a later storage layer is malformed, the parser salvages the base layer instead of throwing away the whole subchunk.
- Chunk surface scanning now keeps a fallback highest non-air column so a column does not remain gray just because the first solid block was missed.
- The multiplayer editor continues to use `player_server_*` keys.

