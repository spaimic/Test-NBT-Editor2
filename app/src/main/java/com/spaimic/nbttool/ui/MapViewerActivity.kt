package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.*
import android.graphics.Bitmap
import com.spaimic.nbttool.export.MapSelectionExporter
import com.spaimic.nbttool.selection.WorldSelection
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

private fun safeSelectionPreviewCrop(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
    val sx = x.coerceIn(0, source.width - 1)
    val sy = y.coerceIn(0, source.height - 1)
    val sw = width.coerceIn(1, source.width - sx)
    val sh = height.coerceIn(1, source.height - sy)
    return safeSelectionPreviewCrop(source, sx, sy, sw, sh)
}

class MapViewerActivity : AppCompatActivity() {

    private lateinit var worldDir: File
    private lateinit var mapView: MapView
    private lateinit var coordText: TextView
    private var currentDimension = 0 // UI: 0 Overworld, 1 Nether, 2 The End
    private var playerOverworldPosition: Pair<Int, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "${LocalWorldStore.levelName(worldDir)} — Loading…"

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

        val dimensionButton = Button(this).apply {
            text = "⋮"
            textSize = 22f
            setOnClickListener { showDimensionMenu() }
        }
        root.addView(
            dimensionButton,
            FrameLayout.LayoutParams(76, 72).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 104
                rightMargin = 24
            }
        )
        setContentView(root)

        mapView.onTap = { x, y, z ->
            coordText.text = "X: $x\nY: $y\nZ: $z"
            showChunkDialog(x, z)
        }
        mapView.onSelectionChanged = { s ->
            coordText.text = if (s == null) "${dimensionName()} • X: ${mapView.currentCameraBlockX()}  Z: ${mapView.currentCameraBlockZ()}"
                else "${dimensionName()} • Selection ${s.width}×${s.height} • X ${s.minX}..${s.maxX} • Z ${s.minZ}..${s.maxZ}"
        }

        loadAndScan()
    }

    private var chunkIndex: com.spaimic.nbttool.chunk.ChunkIndex? = null
    private val requestedChunks = HashSet<Pair<Int, Int>>()
    private val loadedChunks = HashSet<Pair<Int, Int>>()
    private val loadingChunks = HashSet<Pair<Int, Int>>()
    private val mapExecutor = java.util.concurrent.Executors.newFixedThreadPool(6)
    // Marker reads can be slower than terrain decoding; keep them off the terrain
    // pipeline so icons never delay the first visible map pixels.
    private val markerExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
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

                val playerDimension = try { readPlayerDimension(bridge) } catch (_: Exception) { 0 }
                val rawPlayerPos = playerPos ?: (0 to 0)
                playerOverworldPosition = if (playerDimension == 1) {
                    rawPlayerPos.first * 8 to rawPlayerPos.second * 8
                } else {
                    rawPlayerPos
                }

                runOnUiThread {
                    currentDimension = playerDimension
                    mapView.preferredCenterBlock = when (currentDimension) {
                        1 -> Math.floorDiv(playerOverworldPosition!!.first, 8) to Math.floorDiv(playerOverworldPosition!!.second, 8)
                        else -> rawPlayerPos
                    }
                    mapView.clearChunkData()
                    mapView.onVisibleChunksChanged = { positions -> requestVisibleChunks(positions) }
                    title = "${LocalWorldStore.levelName(worldDir)} — ${dimensionName()}"
                    coordText.text = "${dimensionName()} • Loading visible chunks…"
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
        val dimensionForRequest = selectedDbDimension()
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

        // Prioritize chunks closest to the camera. This makes the map look
        // effectively instant because the center appears first while outer
        // chunks continue loading in the background.
        val centerX = mapView.currentCameraBlockX() / 16f
        val centerZ = mapView.currentCameraBlockZ() / 16f
        todo.sortBy {
            val dx = it.first - centerX
            val dz = it.second - centerZ
            dx * dx + dz * dz
        }

        for (pos in todo) {
            mapExecutor.execute {
                try {
                    val unknown = HashSet<String>()
                    val reasons = HashMap<String, Int>()
                    val examples = HashMap<String, String>()
                    // Viewport loading deliberately uses direct LevelDB seeks.
                    // We never need a complete chunk index just to draw what is on screen.
                    val loaded = com.spaimic.nbttool.chunk.ChunkScanner.loadChunkDirect(
                        bridge, pos.first, pos.second, unknown, reasons, examples, dimensionForRequest, includeMarkers = false
                    )
                    runOnUiThread {
                        if (currentDimension != dimensionForRequest) {
                            synchronized(loadingChunks) { loadingChunks.remove(pos) }
                            return@runOnUiThread
                        }
                        synchronized(loadingChunks) {
                            loadingChunks.remove(pos)
                            // A null result means the direct LevelDB seek/parser
                            // did not produce terrain. Do not permanently mark the
                            // chunk as loaded, otherwise one failed read becomes a
                            // permanent gray square on the map.
                            if (loaded != null) loadedChunks.add(pos) else loadedChunks.remove(pos)
                        }
                        if (loaded != null) {
                            // Terrain is available immediately; marker records are
                            // intentionally loaded on a separate tiny executor.
                            mapView.setChunkData(mapOf(pos to loaded.grid))

                            markerExecutor.execute {
                                val markers = com.spaimic.nbttool.chunk.ChunkScanner.loadChunkMarkers(
                                    bridge, pos.first, pos.second, dimensionForRequest
                                )
                                runOnUiThread {
                                    if (currentDimension != dimensionForRequest) return@runOnUiThread
                                    synchronized(markerLock) {
                                        loadedEntityMarkers.addAll(markers.entityMarkers.map {
                                            MapView.MarkerDraw(it.worldX, it.worldZ,
                                                com.spaimic.nbttool.chunk.EntityIcons.iconFor(it.identifier))
                                        })
                                        loadedBlockEntityMarkers.addAll(markers.blockEntityMarkers.map {
                                            MapView.MarkerDraw(it.worldX, it.worldZ,
                                                com.spaimic.nbttool.chunk.TileEntityIcons.iconFor(this, it.id))
                                        })
                                        mapView.entityMarkers = loadedEntityMarkers.toList()
                                        mapView.blockEntityMarkers = loadedBlockEntityMarkers.toList()
                                    }
                                }
                            }
                        }
                        coordText.text = "${dimensionName()} • X: ${mapView.currentCameraBlockX()}  Z: ${mapView.currentCameraBlockZ()}"
                    }
                } catch (e: Throwable) {
                    runOnUiThread {
                        synchronized(loadingChunks) { loadingChunks.remove(pos) }
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
        val clipboardEntries = ChunkClipboard.list(this)
        val options = arrayListOf(
            "Chunk Information",
            "🟦 Start Selection",
            "💎 Show All Diamond Coordinates",
        )
        if (selectedDbDimension() == 1) options.add("🏺 Show All Ancient Debris Coordinates")
        options.addAll(listOf(
            "Show Entities",
            "Show Block Entities",
            "Copy Chunk",
            "Paste Chunk",
            "Delete Chunk",
            "Terrain Editor"
        ))
        if (mapView.selection != null) {
            options.addAll(listOf(
                "📸 Selection: Take Picture of Map",
                "🗑️ Selection: Delete Area",
                "❌ Clear Selection"
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Chunk ($chunkX, $chunkZ)")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Chunk Information" -> showChunkInformation(chunkX, chunkZ)
                    "🟦 Start Selection" -> startSelection(worldX, worldZ)
                    "💎 Show All Diamond Coordinates" -> showDiamondCoordinates(chunkX, chunkZ)
                    "🏺 Show All Ancient Debris Coordinates" -> showAncientDebrisCoordinates(chunkX, chunkZ)
                    "Show Entities" -> {
                        val i = Intent(this, EntityListActivity::class.java)
                        i.putExtra(EntityListActivity.EXTRA_CHUNK_X, chunkX)
                        i.putExtra(EntityListActivity.EXTRA_CHUNK_Z, chunkZ)
                        i.putExtra(EntityListActivity.EXTRA_DIMENSION, selectedDbDimension())
                        startActivity(i)
                    }
                    "Show Block Entities" -> {
                        val i = Intent(this, BlockEntityListActivity::class.java)
                        i.putExtra(BlockEntityListActivity.EXTRA_CHUNK_X, chunkX)
                        i.putExtra(BlockEntityListActivity.EXTRA_CHUNK_Z, chunkZ)
                        i.putExtra(BlockEntityListActivity.EXTRA_DIMENSION, selectedDbDimension())
                        startActivity(i)
                    }
                    "Copy Chunk" -> copyChunk(chunkX, chunkZ)
                    "Paste Chunk" -> chooseChunkToPaste(chunkX, chunkZ)
                    "Delete Chunk" -> promptDeleteChunk(chunkX, chunkZ)
                    "Terrain Editor" -> {
                        val i = Intent(this, ChunkBlockEditorActivity::class.java)
                        i.putExtra(ChunkBlockEditorActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
                        i.putExtra(ChunkBlockEditorActivity.EXTRA_CHUNK_X, chunkX)
                        i.putExtra(ChunkBlockEditorActivity.EXTRA_CHUNK_Z, chunkZ)
                        i.putExtra(ChunkBlockEditorActivity.EXTRA_DIMENSION, selectedDbDimension())
                        startActivity(i)
                    }
                    "📸 Selection: Take Picture of Map" -> promptSelectionPicture()
                    "🗑️ Selection: Delete Area" -> promptDeleteSelection()
                    "❌ Clear Selection" -> mapView.clearSelection()
                }
            }.show()
    }

    private fun startSelection(worldX: Int, worldZ: Int) {
        if (mapView.selection != null) {
            Toast.makeText(this, "A selection already exists. Clear it before starting another.", Toast.LENGTH_LONG).show()
            return
        }
        mapView.startSelection(worldX, worldZ)
        Toast.makeText(this, "64×64 selection started at ($worldX, $worldZ). Drag the four handles to resize.", Toast.LENGTH_LONG).show()
    }

    private fun promptSelectionPicture() {
        val s = mapView.selection ?: return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("12")
            hint = "Pixels per block (1–24)"
        }
        AlertDialog.Builder(this)
            .setTitle("📸 Take Picture of Map")
            .setMessage("Selection: ${s.width} × ${s.height} blocks\nChoose pixels per block (1–24).")
            .setView(input)
            .setPositiveButton("Preview") { _, _ ->
                val ppb = input.text.toString().toIntOrNull()?.coerceIn(1,24) ?: 12
                previewSelectionPicture(s, ppb)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewSelectionPicture(s: WorldSelection, ppb: Int) {
        val bridge = LevelDbSession.bridge ?: run {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Rendering preview…").setMessage("Please wait.").setView(ProgressBar(this)).setNegativeButton("Cancel", null).create()
        dialog.show()
        Thread {
            try {
                val preview = MapSelectionExporter.preview(bridge,s,selectedDbDimension(),ppb)
                runOnUiThread {
                    dialog.dismiss()
                    val image = ImageView(this).apply {
                        setImageBitmap(preview)
                        adjustViewBounds=true
                        scaleType=ImageView.ScaleType.FIT_CENTER
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Map Picture Preview")
                        .setMessage("${s.width} × ${s.height} blocks • ${ppb} px/block")
                        .setView(image)
                        .setPositiveButton("Save PNG") { _, _ -> saveSelectionPicture(s,ppb) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Throwable) {
                runOnUiThread { dialog.dismiss(); showErrorDialog(this,"Failed to render selection preview",e) }
            }
        }.start()
    }

    private fun saveSelectionPicture(s: WorldSelection, ppb: Int) {
        val bridge = LevelDbSession.bridge ?: return
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "NBTTool_Map_${s.minX}_${s.minZ}_${s.maxX}_${s.maxZ}_${ppb}px.png")
        Thread {
            try {
                runOnUiThread { Toast.makeText(this,"Saving map picture…",Toast.LENGTH_LONG).show() }
                MapSelectionExporter.export(bridge,s,selectedDbDimension(),ppb,file) { }
                runOnUiThread { Toast.makeText(this,"Saved to ${file.absolutePath}",Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this,"Failed to save map picture",e) }
            }
        }.start()
    }

    private fun promptDeleteSelection() {
        val s=mapView.selection ?: return
        val input=EditText(this).apply { hint="Type DELETE to confirm" }
        AlertDialog.Builder(this)
            .setTitle("Delete selected area?")
            .setMessage("Clear every block in the selected ${s.width} × ${s.height} area to air. Fully covered chunks also have their entities and block entities removed, just like Delete Chunk. This cannot be undone.")
            .setView(input)
            .setPositiveButton("Delete") { _, _ ->
                if(input.text.toString()=="DELETE") deleteSelection(s)
                else Toast.makeText(this,"Didn't match DELETE — nothing was changed.",Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel",null).show()
    }

    private fun deleteSelection(s: WorldSelection) {
        val bridge=LevelDbSession.bridge ?: return
        Thread {
            try {
                com.spaimic.nbttool.chunk.AreaDeleter.clearSelection(bridge,s,selectedDbDimension())
                runOnUiThread {
                    Toast.makeText(this,"Selected area cleared to air.",Toast.LENGTH_LONG).show()
                    mapView.clearSelection()
                    rescanMap()
                }
            } catch(e:Throwable) {
                runOnUiThread { showErrorDialog(this,"Failed to delete selected area",e) }
            }
        }.start()
    }

    private fun showDiamondCoordinates(chunkX: Int, chunkZ: Int) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        val dimension = selectedDbDimension()
        Thread {
            try {
                val coordinates = ChunkScanner.findDiamondCoordinates(bridge, chunkX, chunkZ, dimension)
                runOnUiThread {
                    if (coordinates.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("💎 Diamond Coordinates")
                            .setMessage("No diamond_ore or deepslate_diamond_ore blocks were found in this chunk.")
                            .setPositiveButton("OK", null)
                            .show()
                        return@runOnUiThread
                    }

                    val text = coordinates.joinToString("\n") { "💎 (${it.x}, ${it.y}, ${it.z})" }
                    AlertDialog.Builder(this)
                        .setTitle("💎 Diamond Coordinates (${coordinates.size})")
                        .setMessage(text)
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Copy All Coordinates") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Diamond Coordinates", text))
                            Toast.makeText(this, "Copied ${coordinates.size} diamond coordinates.", Toast.LENGTH_SHORT).show()
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to scan diamond coordinates", e) }
            }
        }.start()
    }

    private fun showAncientDebrisCoordinates(chunkX: Int, chunkZ: Int) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val coordinates = ChunkScanner.findAncientDebrisCoordinates(bridge, chunkX, chunkZ, 1)
                runOnUiThread {
                    if (coordinates.isEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("🏺 Ancient Debris Coordinates")
                            .setMessage("No ancient_debris blocks were found in this Nether chunk.")
                            .setPositiveButton("OK", null).show()
                        return@runOnUiThread
                    }
                    val text = coordinates.joinToString("\n") { "🏺 (${it.x}, ${it.y}, ${it.z})" }
                    AlertDialog.Builder(this)
                        .setTitle("🏺 Ancient Debris Coordinates (${coordinates.size})")
                        .setMessage(text)
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Copy All Coordinates") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Ancient Debris Coordinates", text))
                            Toast.makeText(this, "Copied ${coordinates.size} ancient debris coordinates.", Toast.LENGTH_SHORT).show()
                        }.show()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to scan ancient debris coordinates", e) }
            }
        }.start()
    }

    private fun showChunkInformation(chunkX: Int, chunkZ: Int) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                var rawRecords = 0
                var subchunks = 0
                var entities = 0
                var blockEntities = 0
                bridge.forEachRawEntry { key, value ->
                    val info = com.spaimic.nbttool.chunk.ChunkKeys.parse(key)
                    if (info != null && info.chunkX == chunkX && info.chunkZ == chunkZ && info.dimension == selectedDbDimension()) {
                        rawRecords++
                        if (info.tag == com.spaimic.nbttool.chunk.ChunkKeys.TAG_SUBCHUNK_PREFIX) subchunks++
                        if (info.tag == com.spaimic.nbttool.chunk.ChunkKeys.TAG_BLOCK_ENTITY) blockEntities++
                    } else if (com.spaimic.nbttool.chunk.EntityKeys.isDigpKeyForDimension(key, selectedDbDimension())) {
                        val (x, z) = com.spaimic.nbttool.chunk.EntityKeys.digpChunkXZ(key)
                        if (x == chunkX && z == chunkZ) {
                            entities += com.spaimic.nbttool.chunk.EntityKeys.splitActorIds(value).size
                        }
                    }
                }
                val message = "Chunk: ($chunkX, $chunkZ)\n" +
                    "Block range: X ${chunkX * 16}..${chunkX * 16 + 15}, Z ${chunkZ * 16}..${chunkZ * 16 + 15}\n" +
                    "Raw chunk records: $rawRecords\n" +
                    "Subchunks: $subchunks\n" +
                    "Entities: $entities\n" +
                    "Block-entity records: $blockEntities\n" +
                    "Dimension: ${dimensionName()}"
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Chunk Information").setMessage(message)
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to inspect chunk", e) }
            }
        }.start()
    }

    private fun copyChunk(chunkX: Int, chunkZ: Int) {
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                val copied = ChunkTransfer.copy(bridge, chunkX, chunkZ, selectedDbDimension())
                val preview = mapView.captureChunkPreviewPng(chunkX, chunkZ)
                val id = ChunkClipboard.save(
                    this,
                    ChunkClipboard.newEntry(copied.chunkX, copied.chunkZ, copied.dimension, copied.records, preview)
                )
                runOnUiThread {
                    Toast.makeText(this, "Copied chunk ($chunkX, $chunkZ) to the chunk clipboard.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to copy chunk", e) }
            }
        }.start()
    }

    private fun chooseChunkToPaste(targetX: Int, targetZ: Int) {
        val entries = ChunkClipboard.list(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Chunk clipboard is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { "${if (it.dimension == 1) "Nether" else if (it.dimension == 2) "The End" else "Overworld"} (${it.chunkX}, ${it.chunkZ}) — ${it.records.size} records" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose chunk to paste")
            .setItems(labels) { _, which -> promptPasteChunk(targetX, targetZ, entries[which]) }
            .show()
    }

    private fun promptPasteChunk(targetX: Int, targetZ: Int, clipboard: ChunkClipboard.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Paste chunk here?")
            .setMessage("Overwrite chunk ($targetX, $targetZ) with ${if (clipboard.dimension == 1) "Nether" else if (clipboard.dimension == 2) "The End" else "Overworld"} data from (${clipboard.chunkX}, ${clipboard.chunkZ})? Blocks, block entities, and entities will be replaced. The source and destination dimensions must match.")
            .setPositiveButton("Paste") { _, _ -> pasteChunk(targetX, targetZ, clipboard) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pasteChunk(targetX: Int, targetZ: Int, clipboard: ChunkClipboard.Entry) {
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                val written = ChunkTransfer.paste(bridge, clipboard, targetX, targetZ, selectedDbDimension())
                runOnUiThread { Toast.makeText(this, "Pasted chunk to ($targetX, $targetZ) — $written records written.", Toast.LENGTH_LONG).show() }
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

    /** Reads the player's current Bedrock dimension from ~local_player. Different
     * versions have used slightly different names, so prefer the canonical key and
     * accept the common variants as a compatibility fallback. */
    private fun readPlayerDimension(bridge: LevelDbBridge): Int {
        val raw = bridge.read("~local_player".toByteArray(Charsets.US_ASCII)) ?: return 0
        val compound = NbtIO.readRootCompoundNoHeader(raw) ?: return 0

        fun numeric(tag: NbtTag): Int? = when (tag) {
            is NbtTag.Byte -> tag.value.toInt()
            is NbtTag.Short -> tag.value.toInt()
            is NbtTag.Int -> tag.value
            is NbtTag.Long -> tag.value.toInt()
            else -> null
        }

        fun findDimension(tag: NbtTag): Int? {
            if (tag is NbtTag.Compound) {
                for (key in listOf("DimensionId", "Dimension", "dimension")) {
                    val candidate = tag.entries[key]?.let(::numeric)
                    if (candidate == 1 || candidate == 2) return candidate
                }
                for (child in tag.entries.values) {
                    val found = findDimension(child)
                    if (found == 1 || found == 2) return found
                }
            } else if (tag is NbtTag.ListTag) {
                for (child in tag.items) {
                    val found = findDimension(child)
                    if (found == 1 || found == 2) return found
                }
            }
            return null
        }

        return findDimension(compound) ?: 0
    }

    private fun dimensionName(): String = when (currentDimension) {
        1 -> "Nether"
        2 -> "The End"
        else -> "Overworld"
    }

    /** Bedrock LevelDB chunk-key dimension IDs: Overworld=0, Nether=1, The End=2. */
    private fun selectedDbDimension(): Int = when (currentDimension) {
        1 -> 1
        2 -> 2
        else -> 0
    }

    private fun showDimensionMenu() {
        val names = arrayOf("Overworld", "Nether", "The End")
        AlertDialog.Builder(this)
            .setTitle("Switch Map Dimension")
            .setSingleChoiceItems(names, currentDimension) { dialog, which ->
                if (which != currentDimension) {
                    currentDimension = which
                    dialog.dismiss()
                    switchMapDimension()
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchMapDimension() {
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
        // Bedrock's Nether portal coordinate relationship is 8:1. When entering
        // the Nether from the Overworld, anchor the map at the corresponding Nether
        // coordinates instead of leaving the camera at the Overworld position.
        val current = mapView.preferredCenterBlock ?: (0 to 0)
        mapView.preferredCenterBlock = if (currentDimension == 1) {
            (Math.floorDiv(current.first, 8) to Math.floorDiv(current.second, 8))
        } else if (currentDimension == 0) {
            playerOverworldPosition ?: current
        } else {
            current
        }
        title = "${LocalWorldStore.levelName(worldDir)} — ${dimensionName()}"
        coordText.text = "${dimensionName()} • Loading visible chunks…"
        mapView.refreshVisibleChunks()
    }

    private fun showMenu() {
        // Keep the world hamburger menu focused on the main editors/tools.
        // Extra LevelDB records are grouped behind the Advanced browser so the
        // main menu remains compact and easy to scroll/use on small screens.
        val options = listOf(
            "Player Editor",
            "World Settings Editor",
            "Multiplayer Editor",
            "MCStructure Editor",
            "Ticking Areas Editor",
            "Village Editor",
            (if (mapView.showEntityIcons) "Hide Entities on the Map" else "Show Entities on the Map"),
            (if (mapView.showTileEntityIcons) "Hide Tile Entities on the Map" else "Show Tile Entities on the Map"),
            "Overworld NBT Data",
            "Nether NBT Data",
            "The End NBT Data",
            "NBT Tag Clipboard",
            "Chunk Clipboard",
            "Open LevelDB Key by Name",
            "Advanced: Browse Extra DB Keys"
        )

        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openLocalPlayerLazily()
                    1 -> openLevelDatEditor()
                    2 -> showKeyPickerLazily("Multiplayer Players") { it.startsWith("player_server_") }
                    3 -> showKeyPickerLazily("MCStructures") { it.startsWith("structuretemplate_") }
                    4 -> showKeyPickerLazily("Ticking Areas") { it.startsWith("tickingarea_") }
                    5 -> showKeyPickerLazily("Village Data") { it.startsWith("VILLAGE_Overworld_") }
                    6 -> {
                        mapView.showEntityIcons = !mapView.showEntityIcons
                        mapView.invalidate()
                    }
                    7 -> {
                        mapView.showTileEntityIcons = !mapView.showTileEntityIcons
                        mapView.invalidate()
                    }
                    8 -> showExactKey("Overworld NBT Data", "Overworld")
                    9 -> showExactKey("Nether NBT Data", "Nether")
                    10 -> showExactKey("The End NBT Data", "TheEnd")
                    11 -> startActivity(Intent(this, ClipboardActivity::class.java))
                    12 -> startActivity(Intent(this, ChunkClipboardActivity::class.java))
                    13 -> promptOpenLevelDbKeyByName()
                    14 -> openAllKeysBrowser()
                }
            }
            .show()
    }

    private fun promptOpenLevelDbKeyByName() {
        val input = EditText(this).apply {
            hint = "e.g. ~local_player"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Open LevelDB Key by Name")
            .setMessage("Enter the exact LevelDB key name to open.")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                val keyName = input.text.toString()
                if (keyName.isBlank()) {
                    AlertDialog.Builder(this)
                        .setTitle("Invalid LevelDB Key")
                        .setMessage("Please enter a LevelDB key name.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    openLevelDbKeyByName(keyName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openLevelDbKeyByName(keyName: String) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            showErrorDialog(this, "LevelDB unavailable", IllegalStateException("The world database is not open yet."))
            return
        }
        Thread {
            try {
                val rawKey = keyName.toByteArray(Charsets.UTF_8)
                val bytes = bridge.read(rawKey)
                if (bytes == null) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("LevelDB Key Not Found")
                            .setMessage("This LevelDB Key doesn't exist.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@Thread
                }
                val compound = try {
                    NbtIO.readRootCompoundNoHeader(bytes)
                } catch (_: Exception) {
                    null
                }
                if (compound == null) {
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("No NBT Data")
                            .setMessage("No valid NBT data found")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@Thread
                }
                LevelDbSession.keyBytesByName[keyName] = rawKey
                LevelDbSession.loadedEntries[keyName] = compound
                runOnUiThread { openEntry(keyName) }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Couldn't open LevelDB key", e) }
            }
        }.start()
    }

    private fun showExactKey(titleText: String, keyName: String) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val rawKey = keyName.toByteArray(Charsets.UTF_8)
                val bytes = bridge.read(rawKey)
                    ?: throw IllegalStateException("No $keyName data exists in this world")
                LevelDbSession.keyBytesByName[keyName] = rawKey
                LevelDbSession.loadedEntries[keyName] = NbtIO.readRootCompoundNoHeader(bytes)
                runOnUiThread { openEntry(keyName) }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Couldn't load $titleText", e) }
            }
        }.start()
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
                    "Village Data" -> listOf("VILLAGE_Overworld_")
                    "Ticking Areas" -> listOf("tickingarea_")
                    "Player Data" -> listOf("player_")
                    "Server Player Data" -> listOf("player_server_")
                    "MCStructure Data" -> listOf("structuretemplate_")
                    "Map Data (map_*)" -> listOf("map_")
                    "Village Data (VILLAGE_Overworld_*)" -> listOf("VILLAGE_Overworld_")
                    "Ticking Areas (tickingarea_*)" -> listOf("tickingarea_")
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


    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
    }
}
