package com.spaimic.nbttool.ui
import android.content.ContentValues
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.view.View
import android.view.ViewGroup
import com.spaimic.nbttool.R

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
import android.graphics.BitmapFactory
import com.spaimic.nbttool.export.MapSelectionExporter
import com.spaimic.nbttool.export.WholeMapExporter
import com.spaimic.nbttool.export.WholeMapBounds
import com.spaimic.nbttool.selection.WorldSelection
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.spaimic.nbttool.chunk.ChunkScanner
import com.spaimic.nbttool.chunk.ChunkClipboard
import com.spaimic.nbttool.chunk.ChunkTransfer
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The screen you land on after tapping a world: a top-down terrain-colored map
 * of every loaded chunk (unloaded ones flat gray), pannable by dragging, with
 * tap-to-see-coordinates. The left-edge drawer jumps straight to the
 * things you'd normally want to edit, without hunting through the full key list.
 */
class MapViewerActivity : AppCompatActivity() {

    private lateinit var worldDir: File
    private lateinit var mapView: MapView
    private lateinit var coordText: TextView
    private var currentDimension = 0 // UI: 0 Overworld, 1 Nether, 2 The End
    private var playerOverworldPosition: Pair<Int, Int>? = null
    private var localPlayerPosition: Triple<Int, Int, Int>? = null
    private var localPlayerDimension: Int = 0
    private val wholeMapExportRunning = AtomicBoolean(false)
    private var preparedWholeMapPng: File? = null
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerGameModeText: TextView
    private lateinit var gridToggleText: TextView
    private lateinit var entityToggleText: TextView
    private lateinit var tileEntityToggleText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "${LocalWorldStore.levelName(worldDir)} — Loading…"

        drawerLayout = DrawerLayout(this)
        val contentRoot = FrameLayout(this)
        mapView = MapView(this)
        contentRoot.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        coordText = TextView(this).apply {
            text = "Tap the map for coordinates"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setPadding(20, 12, 20, 12)
        }
        val overlayColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(coordText)
        }
        contentRoot.addView(
            overlayColumn,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )

        val zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.WHITE)
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
        contentRoot.addView(
            zoomControls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 24
                rightMargin = 24
            }
        )

        // Dimension switching intentionally remains on the main map screen.
        val dimensionButton = ImageButton(this).apply {
            // Explicitly clear any inherited image tint. Build 199 could inherit a
            // theme tint on some Android versions, making the supplied map icon
            // effectively disappear against the white button.
            setImageResource(R.drawable.map)
            imageTintList = null
            setColorFilter(Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Change map dimension"
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showDimensionMenu() }
        }
        contentRoot.addView(
            dimensionButton,
            FrameLayout.LayoutParams(76, 72).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 104
                rightMargin = 24
            }
        )

        // Small map-options FAB: tapping it reveals extra map actions such as the
        // already-prepared whole-map picture export.
        val wholeMapAction = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            elevation = dp(6).toFloat()
        }
        val wholeMapActionIcon = ImageView(this).apply {
            setImageResource(R.drawable.camera)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val wholeMapActionText = TextView(this).apply {
            text = "Take Entire Map Picture"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(dp(6), 0, dp(4), 0)
        }
        wholeMapAction.addView(wholeMapActionIcon, LinearLayout.LayoutParams(dp(42), dp(48)))
        wholeMapAction.addView(wholeMapActionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        wholeMapAction.setOnClickListener {
            wholeMapAction.visibility = View.GONE
            promptWholeMapPicture()
        }
        contentRoot.addView(wholeMapAction, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(18)
            bottomMargin = dp(86)
        })

        val wholeMapFab = TextView(this).apply {
            text = "⋮"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            contentDescription = "Map options"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.LTGRAY)
            }
            elevation = dp(7).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                wholeMapAction.visibility = if (wholeMapAction.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        contentRoot.addView(wholeMapFab, FrameLayout.LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(18)
            bottomMargin = dp(18)
        })

        val drawer = buildToolsDrawer()
        drawerLayout.addView(contentRoot, DrawerLayout.LayoutParams(-1, -1))
        drawerLayout.addView(drawer, DrawerLayout.LayoutParams(dp(340), -1).apply { gravity = Gravity.LEFT })
        setContentView(drawerLayout)

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
                    val y = (pos?.items?.getOrNull(1) as? NbtTag.Float)?.value
                    val z = (pos?.items?.getOrNull(2) as? NbtTag.Float)?.value
                    if (x != null && z != null) x.toInt() to z.toInt() else null
                } catch (_: Exception) { null }

                try {
                    val raw = bridge.read("~local_player".toByteArray(Charsets.US_ASCII))
                    val compound = raw?.let { NbtIO.readRootCompoundNoHeader(it) }
                    val mode = (compound?.entries?.get("PlayerGameMode") as? NbtTag.Int)?.value
                        ?: (compound?.entries?.get("GameType") as? NbtTag.Int)?.value
                    if (::drawerGameModeText.isInitialized && mode != null) {
                        runOnUiThread { drawerGameModeText.text = "Player Gamemode: ${gameModeName(mode)}" }
                    }
                } catch (_: Exception) { }
                val playerDimension = try { readPlayerDimension(bridge) } catch (_: Exception) { 0 }
                localPlayerDimension = playerDimension
                // Read the full coordinate again here so ore-finders can sort by real
                // player distance, including Y. If the NBT is unavailable, keep the
                // existing X/Z fallback used for camera placement.
                localPlayerPosition = try {
                    val rawKey = "~local_player".toByteArray(Charsets.US_ASCII)
                    val bytes = bridge.read(rawKey)
                    val compound = bytes?.let { NbtIO.readRootCompoundNoHeader(it) }
                    val pos = compound?.entries?.get("Pos") as? NbtTag.ListTag
                    val x = (pos?.items?.getOrNull(0) as? NbtTag.Float)?.value
                    val y = (pos?.items?.getOrNull(1) as? NbtTag.Float)?.value
                    val z = (pos?.items?.getOrNull(2) as? NbtTag.Float)?.value
                    if (x != null && y != null && z != null) Triple(
                Math.floor(x.toDouble()).toInt(),
                Math.floor(y.toDouble()).toInt(),
                Math.floor(z.toDouble()).toInt()
            ) else null
                } catch (_: Exception) { null }
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

    private fun findDiamondsNearPlayer() {
        showOreSearchModeDialog(true)
    }

    private fun findAncientDebrisNearPlayer() {
        showOreSearchModeDialog(false)
    }

    /** The coordinate search is intentionally nested inside each ore finder. */
    private fun showOreSearchModeDialog(diamond: Boolean) {
        val icon = if (diamond) "💎" else "🏺"
        val noun = if (diamond) "Diamonds" else "Ancient Debris"
        AlertDialog.Builder(this)
            .setTitle("$icon Find $noun")
            .setItems(arrayOf(
                "Find Near Current Player",
                "Find at Custom Coordinates"
            )) { _, which ->
                if (which == 0) startPlayerOreSearch(diamond) else promptCustomOreCoordinates(diamond)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomOreCoordinates(diamond: Boolean) {
        val labels = if (diamond) "Overworld X, Y, Z" else "Nether X, Y, Z"
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
        }
        val inputs = arrayOf("X", "Y", "Z").map { axis ->
            EditText(this).apply {
                hint = axis
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine(true)
                container.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Find at Custom Coordinates")
            .setMessage("Enter $labels. The same filled 9 × 9 = 81 chunk search will be used.")
            .setView(container)
            .setPositiveButton("Search") { _, _ ->
                val values = inputs.map { it.text.toString().trim().toIntOrNull() }
                if (values.any { it == null }) {
                    Toast.makeText(this, "Please enter valid X, Y and Z coordinates.", Toast.LENGTH_LONG).show()
                } else {
                    startCustomOreSearch(diamond, values[0]!!, values[1]!!, values[2]!!)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Reads the local player at the moment the map action is tapped, then scans. */
    private fun startPlayerOreSearch(diamond: Boolean) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        val title = if (diamond) "💎 Finding Diamonds" else "🏺 Finding Ancient Debris"
        val searchingMessage = if (diamond)
            "Reading the current player position and searching a 9 × 9 chunk square…"
        else
            "Reading the current player position and searching a 9 × 9 Nether chunk square…"
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(searchingMessage)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        Thread {
            try {
                val local = readCurrentLocalPlayerPosition(bridge)
                if (local == null) throw IllegalStateException("Could not read the current player's coordinates.")
                val playerDimension = try { readPlayerDimension(bridge) } catch (_: Exception) { localPlayerDimension }
                val target = convertOreSearchCoordinates(diamond, playerDimension, local)
                if (diamond) {
                    val result = ChunkScanner.findDiamondsAtCoordinates(bridge, target.first, target.second, target.third, 0)
                    runOnUiThread {
                        if (!isFinishing) {
                            dialog.dismiss()
                            showOreSearchResult("💎 Finding Diamonds", result, true)
                        }
                    }
                } else {
                    val result = ChunkScanner.findAncientDebrisAtCoordinates(bridge, target.first, target.second, target.third, 1)
                    runOnUiThread {
                        if (!isFinishing) {
                            dialog.dismiss()
                            showOreSearchResult("🏺 Finding Ancient Debris", result, false)
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.dismiss()
                        showErrorDialog(this, "Failed to search for ${if (diamond) "diamonds" else "ancient debris"}", e)
                    }
                }
            }
        }.start()
    }

    private fun startCustomOreSearch(diamond: Boolean, x: Int, y: Int, z: Int) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        val title = if (diamond) "💎 Finding Diamonds" else "🏺 Finding Ancient Debris"
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Searching a 9 × 9 = 81 chunk square centered at ($x, $y, $z)…")
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        Thread {
            try {
                if (diamond) {
                    val result = ChunkScanner.findDiamondsAtCoordinates(bridge, x, y, z, 0)
                    runOnUiThread {
                        if (!isFinishing) {
                            dialog.dismiss()
                            showOreSearchResult("$title at ($x, $y, $z)", result, true)
                        }
                    }
                } else {
                    val result = ChunkScanner.findAncientDebrisAtCoordinates(bridge, x, y, z, 1)
                    runOnUiThread {
                        if (!isFinishing) {
                            dialog.dismiss()
                            showOreSearchResult("$title at ($x, $y, $z)", result, false)
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (!isFinishing) {
                        dialog.dismiss()
                        showErrorDialog(this, "Failed to search for ${if (diamond) "diamonds" else "ancient debris"}", e)
                    }
                }
            }
        }.start()
    }

    private fun convertOreSearchCoordinates(
        diamond: Boolean,
        playerDimension: Int,
        local: Triple<Int, Int, Int>
    ): Triple<Int, Int, Int> = if (diamond) {
        when (playerDimension) {
            0 -> local
            1 -> Triple(local.first * 8, local.second, local.third * 8)
            else -> throw IllegalStateException("Diamond search needs an Overworld or Nether player position.")
        }
    } else {
        when (playerDimension) {
            1 -> local
            0 -> Triple(Math.floorDiv(local.first, 8), local.second, Math.floorDiv(local.third, 8))
            else -> throw IllegalStateException("Ancient Debris search needs an Overworld or Nether player position.")
        }
    }

    private fun readCurrentLocalPlayerPosition(bridge: LevelDbBridge): Triple<Int, Int, Int>? {
        return try {
            val rawKey = "~local_player".toByteArray(Charsets.US_ASCII)
            val bytes = bridge.read(rawKey)
            val compound = bytes?.let { NbtIO.readRootCompoundNoHeader(it) }
            val pos = compound?.entries?.get("Pos") as? NbtTag.ListTag
            val x = (pos?.items?.getOrNull(0) as? NbtTag.Float)?.value
            val y = (pos?.items?.getOrNull(1) as? NbtTag.Float)?.value
            val z = (pos?.items?.getOrNull(2) as? NbtTag.Float)?.value
            if (x != null && y != null && z != null) Triple(
                Math.floor(x.toDouble()).toInt(),
                Math.floor(y.toDouble()).toInt(),
                Math.floor(z.toDouble()).toInt()
            ) else null
        } catch (_: Exception) { null }
    }

    private fun <T : Any> showOreSearchResult(
        title: String,
        result: com.spaimic.nbttool.chunk.OreSearchResult<T>,
        diamond: Boolean
    ) {
        val icon = if (diamond) "💎" else "🏺"
        val noun = if (diamond) "diamonds" else "ancient debris"
        val dialog = AlertDialog.Builder(this)
            .setTitle("$title — Results")
            .setNegativeButton("Close", null)
            .create()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val sortGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val nearest = RadioButton(this).apply { text = "Nearest"; id = View.generateViewId(); isChecked = true }
        val most = RadioButton(this).apply { text = "Most Ores"; id = View.generateViewId() }
        sortGroup.addView(nearest, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sortGroup.addView(most, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(sortGroup)

        val textView = TextView(this).apply {
            setPadding(16, 12, 16, 12)
            textSize = 15f
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scroll)

        val copyButton = Button(this).apply { text = "📋 Copy Group Coordinates" }
        root.addView(copyButton)
        dialog.setView(root)

        fun updateText(mostOres: Boolean) {
            val clusters = if (mostOres) {
                result.clusters.sortedWith(compareByDescending<com.spaimic.nbttool.chunk.OreCluster<T>> { it.coordinates.size }
                    .thenBy { it.distanceSquaredToPlayer })
            } else {
                result.clusters.sortedBy { it.distanceSquaredToPlayer }
            }
            val lines = StringBuilder()
                .append("Search center: (${result.playerX}, ${result.playerY}, ${result.playerZ})\n")
                .append("Center chunk: (${result.centerChunkX}, ${result.centerChunkZ})\n")
                .append("Searched: 9 × 9 = ${result.searchedChunks} chunks\n")
                .append("Found: ${result.coordinates.size} $noun in ${clusters.size} group")
            if (clusters.size != 1) lines.append("s")
            lines.append(".\n\n")
            clusters.forEachIndexed { index, cluster ->
                val distance = Math.sqrt(cluster.distanceSquaredToPlayer.toDouble())
                lines.append("$icon Group ${index + 1}: ${cluster.coordinates.size} $noun • ${String.format(java.util.Locale.US, "%.1f", distance)} blocks away\n")
                val sorted = cluster.coordinates.sortedBy { coordinateDistanceSquared(it, result.playerX, result.playerY, result.playerZ) }
                sorted.forEach { coordinate -> lines.append("  ${formatOreCoordinate(coordinate)}\n") }
                lines.append("\n")
            }
            if (clusters.isEmpty()) lines.append("No $noun were found in the searched square.")
            textView.text = lines.toString()
            copyButton.tag = clusters
        }

        nearest.setOnClickListener { updateText(false) }
        most.setOnClickListener { updateText(true) }
        copyButton.setOnClickListener {
            @Suppress("UNCHECKED_CAST")
            val clusters = (copyButton.tag as? List<com.spaimic.nbttool.chunk.OreCluster<T>>) ?: emptyList()
            promptCopyOreGroups(clusters, diamond, result)
        }
        updateText(false)
        dialog.show()
    }

    private fun <T : Any> promptCopyOreGroups(
        clusters: List<com.spaimic.nbttool.chunk.OreCluster<T>>,
        diamond: Boolean,
        result: com.spaimic.nbttool.chunk.OreSearchResult<T>
    ) {
        val input = EditText(this).apply {
            hint = "Example: 1,3,5"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Copy Group Coordinates")
            .setMessage("Enter up to 10 group numbers, separated by commas.")
            .setView(input)
            .setPositiveButton("Copy") { _, _ ->
                val raw = input.text.toString().trim()
                val numbers = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (numbers.size > 10) {
                    Toast.makeText(this, "You can copy up to 10 groups at once.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val ids = LinkedHashSet<Int>()
                for (part in numbers) {
                    val n = part.toIntOrNull()
                    if (n == null || n < 1 || n > clusters.size) {
                        Toast.makeText(this, "Invalid group number: $part", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    ids.add(n)
                }
                val selected = ids.map { clusters[it - 1] }
                val coordinateText = selected.mapIndexed { index, cluster ->
                    val coordinates = cluster.coordinates
                        .sortedBy { coordinateDistanceSquared(it, result.playerX, result.playerY, result.playerZ) }
                        .joinToString("\n") { formatPlainOreCoordinate(it) }
                    "Group ${ids.elementAt(index)}:\n$coordinates"
                }.joinToString("\n\n")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("${if (diamond) "Diamond" else "Ancient Debris"} Group Coordinates", coordinateText))
                Toast.makeText(this, "Copied ${selected.size} group${if (selected.size == 1) "" else "s"}.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatPlainOreCoordinate(value: Any): String = when (value) {
        is com.spaimic.nbttool.chunk.DiamondCoordinate -> "(${value.x}, ${value.y}, ${value.z})"
        is com.spaimic.nbttool.chunk.OreCoordinate -> "(${value.x}, ${value.y}, ${value.z})"
        else -> value.toString()
    }

    private fun formatOreCoordinate(value: Any): String = when (value) {
        is com.spaimic.nbttool.chunk.DiamondCoordinate -> "💎 (${value.x}, ${value.y}, ${value.z})"
        is com.spaimic.nbttool.chunk.OreCoordinate -> "🏺 (${value.x}, ${value.y}, ${value.z})"
        else -> value.toString()
    }

    private fun coordinateDistanceSquared(value: Any, playerX: Int, playerY: Int, playerZ: Int): Long {
        val x: Int; val y: Int; val z: Int
        when (value) {
            is com.spaimic.nbttool.chunk.DiamondCoordinate -> { x = value.x; y = value.y; z = value.z }
            is com.spaimic.nbttool.chunk.OreCoordinate -> { x = value.x; y = value.y; z = value.z }
            else -> return Long.MAX_VALUE
        }
        val dx = x.toLong() - playerX.toLong()
        val dy = y.toLong() - playerY.toLong()
        val dz = z.toLong() - playerZ.toLong()
        return dx * dx + dy * dy + dz * dz
    }

    private fun showChunkDialog(worldX: Int, worldZ: Int) {
        val chunkX = Math.floorDiv(worldX, 16)
        val chunkZ = Math.floorDiv(worldZ, 16)
        val clipboardEntries = ChunkClipboard.list(this)
        val options = arrayListOf(
            "Chunk Information",
            "🟦 Start Selection",
        )
        options.addAll(listOf(
            "Show Entities",
            "Show Block Entities"
        ))
        if (ExperimentMode.isEnabled(this)) {
            options.addAll(listOf(
                "Copy Chunk",
                "Paste Chunk",
                "Delete Chunk"
            ))
        }
        options.add("Terrain Editor")
        if (mapView.selection != null) {
            options.add("📸 Selection: Take Picture of Map")
            if (ExperimentMode.isEnabled(this)) {
                options.add("🗑️ Selection: Delete Area")
            }
            options.add("❌ Clear Selection")
        }
        AlertDialog.Builder(this)
            .setTitle("Chunk ($chunkX, $chunkZ)")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Chunk Information" -> showChunkInformation(chunkX, chunkZ)
                    "🟦 Start Selection" -> startSelection(worldX, worldZ)
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
        Thread {
            try {
                runOnUiThread { Toast.makeText(this, "Saving map picture…", Toast.LENGTH_LONG).show() }
                val displayName = "NBTTool_Map_${s.minX}_${s.minZ}_${s.maxX}_${s.maxZ}_${ppb}px.png"
                val destination = savePngToGallery(displayName) { output ->
                    MapSelectionExporter.export(bridge, s, selectedDbDimension(), ppb, output) { }
                }
                runOnUiThread {
                    Toast.makeText(this, "Map picture saved to $destination.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to save map picture", e) }
            }
        }.start()
    }

    private fun promptWholeMapPicture() {
        val bridge = LevelDbSession.bridge ?: run {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        if (wholeMapExportRunning.get()) {
            Toast.makeText(this, "An entire-map export is already running. Please wait for it to finish.", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("🌍 Take Entire Map Picture")
            .setMessage("First, NBT Tool will analyze the world and calculate the largest safe pixels-per-block value. The export is limited to less than 1 billion pixels.")
            .setPositiveButton("Analyze") { _, _ -> analyzeWholeMapPicture(bridge) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun analyzeWholeMapPicture(bridge: LevelDbBridge) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Analyzing world map…")
            .setMessage("Finding the complete chunk bounds. Please wait.")
            .setView(ProgressBar(this))
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        Thread {
            try {
                val bounds = WholeMapExporter.findBounds(bridge, selectedDbDimension())
                    ?: throw IllegalStateException("No chunk data was found in this dimension.")
                val maxPpb = WholeMapExporter.maximumPixelsPerBlock(bounds)
                runOnUiThread {
                    dialog.dismiss()
                    if (maxPpb < WholeMapExporter.MIN_PIXELS_PER_BLOCK) {
                        AlertDialog.Builder(this)
                            .setTitle("Map is too large to export")
                            .setMessage(
                                "The map is ${bounds.widthBlocks} × ${bounds.heightBlocks} blocks. " +
                                "Even 2 px/block would exceed the ${WholeMapExporter.MAX_TOTAL_PIXELS - 1} pixel limit."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        return@runOnUiThread
                    }

                    val scale = SeekBar(this).apply {
                        max = maxPpb - WholeMapExporter.MIN_PIXELS_PER_BLOCK
                        progress = max - ((maxPpb - WholeMapExporter.MIN_PIXELS_PER_BLOCK) / 4)
                            .coerceAtLeast(0)
                        isEnabled = maxPpb > WholeMapExporter.MIN_PIXELS_PER_BLOCK
                    }
                    val valueText = TextView(this).apply {
                        textSize = 18f
                        gravity = Gravity.CENTER
                        setPadding(0, 16, 0, 16)
                    }
                    fun updateValue() {
                        val ppb = WholeMapExporter.MIN_PIXELS_PER_BLOCK + scale.progress
                        val pixels = WholeMapExporter.totalPixels(bounds, ppb)
                        valueText.text = "$ppb px/block\n${bounds.widthBlocks * ppb} × ${bounds.heightBlocks * ppb} pixels\n${pixels} total pixels"
                    }
                    scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = updateValue()
                        override fun onStartTrackingTouch(seekBar: SeekBar) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar) {}
                    })
                    updateValue()
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 8, 24, 8)
                        addView(valueText)
                        addView(scale)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Entire Map Picture")
                        .setMessage(
                            "Bounds: X ${bounds.minX}..${bounds.maxX}, Z ${bounds.minZ}..${bounds.maxZ}\n" +
                            "World area: ${bounds.widthBlocks} × ${bounds.heightBlocks} blocks\n" +
                            "Maximum allowed: $maxPpb px/block"
                        )
                        .setView(box)
                        .setPositiveButton("Preview") { _, _ ->
                            val ppb = WholeMapExporter.MIN_PIXELS_PER_BLOCK + scale.progress
                            previewWholeMapPicture(bounds, ppb)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Throwable) {
                runOnUiThread { dialog.dismiss(); showErrorDialog(this, "Failed to analyze entire map", e) }
            }
        }.start()
    }

    private fun previewWholeMapPicture(bounds: WholeMapBounds, ppb: Int) {
        val bridge = LevelDbSession.bridge ?: return
        if (!wholeMapExportRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "An entire-map export is already running. Please wait for it to finish.", Toast.LENGTH_LONG).show()
            return
        }
        preparedWholeMapPng?.let { try { it.delete() } catch (_: Throwable) {} }
        preparedWholeMapPng = null

        val cancelled = AtomicBoolean(false)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val lastProgress = AtomicInteger(0)
        val progressText = TextView(this).apply {
            text = "Rendering map: 0%"
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        val progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(progress)
            addView(progressText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rendering entire map preview…")
            .setMessage("The full-resolution PNG is prepared during the same render, so Save PNG will not render the world a second time.")
            .setView(progressBox)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        Thread {
            var temp: File? = null
            try {
                val cacheDir = File(cacheDir, "whole_map_exports")
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    throw java.io.IOException("Unable to create temporary export directory")
                }
                temp = File.createTempFile("whole_map_${System.currentTimeMillis()}_", ".png", cacheDir)
                val preview = java.io.FileOutputStream(temp).use { output ->
                    WholeMapExporter.previewAndPreparePng(
                        bridge,
                        bounds,
                        selectedDbDimension(),
                        ppb,
                        output,
                        onProgress = { value ->
                            runOnUiThread {
                                val safe = lastProgress.updateAndGet { old -> maxOf(old, value.coerceIn(0, 100)) }
                                progress.progress = safe
                                progressText.text = "Rendering map: $safe%"
                            }
                        },
                        isCancelled = { cancelled.get() }
                    )
                }
                if (cancelled.get()) {
                    try { temp.delete() } catch (_: Throwable) {}
                    return@Thread
                }
                preparedWholeMapPng = temp
                val dimName = when (selectedDbDimension()) { 1 -> "Nether"; 2 -> "TheEnd"; else -> "Overworld" }
                val displayName = "NBTTool_${dimName}_EntireMap_${ppb}px.png"
                runOnUiThread {
                    dialog.dismiss()
                    val image = ImageView(this).apply {
                        setImageBitmap(preview)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    val previewScroll = ScrollView(this).apply {
                        isFillViewport = false
                        addView(
                            image,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            )
                        )
                    }
                    val maxPreviewHeight = (resources.displayMetrics.heightPixels * 0.52f)
                        .toInt().coerceAtLeast(360)
                    val previewBox = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 8, 24, 8)
                        addView(
                            TextView(this@MapViewerActivity).apply {
                                text =
                                    "${bounds.widthBlocks} × ${bounds.heightBlocks} blocks • $ppb px/block\n" +
                                    "${bounds.widthBlocks * ppb} × ${bounds.heightBlocks * ppb} pixels\n" +
                                    "Full PNG is already prepared. Preview is scaled down for display."
                                setPadding(0, 0, 0, 12)
                            },
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                        addView(
                            previewScroll,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                maxPreviewHeight
                            )
                        )
                    }
                    AlertDialog.Builder(this)
                        .setCancelable(false)
                        .setTitle("Entire Map Picture Preview")
                        .setView(previewBox)
                        .setPositiveButton("Save PNG") { _, _ -> savePreparedWholeMapPicture(temp, displayName) }
                        .setNegativeButton("Cancel") { _, _ ->
                            try { temp.delete() } catch (_: Throwable) {}
                            preparedWholeMapPng = null
                            wholeMapExportRunning.set(false)
                        }
                        .show()
                }
            } catch (_: CancellationException) {
                temp?.let { try { it.delete() } catch (_: Throwable) {} }
                runOnUiThread {
                    dialog.dismiss()
                    wholeMapExportRunning.set(false)
                    Toast.makeText(this, "Entire map preview cancelled. No incomplete PNG was kept.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                temp?.let { try { it.delete() } catch (_: Throwable) {} }
                runOnUiThread {
                    dialog.dismiss()
                    wholeMapExportRunning.set(false)
                    showErrorDialog(this, "Failed to render entire map preview", e)
                }
            }
        }.start()
    }

    private fun savePreparedWholeMapPicture(temp: File?, displayName: String) {
        if (temp == null || !temp.exists()) {
            wholeMapExportRunning.set(false)
            Toast.makeText(this, "Prepared PNG is no longer available. Please render the map again.", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val destination = savePngToGallery(displayName) { output ->
                    temp.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
                }
                try { temp.delete() } catch (_: Throwable) {}
                preparedWholeMapPng = null
                runOnUiThread {
                    wholeMapExportRunning.set(false)
                    Toast.makeText(this, "Entire map picture saved to $destination.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    wholeMapExportRunning.set(false)
                    showErrorDialog(this, "Failed to save entire map picture", e)
                }
            }
        }.start()
    }

    private fun saveWholeMapPicture(bounds: WholeMapBounds, ppb: Int) {
        val bridge = LevelDbSession.bridge ?: return
        if (!wholeMapExportRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "An entire-map export is already running.", Toast.LENGTH_LONG).show()
            return
        }
        val cancelled = AtomicBoolean(false)
        val dimName = when (selectedDbDimension()) { 1 -> "Nether"; 2 -> "TheEnd"; else -> "Overworld" }
        val displayName = "NBTTool_${dimName}_EntireMap_${ppb}px.png"
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val lastProgress = AtomicInteger(0)
        val progressText = TextView(this).apply {
            text = "Generating PNG: 0%"
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        val progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(progress)
            addView(progressText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Saving entire map…")
            .setMessage("Rendering the selected full-resolution map directly into PNG. The display preview is downscaled and is not used as the final image.")
            .setView(progressBox)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        Thread {
            try {
                val destination = savePngToGallery(displayName) { output ->
                    WholeMapExporter.export(
                        bridge,
                        bounds,
                        selectedDbDimension(),
                        ppb,
                        output,
                        onProgress = { value ->
                            runOnUiThread {
                                val safe = lastProgress.updateAndGet { old -> maxOf(old, value.coerceIn(0, 99)) }
                                progress.progress = safe
                                progressText.text = "Generating PNG: $safe%"
                            }
                        },
                        isCancelled = { cancelled.get() }
                    )
                }
                runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    lastProgress.set(100)
                    progress.progress = 100
                    progressText.text = "Complete: 100%"
                    dialog.dismiss()
                    Toast.makeText(this, "Entire map picture saved to $destination.", Toast.LENGTH_LONG).show()
                }
            } catch (_: CancellationException) {
                runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(this, "Entire map picture cancelled. No incomplete PNG was kept.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                runOnUiThread { dialog.dismiss(); showErrorDialog(this, "Failed to save entire map picture", e) }
            } finally {
                wholeMapExportRunning.set(false)
            }
        }.start()
    }

    /**
     * Writes a PNG through MediaStore when available, with Download first and
     * Pictures as a fallback. The producer owns/finishes the supplied stream.
     * On failure, incomplete/pending MediaStore entries are removed.
     */
    private fun savePngToGallery(
        displayName: String,
        producer: (java.io.OutputStream) -> Unit
    ): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val resolver = contentResolver

            fun attempt(collection: android.net.Uri, relativePath: String): String? {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                try {
                    val output = resolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Unable to open MediaStore output stream")
                    output.use { producer(it) }

                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    if (resolver.update(uri, done, null, null) <= 0) {
                        throw java.io.IOException("Unable to finalize MediaStore image")
                    }
                    return relativePath + displayName
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
            }

            try {
                return attempt(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_DOWNLOADS + "/"
                ) ?: throw java.io.IOException("Unable to create image in Download")
            } catch (downloadError: Throwable) {
                try {
                    return attempt(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        Environment.DIRECTORY_PICTURES + "/"
                    ) ?: throw java.io.IOException("Unable to create image in Pictures")
                } catch (picturesError: Throwable) {
                    throw java.io.IOException(
                        "Could not save PNG to Download or Pictures",
                        picturesError
                    )
                }
            }
        }

        // Legacy Android: use normal files and explicitly request media scanning.
        val directories = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        )
        var lastError: Throwable? = null
        for (directory in directories) {
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw java.io.IOException("Unable to create ${directory.absolutePath}")
                }
                val file = File(directory, displayName)
                file.outputStream().use { producer(it) }
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("image/png"),
                    null
                )
                return file.absolutePath
            } catch (t: Throwable) {
                lastError = t
                // Never leave a partial PNG behind.
                try { File(directory, displayName).delete() } catch (_: Throwable) {}
            }
        }
        throw java.io.IOException("Could not save PNG to Download or Pictures", lastError)
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        val scroll = ScrollView(this).apply {
            addView(content)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose chunk to paste")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()

        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 12, 8, 12)
                isClickable = true
                isFocusable = true
            }

            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(112, 112).apply {
                    rightMargin = 18
                }
                scaleType = ImageView.ScaleType.FIT_XY
                val bytes = entry.previewPng
                if (bytes != null && bytes.isNotEmpty()) {
                    setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                } else {
                    setImageResource(R.drawable.question_mark)
                }
            }
            row.addView(preview)

            val text = TextView(this).apply {
                text = "${if (entry.dimension == 1) "Nether" else if (entry.dimension == 2) "The End" else "Overworld"}\n" +
                    "Chunk (${entry.chunkX}, ${entry.chunkZ})\n" +
                    "${entry.records.size} LevelDB records"
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(text)

            row.setOnClickListener {
                dialog.dismiss()
                promptPasteChunk(targetX, targetZ, entry)
            }
            content.addView(row)
            content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(0x33000000)
            })
        }

        dialog.show()
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

    private fun gameModeName(mode: Int): String = when (mode) {
        0 -> "Survival (0)"
        1 -> "Creative (1)"
        2 -> "Adventure (2)"
        3 -> "Spectator (3)"
        4 -> "Hardcore (4)"
        5 -> "Default Gamemode: ${defaultGameModeName()}"
        else -> "Unknown ($mode)"
    }

    private fun defaultGameModeName(): String {
        return try {
            val dat = NbtIO.readLevelDat(File(worldDir, "level.dat").readBytes())
            val mode = (dat.root.entries["GameType"] as? NbtTag.Int)?.value
                ?: (dat.root.entries["GameType"] as? NbtTag.Byte)?.value?.toInt()
                ?: return "Unknown"
            when (mode) {
                0 -> "Survival (0)"
                1 -> "Creative (1)"
                2 -> "Adventure (2)"
                3 -> "Spectator (3)"
                4 -> "Hardcore (4)"
                else -> "Unknown ($mode)"
            }
        } catch (_: Exception) {
            "Unknown"
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun drawerText(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun drawerRow(icon: Int, title: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(3), dp(8), dp(3))
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.WHITE)
            setOnClickListener { action(); drawerLayout.closeDrawer(Gravity.LEFT) }
        }
        val image = ImageView(this).apply {
            setImageResource(icon)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(image, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(drawerText(title), LinearLayout.LayoutParams(0, dp(52), 1f))
        return row
    }

    private fun drawerCategory(title: String): TextView = drawerText(title, 13f).apply {
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.DKGRAY)
        setPadding(dp(18), dp(18), dp(18), dp(6))
    }

    private fun buildToolsDrawer(): View {
        val scroll = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(18))
        }

        val icon = ImageView(this).apply {
            val file = LocalWorldStore.worldIconFile(worldDir)
            if (file != null) setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            else setImageResource(R.drawable.map)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        panel.addView(icon, LinearLayout.LayoutParams(-1, dp(180)))
        panel.addView(drawerText(LocalWorldStore.levelName(worldDir), 21f))

        val seed = try {
            val dat = NbtIO.readLevelDat(File(worldDir, "level.dat").readBytes())
            (dat.root.entries["RandomSeed"] as? NbtTag.Long)?.value?.toString()
                ?: (dat.root.entries["RandomSeed"] as? NbtTag.Int)?.value?.toString()
                ?: "Unknown"
        } catch (_: Exception) { "Unknown" }
        panel.addView(drawerText("World Seed: $seed", 14f))
        drawerGameModeText = drawerText("Player Gamemode: Loading…", 14f)
        panel.addView(drawerGameModeText)

        panel.addView(drawerCategory("World Editor"))
        panel.addView(drawerRow(R.drawable.player, "Player Editor") { openLocalPlayerLazily() })
        panel.addView(drawerRow(R.drawable.multiplayer, "Multiplayer Editor") { showKeyPickerLazily("Multiplayer Players") { it.startsWith("player_server_") } })
        panel.addView(drawerRow(R.drawable.world_setting, "World Editor") { openLevelDatEditor() })
        panel.addView(drawerRow(R.drawable.mcstructure, "MCStructure Editor") { showKeyPickerLazily("MCStructures") { it.startsWith("structuretemplate_") } })
        panel.addView(drawerRow(R.drawable.tickingarea, "Ticking Areas Editor") { showKeyPickerLazily("Ticking Areas") { it.startsWith("tickingarea_") } })
        panel.addView(drawerRow(R.drawable.search, "Village Editor") { showKeyPickerLazily("Village Data") { it.startsWith("VILLAGE_Overworld_") } })

        panel.addView(drawerCategory("Map Options"))
        gridToggleText = drawerText(if (mapView.showGrid) "Hide Grid" else "Show Grid")
        panel.addView(drawerOptionRow(R.drawable.grid, gridToggleText) {
            mapView.showGrid = !mapView.showGrid
            gridToggleText.text = if (mapView.showGrid) "Hide Grid" else "Show Grid"
        })
        entityToggleText = drawerText(if (mapView.showEntityIcons) "Hide Entities on the Map" else "Show Entities on the Map")
        panel.addView(drawerOptionRow(R.drawable.toggle, entityToggleText) {
            mapView.showEntityIcons = !mapView.showEntityIcons
            entityToggleText.text = if (mapView.showEntityIcons) "Hide Entities on the Map" else "Show Entities on the Map"
        })
        tileEntityToggleText = drawerText(if (mapView.showTileEntityIcons) "Hide Tile Entities on the Map" else "Show Tile Entities on the Map")
        panel.addView(drawerOptionRow(R.drawable.toggle, tileEntityToggleText) {
            mapView.showTileEntityIcons = !mapView.showTileEntityIcons
            tileEntityToggleText.text = if (mapView.showTileEntityIcons) "Hide Tile Entities on the Map" else "Show Tile Entities on the Map"
        })

        panel.addView(drawerCategory("Clipboard"))
        panel.addView(drawerRow(R.drawable.clipboard, "NBT Clipboard") { startActivity(Intent(this, ClipboardActivity::class.java)) })
        panel.addView(drawerRow(R.drawable.clipboard, "Chunk Clipboard") { startActivity(Intent(this, ChunkClipboardActivity::class.java)) })

        panel.addView(drawerCategory("Other NBTs"))
        panel.addView(drawerRow(R.drawable.other_nbt, "Overworld NBT Data") { showExactKey("Overworld NBT Data", "Overworld") })
        panel.addView(drawerRow(R.drawable.other_nbt, "Nether NBT Data") { showExactKey("Nether NBT Data", "Nether") })
        panel.addView(drawerRow(R.drawable.other_nbt, "The End NBT Data") { showExactKey("The End NBT Data", "TheEnd") })

        panel.addView(drawerCategory("Advanced"))
        panel.addView(drawerRow(R.drawable.search, "Open LevelDB Key by Name") { promptOpenLevelDbKeyByName() })
        panel.addView(drawerRow(R.drawable.other_nbt, "Browse Extra LevelDB Keys") { openAllKeysBrowser() })

        scroll.addView(panel)
        return scroll
    }

    private fun drawerOptionRow(icon: Int, label: TextView, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(3), dp(8), dp(3))
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.WHITE)
            setOnClickListener { action(); drawerLayout.closeDrawer(Gravity.LEFT) }
        }
        val image = ImageView(this).apply {
            setImageResource(icon)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(image, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(label, LinearLayout.LayoutParams(0, dp(52), 1f))
        return row
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
