# NbtTool — minimal Bedrock level.dat editor

A working starting point for a Minecraft Bedrock NBT editor, built around Shizuku
for sandboxed file access. This is intentionally minimal — it gets the three hard
parts (NBT codec, Shizuku file bridge, tree UI) working end-to-end for `level.dat`,
so you have a solid base to extend.

## What's here

- `nbt/NbtTag.kt` — in-memory model of every Bedrock NBT tag type
- `nbt/NbtIO.kt` — the little-endian NBT codec: `readLevelDat`/`writeLevelDat`
  (the 8-byte-header `level.dat` format), `readRootCompoundNoHeader`/
  `writeRootCompoundNoHeader` (header-less root compounds, the shape LevelDB
  values are stored in), and `serializeTag`/`deserializeTag` (a single tag,
  no name — used by the NBT clipboard)
- `shizuku/ShizukuShell.kt` — runs shell commands through Shizuku's elevated
  process: list/copy single files, and recursive `pullDir`/`pushDir` for
  whole folders (needed for LevelDB's multi-file `db/` folder). `pushDir`
  always backs up the original folder to `<path>.bak` first.
- `shizuku/LevelDbBridge.kt` — pulls a world's `db/` folder locally and opens
  it with a pure-Java LevelDB implementation; lists/reads/writes/deletes keys
  (`~local_player`, `player_server_<id>`, `map_<id>`, `village_<uuid>_*`, etc.)
- `shizuku/LevelDbSession.kt` — holds the open DB + parsed entries in memory
  so the key list and the tree editor share the same mutable objects
- `clipboard/NbtClipboard.kt` — named NBT snippets persisted to app-private
  storage (not the device's system clipboard), so a copied tag survives app
  restarts and can be pasted into a completely different world later
- `ui/WorldListActivity.kt` — requests Shizuku permission, scans for world
  folders containing a `level.dat`, and shows each world's actual **LevelName**
  (pulled from its own level.dat) rather than the random on-disk folder id
- `ui/NbtTreeView.kt` — the shared tree adapter: tap a compound to expand,
  tap a primitive to edit its value, **long-press any row** for a menu with
  Delete / Add child / Copy to NBT clipboard / Paste from NBT clipboard
- `ui/EditorActivity.kt` — the level.dat tree editor, plus a button into the
  LevelDB browser below
- `ui/PlayerDbActivity.kt` — lists LevelDB keys for the world; "Save all
  changes to world" re-serializes every entry you opened and pushes the
  whole `db/` folder back (after backing up the original)
- `ui/DbEntryEditorActivity.kt` — the tree editor for a single LevelDB entry
  (a player's data, a map, a village record)

## Requirements to actually run this

1. **Shizuku must be installed and running** on the test device (either via
   wireless debugging pairing on Android 11+, or root, or a computer +
   `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`).
2. Update the `dev.rikka.shizuku:api` / `:provider` versions in
   `app/build.gradle` to whatever's current — pin to a real release tag from
   https://github.com/RikkaApps/Shizuku-API before you build.
3. `minSdk 28 / targetSdk 33` matches the reference app we looked at; Shizuku
   works across a wider range but this keeps behavior predictable.

## Important LevelDB caveats

- **Close the DB before copying it anywhere.** LevelDB keeps a `LOCK` file
  and in-memory state; copying `db/` back into the sandbox while it's still
  open (or crashing mid-write) is exactly the kind of thing that can corrupt
  it or silently drop keys — which lines up with the `~local_player`
  disappearing you saw in the other app. `PlayerDbActivity.saveAll()` always
  calls `bridge.close()` before `pushBack()` for this reason; don't reorder
  that if you extend it.
- **The whole folder moves together.** You can't patch a single `.ldb` file
  in place — LevelDB's on-disk layout (MANIFEST, CURRENT, multiple `.ldb`
  files) has to be treated as one unit, hence `pullDir`/`pushDir` rather than
  per-file copies.
- Every `pushDir` backs up the previous folder to `db.bak` alongside it
  first — if a save goes wrong, that backup is your recovery path.

## What this still doesn't cover (natural next steps)

- **List item editing UI** — you can add/delete/paste list items via the
  long-press menu, but there's no dedicated "reorder" or bulk-edit view yet.
- **Village internal structure** — village keys are shown and editable as
  raw NBT like everything else, but nothing decodes their multi-key
  relationship (e.g. linking a village's poi/dwellers/players sub-keys) —
  you'd browse each one independently right now.
- **Corruption/malformed-tag handling** — parsing currently throws on
  anything unexpected; a real app should degrade gracefully rather than
  crash on one bad key.

## Build

Standard Gradle Android project — open the root folder in Android Studio, or:

```
./gradlew assembleDebug
```

## Building an APK from your phone (no PC/Android Studio)

This repo includes `.github/workflows/build.yml`, which builds a debug APK
on GitHub's servers whenever you push to `main`. To use it entirely from
your phone:

1. Create a new (public or private) repo on github.com — the mobile site
   works fine for this, you don't need the app.
2. On the repo's main page, tap **Add file → Upload files**, then upload
   everything from this unzipped folder (including the hidden `.github`
   folder — most upload flows preserve folder structure if you drag/select
   the whole tree; if a browser upload flattens it, you can instead use
   the GitHub mobile app's "create file" feature to recreate
   `.github/workflows/build.yml` by pasting its contents in directly).
3. Once the upload commits to `main`, go to the repo's **Actions** tab —
   a "Build debug APK" run should already be in progress (or trigger it
   manually there via "Run workflow").
4. When it finishes (green checkmark), open that run and scroll to
   **Artifacts** — download `NbtTool-debug-apk`. It downloads as a `.zip`
   containing the actual `.apk`; unzip it (MT Manager or any file manager
   with zip support handles this) and install.
5. You'll need "install unknown apps" permission enabled for whichever
   app you used to open the APK, since it's unsigned/debug-signed and not
   from the Play Store.

Each time you push a change to `main`, a fresh APK gets built automatically
— no local Android SDK required at any point.
