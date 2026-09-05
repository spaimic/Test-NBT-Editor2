# Build 200 — Milestone / UI & Entrance Polish

Build 200 introduces the app's one-time entrance animation and several UI polish improvements while leaving the established whole-map exporter untouched.

## One-time Build 200 intro
- White-screen entrance animation.
- Four `S` characters start from the four corners, continuously rotate, and converge on the center together.
- The supplied Build 200 impact GIF is played for exactly **1.20 seconds (00:00:01.20)**. The source GIF is 57×48 pixels with three 400 ms frames, so its native duration is exactly 1200 ms.
- After the impact, one `S` flies upward, hits the top, falls back toward the center, and bounces.
- `Spaimic's World Editor` types onto the screen while the S is bouncing.
- The intro fades away smoothly by becoming transparent before the normal app screen is revealed.
- The intro is stored as seen in SharedPreferences and is shown only once per app data installation. Clearing app data resets it.

## Map UI
- Fixed the supplied `map.png` dimension icon by explicitly removing inherited image tint and forcing its visible pixels to black.
- The dimension switch remains on the main map screen.
- The bottom-right control is now a smaller map-options FAB (`⋮`). It reveals the existing **Take Entire Map Picture** action with the supplied camera icon.
- The whole-map export implementation itself was not changed.

## NBT tree UI
- Compound/List expansion now has a short fade + slide-in animation for the children revealed by opening a container.
- Nested NBT rows now show a subtle transparent `└─` branch marker immediately before the NBT type icon, making ownership by a parent Compound/List clearer.
- Root-level tags do not receive the branch marker.

## Other behavior
- Build 199's white UI, drawer organization, map options, icons, and gamemode handling remain intact.
- Gamemode ID 5 continues to display the actual default world gamemode, such as `Default Gamemode: Survival (0)`.
