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

        val zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            val out = Button(this@MapViewerActivity).apply {
                text = "−"
                textSize = 22f
                setOnClickListener { mapView.zoomOut() }
            }
            val `in` = Button(this@MapViewerActivity).apply {
                text = "+"
                textSize = 22f
                setOnClickListener { mapView.zoomIn() }
            }
            addView(out, LinearLayout.LayoutParams(76, 72))
            addView(`in`, LinearLayout.LayoutParams(76, 72))
        }
        root.addView(
            zoomControls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 24
                rightMargin = 24
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
    private var keyLoadingDialog: AlertDialog? = null

    private fun loadAndScan() {
        Thread {
            try {
                LevelDbSession.reset()
                // Opening the map must not wait for a world-wide key scan.
                // The viewport loader can read individual chunks directly from LevelDB.
                val bridge = LevelDbBridge.open(worldDir)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldDir = worldDir

                // Read only the local player first. This is a single DB lookup and gives
                // us an immediate camera target without inspecting thousands of chunks.
                val playerPos = try {
                    val rawKey = "~local_player".toByteArray(Charsets.US_ASCII)
                    val bytes = bridge.read(rawKey)
                    val compound = bytes?.let { NbtIO.readRootCompoundNoHeader(it) }
                    val pos = compound?.entries?.get("Pos") as? NbtTag.ListTag
                    val x = (pos?.items?.getOrNull(0) as? NbtTag.Float)?.value
                    val z = (pos?.items?.getOrNull(2) as? NbtTag.Float)?.value
                    if (x != null && z != null) x.toInt() to z.toInt() else null
                } catch (_: Exception) { null }

                runOnUiThread {
                    mapView.preferredCenterBlock = playerPos ?: (0 to 0)
                    mapView.clearChunkData()
                    mapView.onVisibleChunksChanged = { positions -> requestVisibleChunks(positions) }
                    coordText.text = "Loading visible chunks…"
                    // IMPORTANT: do not enumerate LevelDB here. The database can contain
                    // thousands of chunk records plus map_*, village, player, structure,
                    // entity and other records. Every non-map feature discovers/reads its
                    // own data only after the user explicitly asks for it.
                    mapView.refreshVisibleChunks()
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread { showErrorDialog(this, "Out of memory opening world", Exception("Could not open the world database.", e)) }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to open world map", e) }
            }
        }.start()
    }

    private fun requestVisibleChunks(positions: Set<Pair<Int, Int>>) {
        val bridge = LevelDbSession.bridge ?: return
        val todo = ArrayList<Pair<Int, Int>>()

        synchronized(loadingChunks) {
            for (pos in positions) {
                // Do not require a global chunk index here: visible chunks are queried directly from LevelDB.
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
                    // Viewport loading deliberately uses direct LevelDB seeks.
                    // We never need a complete chunk index just to draw what is on screen.
                    val loaded = com.spaimic.nbttool.chunk.ChunkScanner.loadChunkDirect(
                        bridge, pos.first, pos.second, unknown, reasons, examples
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
                com.spaimic.nbttool.chunk.ChunkDeleter.clearChunkToAir(bridge, chunkX, chunkZ)
                runOnUiThread { Toast.makeText(this, "Chunk cleared to air.", Toast.LENGTH_SHORT).show() }
                rescanMap()
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to delete chunk", e) }
            }
        }.start()
    }

    private fun showMenu() {
        // Building the menu itself must perform ZERO LevelDB enumeration or value reads.
        // Availability/counts are intentionally discovered only after the user taps an item.
        val options = listOf(
            "Local Player (~local_player)",
            "Multiplayer Players",
            "MCStructures",
            "Map Data (map_*)",
            "Village Data",
            "Ticking Areas",
            (if (mapView.showEntityIcons) "Hide" else "Show") + " Entity Icons",
            (if (mapView.showTileEntityIcons) "Hide" else "Show") + " Tile Entity Icons",
            "World settings (level.dat)",
            "Advanced: browse non-chunk DB keys"
        )

        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openLocalPlayerLazily()
                    1 -> showKeyPickerLazily("Multiplayer Players") { it.startsWith("player_") }
                    2 -> showKeyPickerLazily("MCStructures") { it.startsWith("structuretemplate_") }
                    3 -> showKeyPickerLazily("Map Data") { it.startsWith("map_") }
                    4 -> showKeyPickerLazily("Village Data") { it.startsWith("VILLAGE_") }
                    5 -> showKeyPickerLazily("Ticking Areas") { it.startsWith("tickingarea_") }
                    6 -> {
                        mapView.showEntityIcons = !mapView.showEntityIcons
                        mapView.invalidate()
                    }
                    7 -> {
                        mapView.showTileEntityIcons = !mapView.showTileEntityIcons
                        mapView.invalidate()
                    }
                    8 -> openLevelDatEditor()
                    9 -> openAllKeysBrowser()
                }
            }
            .show()
    }

    private fun openLocalPlayerLazily() {
        Thread {
            try {
                val bridge = LevelDbSession.bridge
                    ?: throw IllegalStateException("World database isn't open")
                val rawKey = "~local_player".toByteArray(Charsets.UTF_8)
                val bytes = bridge.read(rawKey)
                    ?: throw IllegalStateException("No ~local_player data exists in this world")
                val displayName = "~local_player"
                LevelDbSession.keyBytesByName[displayName] = rawKey
                LevelDbSession.loadedEntries[displayName] = NbtIO.readRootCompoundNoHeader(bytes)
                runOnUiThread { openEntry(displayName) }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Couldn't load ~local_player", e) }
            }
        }.start()
    }

    /**
     * The expensive key enumeration happens ONLY after the user selects a specific
     * lazy category. Values are still not read until the user selects a key.
     */
    private fun showKeyPickerLazily(titleText: String, predicate: (String) -> Boolean) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }

        keyLoadingDialog?.dismiss()
        keyLoadingDialog = AlertDialog.Builder(this)
            .setTitle(titleText)
            .setMessage("Loading available keys…")
            .setNegativeButton("Cancel", null)
            .create()
        keyLoadingDialog?.setOnDismissListener { keyLoadingDialog = null }
        keyLoadingDialog?.show()

        Thread {
            try {
                // Use LevelDB's sorted-key seeks instead of scanning the entire DB.
                // Village records use uppercase VILLAGE_ prefixes in Bedrock, and
                // tickingarea records use the lowercase tickingarea_ prefix.
                val prefixes = when (titleText) {
                    "Multiplayer Players" -> listOf("player_")
                    "MCStructures" -> listOf("structuretemplate_")
                    "Map Data" -> listOf("map_")
                    "Village Data" -> listOf("VILLAGE_")
                    "Ticking Areas" -> listOf("tickingarea_")
                    else -> emptyList()
                }

                val names = bridge.listKeysByPrefixes(prefixes)
                    .asSequence()
                    .map { it.displayName to it.rawKey }
                    .filter { predicate(it.first) }
                    .sortedBy { it.first }
                    .toList()

                synchronized(LevelDbSession.keyBytesByName) {
                    for ((name, rawKey) in names) {
                        LevelDbSession.keyBytesByName[name] = rawKey
                    }
                }

                runOnUiThread {
                    keyLoadingDialog?.dismiss()
                    keyLoadingDialog = null
                    if (names.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle(titleText)
                            .setMessage("No matching LevelDB entries were found.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("$titleText (${names.size})")
                            .setItems(names.map { it.first }.toTypedArray()) { _, which ->
                                openEntry(names[which].first)
                            }
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    keyLoadingDialog?.dismiss()
                    keyLoadingDialog = null
                    showErrorDialog(this, "Couldn't discover $titleText", e)
                }
            }
        }.start()
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
                LevelDbSession.saveAllLoadedEntries()
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
                rescanMap() // in case a pasted structure or edit changed visible terrain
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Save failed (your edits are still kept — safe to retry)", e) }
            }
        }.start()
    }

    private fun rescanMap() {
        chunkIndex = null
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
