# NBT Editor (NbtTool)

A Minecraft Bedrock Edition world and NBT editor for Android. It opens worlds
through [Shizuku](https://shizuku.rikka.app/), so it can read and write the
sandboxed `Android/data` world folders without root.

Package: `com.spaimic.nbttool` · Current build: **248**

> ⚠️ **Back up your worlds.** This app edits LevelDB data directly. Use the
> built-in **Backup Worlds** feature before any edit, and avoid heavy bulk
> operations on a world you can't afford to lose.

## Features

Items marked 🧪 need **Experiment mode**: tap the **My Worlds** title on the
home screen several times to enable it.

**World & data editors**
- 👤 Player Editor · 👥 Multiplayer Editor
- ⚙️ World Settings Editor
- ⏰ Ticking Areas Editor · 🏘️ Village Data Editor
- 🏗️ MCStructure Editor · 🧪 MCStructure Placement (Beta)
- 🔨 View and edit Entities and Tile-Entities (per chunk)
- 🏞️ Terrain Editor (chunk-wide Search and Replace All, Y -64 to 320)
- 🗾 Custom Flat World Generator

**NBT tools**
- 🌟 Open LevelDB key by name · 🔎 Browse extra LevelDB keys
- 🔍 Global NBT Search · 🔄 Undo / Redo
- ⛏️ Copy / 🪏 Paste NBT tags · 📋 NBT Clipboard
- 📁 Export / 🗂️ Import NBT data as JSON

**Chunks & selection**
- 📊 Chunk Information panel
- 🗒️ 🧪 Copy Chunk · 📝 🧪 Paste Chunk · 📋 Chunk Clipboard · 🗑️ 🧪 Delete Chunk
- 🪟 Selection tool (up to 2048×2048)
  - 📷 Take a picture of the selected area
  - 🗑️ 🧪 Delete the selected area

**Viewers**
- 🗺️ 2D Map Viewer across all dimensions, with per-column layer viewing
  (↑ Layer / ↓ Layer / Surface), entity points and tile-entity points
  - 🎚️ View at a specific Y level (the highest block at or below Y)
  - 🧭 Go to X / Z
  - 📍 Locators: saved per-world bookmarks with 8 selectable colors
  - 🌿 Biome overlay: color the map by biome; the biome under the map centre is shown at the bottom, and tapping the map shows the biome there
  - 🟩 Slime chunk view (Overworld): slime chunks green, everything else black
  - 🧱 Block editor on the map: zoom to 34× or more for a per-block grid; tap a block to see/edit it (Y ±1 with hold-to-repeat, "Hide air blocks")
  - 🔍 Zoom from 1× to 38×
- 🌍 3D Chunk Viewer across all dimensions (face culling + greedy meshing)
- 📷 Take a picture of the entire map

**Finders**
- 💎 Advanced Diamond Finder (4-chunk radius)
- 🏺 Advanced Ancient Debris Finder (4-chunk radius)

**Worlds**
- 🎒 Backup Worlds · 🛠️ Restore Worlds from backups

## Requirements

- Android 9+ (minSdk 28)
- Shizuku installed and running (wireless debugging, root, or ADB start script)
- Minecraft Bedrock Edition installed (worlds under `Android/data`)

## Building

Open in Android Studio, or run `./gradlew assembleDebug`.

To build without a PC, push to `main`: `.github/workflows/build.yml` builds a
debug APK on GitHub Actions. Download `NbtTool-debug-apk` from the run's
**Artifacts**, unzip it, and install the APK.

## LevelDB notes

- The database is closed before it is copied back, and the whole `db/` folder
  moves as one unit. Every push backs up the previous folder to `db.bak`.
- Large bulk operations (chunk copy/move, MCStructure placement) rely on a
  vendored LevelDB with table-size and compaction fixes (Build 235/236).
  Placed actors have `IsAutonomous` forced to `0` so they load in-game.

## Changelogs

Each build ships a `BUILD_N.md` describing what changed.

## Credits

The 2D map viewer is modeled on the behavior of
[Blocktopograph](https://github.com/rukiroki/blocktopograph) (AGPL-3.0). The
code in this repository was written independently.
