# Build 192 — Ore Finder Improvements

Build 192 is based on Build 191 and improves the diamond / ancient-debris finder.

## Changes

- **Custom Coordinates** is now available inside both:
  - `💎 Find Diamonds Near Player`
  - `🏺 Find Ancient Debris Near Player`
- Each ore finder now offers:
  - Find Near Current Player
  - Find at Custom Coordinates
- Custom coordinate searches use the exact same filled **9 × 9 = 81 chunk square** as the player search.
- Diamond custom coordinates are interpreted as Overworld coordinates.
- Ancient-debris custom coordinates are interpreted as Nether coordinates.
- Existing Overworld ↔ Nether conversion for the current-player search is preserved.
- Removed **Copy All Coordinates**.
- Added **Copy Group Coordinates**.
- Group numbers can be entered separated by commas, with a maximum of 10 groups per copy operation.
- Duplicate group numbers are ignored.
- Invalid or out-of-range group numbers are rejected.
- Results can now be listed in two ways:
  - **Nearest** — groups ordered by distance from the search center/player position.
  - **Most Ores** — groups ordered by number of diamonds/ancient debris, largest group first.
- The displayed group numbers follow the currently selected ordering, so copied group numbers correspond to what is currently displayed.
- Existing 26-neighbor clustering remains unchanged: face, edge, and corner touching ore blocks are grouped together.
- Diamond detection continues to include both normal and deepslate diamond ore.
- Ancient-debris detection remains Nether-only.

## Safety / World Changes

The ore finder is read-only. Build 192 does not modify world terrain while searching or displaying results.

## Large-world preview crash fix

- Fixed an Android out-of-memory crash when a very large world is selected at a high export scale such as **4 px/block**.
- The export scale remains the requested final PNG scale, but the on-screen preview is now rendered as a small sampled bitmap capped at **1024 × 1024** instead of allocating the full-resolution image.
- Example: a `46,656 × 21,312` pixel final image contains `994,332,672` pixels and would require roughly **3.98 GiB** just for an ARGB_8888 Bitmap, which is not safe on Android.
- The preview now renders one block per source pixel and samples it down for display, keeping memory bounded.
- The **Save PNG** action now performs the exact full-resolution streaming PNG export from the world data rather than trying to expand the small display preview. This preserves full output resolution and avoids another giant Bitmap allocation.
- The final PNG continues to use the existing streaming renderer and total-pixel safety checks.
