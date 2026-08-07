package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.ChunkScanner
import com.spaimic.nbttool.chunk.ChunkClipboard
import com.spaimic.nbttool.chunk.ChunkTransfer
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * The screen you land on after tapping a world: a top-down terrain-colored map
 * of every loaded chunk (unloaded ones flat gray), pannable by dragging, with
 * tap-to-see-coordinates. The hamburger menu (top-left) jumps straight to the
 * things you'd normally want to edit, without hunting through the full key list.
 */
class MapViewerActivity : AppCompatActivity() {

    private lateinit var worldDir: File
    private lateinit var mapView: MapView
    private lateinit var coordText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = LocalWorldStore.levelName(worldDir)

        val root = FrameLayout(this)
        mapView = MapView(this)
        root.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val menuButton = Button(this).apply {
            text = "≡"
            setOnClickListener { showMenu() }
        }
        coordText = TextView(this).apply {
            text = "Tap the map for coordinates"
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
        }
        val overlayColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(menuButton)
            addView(coordText)
        }
        root.addView(
            overlayColumn,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )
        setContentView(root)

        mapView.onTap = { x, y, z ->
            coordText.text = "X: $x\nY: $y\nZ: $z"
            showChunkDialog(x, z)
        }

        loadAndScan()
    }

    private var chunkIndex: com.spaimic.nbttool.chunk.ChunkIndex? = null
    private val requestedChunks = HashSet<Pair<Int, Int>>()
    private val loadedChunks = HashSet<Pair<Int, Int>>()
    private val loadingChunks = HashSet<Pair<Int, Int>>()
    private val mapExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val markerLock = Any()
    private val loadedEntityMarkers = ArrayList<MapView.MarkerDraw>()
    private val loadedBlockEntityMarkers = ArrayList<MapView.MarkerDraw>()
    private var backupCreated = false

    private fun loadAndScan() {
        Thread {
            try {
                LevelDbSession.reset()
                // IMPORTANT: do not copy the entire db/ just to open the map.
                // A backup is created lazily immediately before the first destructive edit.
                val bridge = LevelDbBridge.open(worldDir)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldDir = worldDir

                val keys = bridge.listKeys() // keys only; values are NOT read here
                for (k in keys) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                val index = com.spaimic.nbttool.chunk.ChunkIndex.fromKeys(keys)
                chunkIndex = index

                val playerPos = LevelDbSession.keyBytesByName["~local_player"]?.let { rawKey ->
                    try {
                        val bytes = bridge.read(rawKey)
                        val compound = bytes?.let { NbtIO.readRootCompoundNoHeader(it) }
                        val pos = compound?.entries?.get("Pos") as? NbtTag.ListTag
                        val x = (pos?.items?.getOrNull(0) as? NbtTag.Float)?.value
                        val z = (pos?.items?.getOrNull(2) as? NbtTag.Float)?.value
                        if (x != null && z != null) x.toInt() to z.toInt() else null
                    } catch (_: Exception) { null }
                }

                val center = playerPos ?: index.chunkPositions.takeIf { it.isNotEmpty() }?.let {
                    val xs = it.map { p -> p.first }.sorted()
                    val zs = it.map { p -> p.second }.sorted()
                    xs[xs.size / 2] * 16 + 8 to zs[zs.size / 2] * 16 + 8
                }

                runOnUiThread {
                    mapView.preferredCenterBlock = center
                    mapView.clearChunkData()
                    mapView.onVisibleChunksChanged = { positions -> requestVisibleChunks(positions) }
                    coordText.text = "Loading visible chunks…"
                    mapView.refreshVisibleChunks()
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { showErrorDialog(this, "Out of memory opening world", Exception("The map index could not be created.", e)) }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to open world map", e) }
            }
        }.start()
    }


    private fun requestVisibleChunks(positions: Set<Pair<Int, Int>>) {
        val bridge = LevelDbSession.bridge ?: return
        val index = chunkIndex ?: return
        val todo = ArrayList<Pair<Int, Int>>()

        synchronized(loadingChunks) {
            for (pos in positions) {
                if (!index.hasChunk(pos.first, pos.second)) continue
                if (loadedChunks.contains(pos) || loadingChunks.contains(pos)) continue
                loadingChunks.add(pos)
                todo.add(pos)
            }
        }
        if (todo.isEmpty()) return

        mapView.markLoading(todo.toSet())
        for (pos in todo) {
            mapExecutor.execute {
                try {
                    val unknown = HashSet<String>()
                    val reasons = HashMap<String, Int>()
                    val examples = HashMap<String, String>()
                    val loaded = com.spaimic.nbttool.chunk.ChunkScanner.loadChunk(
                        bridge, index, pos.first, pos.second, unknown, reasons, examples
                    )
                    runOnUiThread {
                        synchronized(loadingChunks) {
                            loadingChunks.remove(pos)
                            loadedChunks.add(pos)
                        }
                        if (loaded != null) {
                            mapView.setChunkData(mapOf(pos to loaded.grid))
                            synchronized(markerLock) {
                                loadedEntityMarkers.addAll(loaded.entityMarkers.map {
                                    MapView.MarkerDraw(it.worldX, it.worldZ,
                                        com.spaimic.nbttool.chunk.EntityIcons.iconFor(it.identifier))
                                })
                                loadedBlockEntityMarkers.addAll(loaded.blockEntityMarkers.map {
                                    MapView.MarkerDraw(it.worldX, it.worldZ,
                                        com.spaimic.nbttool.chunk.TileEntityIcons.iconFor(this, it.id))
                                })
                                mapView.entityMarkers = loadedEntityMarkers.toList()
                                mapView.blockEntityMarkers = loadedBlockEntityMarkers.toList()
                            }
                        }
                        mapView.markLoaded(setOf(pos))
                        coordText.text = "X: ${mapView.currentCameraBlockX()}  Z: ${mapView.currentCameraBlockZ()}"
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        synchronized(loadingChunks) { loadingChunks.remove(pos) }
                        mapView.markLoaded(setOf(pos))
                    }
                }
            }
        }
    }

    private fun reportScanStats(stats: com.spaimic.nbttool.chunk.ScanStats) {
        val unknownSample = stats.unknownBlockNames.take(15).joinToString("\n") { "  $it" }
        val unknownNote = if (stats.unknownBlockNames.isEmpty()) "" else
            "\n\nBlock names not yet in the color table (${stats.unknownBlockNames.size} distinct):\n$unknownSample" +
                if (stats.unknownBlockNames.size > 15) "\n  …and ${stats.unknownBlockNames.size - 15} more" else ""
        val reasonsNote = if (stats.failureReasonCounts.isEmpty()) "" else
            "\n\nWhy parsing failed:\n" + stats.failureReasonCounts.joinToString("\n") { (reason, count) -> "  ×$count: $reason" } +
                "\n\nExample bytes (top reasons):\n" +
                stats.failureReasonCounts.take(2).joinToString("\n\n") { (reason, _) ->
                    "$reason:\n${stats.failureReasonExamples[reason] ?: "(no sample)"}"
                }
        val message = "Loaded chunks: ${stats.totalLoadedChunks}" +
            (if (stats.outliersDropped > 0) " (${stats.outliersDropped} dropped as likely false-positive outliers)" else "") +
            "\nRendered with real terrain colors: ${stats.parsedOk}" +
            "\nNo subchunk data found: ${stats.noSubchunkFound}" +
            "\nSubchunk present but failed to parse: ${stats.subchunkParseFailed}" +
            reasonsNote +
            unknownNote
        AlertDialog.Builder(this)
            .setTitle("Map scan results")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showChunkDialog(worldX: Int, worldZ: Int) {
        val chunkX = Math.floorDiv(worldX, 16)
        val chunkZ = Math.floorDiv(worldZ, 16)
        val clipboard = ChunkClipboard.load(this)
        val pasteLabel = if (clipboard != null) {
            "Paste Chunk (${clipboard.chunkX}, ${clipboard.chunkZ}) Here"
        } else {
            "Paste Chunk"
        }
        val options = arrayOf(
            "Show Entities",
            "Show Block Entities",
            "Copy Chunk",
            pasteLabel,
            "Delete Chunk"
        )

        AlertDialog.Builder(this)
            .setTitle("Chunk ($chunkX, $chunkZ)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val i = Intent(this, EntityListActivity::class.java)
                        i.putExtra(EntityListActivity.EXTRA_CHUNK_X, chunkX)
                        i.putExtra(EntityListActivity.EXTRA_CHUNK_Z, chunkZ)
                        startActivity(i)
                    }
                    1 -> {
                        val i = Intent(this, BlockEntityListActivity::class.java)
                        i.putExtra(BlockEntityListActivity.EXTRA_CHUNK_X, chunkX)
                        i.putExtra(BlockEntityListActivity.EXTRA_CHUNK_Z, chunkZ)
                        startActivity(i)
                    }
                    2 -> copyChunk(chunkX, chunkZ)
                    3 -> if (clipboard != null) promptPasteChunk(chunkX, chunkZ, clipboard)
                         else Toast.makeText(this, "Chunk clipboard is empty.", Toast.LENGTH_SHORT).show()
                    4 -> promptDeleteChunk(chunkX, chunkZ)
                }
            }
            .show()
    }

    private fun ensureBackup() {
        if (backupCreated) return
        val bridge = LevelDbSession.bridge ?: return
        try {
            LevelDbBridge.backup(worldDir, LocalWorldStore.backupsDir(this))
            backupCreated = true
        } catch (e: Exception) {
            throw IllegalStateException("Could not create the safety backup before editing the world.", e)
        }
    }

    private fun copyChunk(chunkX: Int, chunkZ: Int) {
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                val copied = ChunkTransfer.copy(bridge, chunkX, chunkZ)
                ChunkClipboard.save(
                    this,
                    ChunkClipboard.Entry(copied.chunkX, copied.chunkZ, copied.records)
                )
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Copied chunk ($chunkX, $chunkZ) to the chunk clipboard.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to copy chunk", e) }
            }
        }.start()
    }

    private fun promptPasteChunk(
        targetX: Int,
        targetZ: Int,
        clipboard: ChunkClipboard.Entry
    ) {
        AlertDialog.Builder(this)
            .setTitle("Paste chunk here?")
            .setMessage(
                "This will overwrite chunk ($targetX, $targetZ) with the literal data " +
                    "copied from chunk (${clipboard.chunkX}, ${clipboard.chunkZ}). " +
                    "Blocks, block entities, and entities in the destination chunk will be replaced. " +
                    "This cannot be undone."
            )
            .setPositiveButton("Paste") { _, _ ->
                pasteChunk(targetX, targetZ, clipboard)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pasteChunk(
        targetX: Int,
        targetZ: Int,
        clipboard: ChunkClipboard.Entry
    ) {
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                ensureBackup()
                val written = ChunkTransfer.paste(bridge, clipboard, targetX, targetZ)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Pasted chunk to ($targetX, $targetZ) — $written records written.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                rescanMap()
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to paste chunk", e) }
            }
        }.start()
    }

    private fun promptDeleteChunk(chunkX: Int, chunkZ: Int) {
        val input = EditText(this).apply { hint = "Type DELETE to confirm" }
        AlertDialog.Builder(this)
            .setTitle("Delete chunk ($chunkX, $chunkZ)?")
            .setMessage(
                "This replaces every block in this chunk with air, from Y 320 down to " +
                    "Y -64, and removes every entity and block entity in it. This can't be " +
                    "undone (though a backup of the world's database was taken when you " +
                    "opened this map).\n\nType DELETE to confirm."
            )
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                if (input.text.toString() == "DELETE") {
                    deleteChunk(chunkX, chunkZ)
                } else {
                    Toast.makeText(this, "Didn't match \"DELETE\" — nothing was changed.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteChunk(chunkX: Int, chunkZ: Int) {
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                ensureBackup()
                com.spaimic.nbttool.chunk.ChunkDeleter.clearChunkToAir(bridge, chunkX, chunkZ)
                runOnUiThread { Toast.makeText(this, "Chunk cleared to air.", Toast.LENGTH_SHORT).show() }
                rescanMap()
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to delete chunk", e) }
            }
        }.start()
    }

    private fun showMenu() {
        val names = LevelDbSession.keyBytesByName.keys
        val hasLocalPlayer = "~local_player" in names
        val structureCount = names.count { it.startsWith("structuretemplate_") }
        val playerCount = names.count { it.startsWith("player_") }

        val options = listOf(
            "~local_player",
            "MCStructure editor" + if (structureCount > 0) " ($structureCount)" else "",
            "Multiplayer editor" + if (playerCount > 0) " ($playerCount)" else "",
            (if (mapView.showEntityIcons) "Hide" else "Show") + " Entity Icons",
            (if (mapView.showTileEntityIcons) "Hide" else "Show") + " Tile Entity Icons",
            "World settings (level.dat)",
            "Advanced: browse all DB keys"
        )

        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (hasLocalPlayer) openEntry("~local_player")
                         else Toast.makeText(this, "No ~local_player found in this world", Toast.LENGTH_SHORT).show()
                    1 -> showStructurePicker()
                    2 -> showMultiplayerPicker()
                    3 -> mapView.showEntityIcons = !mapView.showEntityIcons
                    4 -> mapView.showTileEntityIcons = !mapView.showTileEntityIcons
                    5 -> openLevelDatEditor()
                    6 -> openAllKeysBrowser()
                }
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) {
            saveChanges()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showStructurePicker() {
        val structures = LevelDbSession.keyBytesByName.keys.filter { it.startsWith("structuretemplate_") }.sorted()
        if (structures.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No MCStructure found").setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("MCStructures")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, structures)) { _, which ->
                openEntry(structures[which])
            }
            .show()
    }

    private fun showMultiplayerPicker() {
        val players = LevelDbSession.keyBytesByName.keys.filter { it.startsWith("player_") }.sorted()
        if (players.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No other player data found").setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pick a player")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, players)) { _, which ->
                openEntry(players[which])
            }
            .show()
    }

    private fun openEntry(displayName: String) {
        Thread {
            try {
                if (!LevelDbSession.loadedEntries.containsKey(displayName)) {
                    val rawKey = LevelDbSession.keyBytesByName[displayName]!!
                    val bytes = LevelDbSession.bridge!!.read(rawKey) ?: throw IllegalStateException("key vanished from DB")
                    LevelDbSession.loadedEntries[displayName] = NbtIO.readRootCompoundNoHeader(bytes)
                }
                runOnUiThread {
                    val i = Intent(this, DbEntryEditorActivity::class.java)
                    i.putExtra(DbEntryEditorActivity.EXTRA_DISPLAY_NAME, displayName)
                    startActivity(i)
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Couldn't parse \"$displayName\"", e) }
            }
        }.start()
    }

    private fun saveChanges() {
        if (LevelDbSession.bridge == null) return
        Thread {
            try {
                ensureBackup()
                LevelDbSession.saveAllLoadedEntries()
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
                rescanMap() // in case a pasted structure or edit changed visible terrain
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Save failed (your edits are still kept — safe to retry)", e) }
            }
        }.start()
    }

    private fun rescanMap() {
        synchronized(loadingChunks) {
            loadedChunks.clear()
            loadingChunks.clear()
        }
        synchronized(markerLock) {
            loadedEntityMarkers.clear()
            loadedBlockEntityMarkers.clear()
        }
        mapView.clearChunkData()
        mapView.entityMarkers = emptyList()
        mapView.blockEntityMarkers = emptyList()
        mapView.refreshVisibleChunks()
    }

    private fun openLevelDatEditor() {
        val intent = Intent(this, EditorActivity::class.java)
        intent.putExtra(EditorActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        startActivity(intent)
    }

    private fun openAllKeysBrowser() {
        val intent = Intent(this, PlayerDbActivity::class.java)
        intent.putExtra(PlayerDbActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            try { LevelDbSession.bridge?.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
    }
}
