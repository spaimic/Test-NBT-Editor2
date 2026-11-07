# Build 103

- Added private world backup system.
- Added Backup World action to the long-press world menu.
- Added Manage Backups with restore and delete actions.
- Backups preserve the complete local world, including NBT/LevelDB files and local display-name metadata.
- Restore validates `level.dat` and extracts to a temporary directory before replacing the current world.
- Removed the obsolete Flat World Generator action from the long-press world menu.
- Kept the improved Custom Flat World Generator button on My Worlds.
