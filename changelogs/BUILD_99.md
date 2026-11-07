# Build 99

Final pre-milestone polish before Build 100.

## Changes
- Added **Open LevelDB Key by Name** to the world hamburger menu.
  - Existing key with valid NBT opens directly in the NBT editor.
  - Missing key shows: `This LevelDB Key doesn't exist.`
  - Existing key without valid NBT shows: `No valid NBT data found`
- Improved error handling for direct LevelDB-key access.
- Fixed the world map title staying on `Loading…` after the database/world had opened.
- Added local-world renaming from **My Worlds**.
  - Renaming changes only NBT Editor's local display name.
  - `level.dat` is never modified by renaming.
- My Worlds now shows the real `level.dat` `LevelName` underneath the local display name in a lighter text style.
- Kept world icons and existing LastPlayed information intact.
