package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbSession
import com.spaimic.nbttool.chunk.McStructurePlacer
import com.spaimic.nbttool.chunk.ChunkScanner
import com.spaimic.nbttool.worlds.LocalWorldStore

/**
 * Build 208 first direct MCStructure placement prototype.
 * It is deliberately behind Experiment Mode and creates a complete world backup before writing.
 */
class McStructurePlacementActivity : AppCompatActivity() {
    private lateinit var xField: EditText
    private lateinit var yField: EditText
    private lateinit var zField: EditText
    private lateinit var dimensionSpinner: Spinner
    private lateinit var summary: TextView
    private lateinit var placementPreview: McStructurePlacementPreviewView
    private lateinit var coordinateLabel: TextView
    private var previewLoadGeneration = 0
    private var currentPlayerPosition: Triple<Int, Int, Int>? = null
    private var analysis: McStructureAnalysis? = null
    private var displayName: String = "MCStructure"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "MCStructure"
        title = "Place: $displayName"

        if (!ExperimentMode.isEnabled(this)) {
            Toast.makeText(this, "Experiment Mode is required for structure placement.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "🏗️ MCStructure Placement Preview"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(TextView(this).apply {
            text = "Choose the placement origin below. The 3D preview shows the nearby terrain/chunks and a gold bounding box for the MCStructure. Drag the preview to rotate and pinch to zoom; use the eight buttons to move the structure without moving the camera."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(10))
        })

        placementPreview = McStructurePlacementPreviewView(this)
        content.addView(placementPreview, LinearLayout.LayoutParams(-1, dp(360)))

        coordinateLabel = TextView(this).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, dp(6))
        }
        content.addView(coordinateLabel)

        val moveGrid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = true
        }
        fun moveButton(label: String, dx: Int = 0, dy: Int = 0, dz: Int = 0) {
            val b = Button(this).apply {
                text = label
                textSize = 17f
                setOnClickListener {
                    movePlacement(dx, dy, dz)
                }
            }
            moveGrid.addView(b, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        moveButton("⬆️⚡", dy = 5)
        moveButton("⬆️", dy = 1)
        moveButton("⬇️", dy = -1)
        moveButton("⬇️⚡", dy = -5)
        moveButton("⬅️⚡", dx = -5)
        moveButton("⬅️", dx = -1)
        moveButton("➡️", dx = 1)
        moveButton("➡️⚡", dx = 5)
        content.addView(moveGrid, LinearLayout.LayoutParams(-1, dp(112)))

        val worldButton = Button(this).apply {
            text = "Use Current Player Position"
            setOnClickListener { loadPlayerPositionIntoFields() }
        }
        content.addView(worldButton, LinearLayout.LayoutParams(-1, dp(48)))

        xField = numberField(content, "World X")
        yField = numberField(content, "World Y")
        zField = numberField(content, "World Z")

        val coordinateWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (::placementPreview.isInitialized) refreshPlacementPreview(forceTerrainReload = false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        xField.addTextChangedListener(coordinateWatcher)
        yField.addTextChangedListener(coordinateWatcher)
        zField.addTextChangedListener(coordinateWatcher)

        content.addView(TextView(this).apply { text = "Dimension"; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, dp(8), 0, dp(4)) })
        dimensionSpinner = Spinner(this)
        dimensionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Overworld", "Nether", "The End"))
        content.addView(dimensionSpinner, LinearLayout.LayoutParams(-1, dp(50)))

        val previewRefresh = Button(this).apply {
            text = "Refresh 3D Placement Preview"
            setOnClickListener { refreshPlacementPreview(forceTerrainReload = true) }
        }
        content.addView(previewRefresh, LinearLayout.LayoutParams(-1, dp(48)))

        dimensionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshPlacementPreview(forceTerrainReload = true)
            }
        }

        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, dp(14), 0, dp(14))
        }
        content.addView(summary)

        val placeButton = Button(this).apply {
            text = "PLACE STRUCTURE"
            textSize = 16f
            setOnClickListener { confirmPlacement() }
        }
        content.addView(placeButton, LinearLayout.LayoutParams(-1, dp(54)))

        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        content.addView(cancel, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        setContentView(root)
        loadStructure()
        loadPlayerPositionIntoFields()
        refreshPlacementPreview(forceTerrainReload = true)
    }

    private fun numberField(parent: LinearLayout, label: String): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.BLACK); setPadding(0, dp(8), 0, dp(4)) })
        val field = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setText("0")
            setTextColor(Color.BLACK)
        }
        parent.addView(field, LinearLayout.LayoutParams(-1, dp(50)))
        return field
    }

    private fun loadStructure() {
        val bridge = LevelDbSession.bridge
        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)
        if (sourceUri.isNullOrBlank() && bridge == null) {
            summary.text = "World database isn't open."
            return
        }
        Thread {
            try {
                val bytes = if (!sourceUri.isNullOrBlank()) {
                    contentResolver.openInputStream(android.net.Uri.parse(sourceUri))?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't open the imported .mcstructure file.")
                } else {
                    val rawKey = LevelDbSession.keyBytesByName[displayName] ?: displayName.toByteArray(Charsets.UTF_8)
                    bridge!!.read(rawKey) ?: throw IllegalStateException("The MCStructure record no longer exists.")
                }
                val decoded = McStructureDecoder.decode(NbtIO.readRootCompoundNoHeader(bytes))
                analysis = decoded
                runOnUiThread {
                    placementPreview.setStructure(decoded)
                    summary.text = buildString {
                        append("Structure: $displayName\n")
                        append("Size: ${decoded.sizeX} × ${decoded.sizeY} × ${decoded.sizeZ}\n")
                        append("Blocks: ${decoded.blocks.size}\n")
                        append("Entities in structure: ${decoded.entities?.items?.size ?: 0}\n")
                        append("Block-position-data entries: ${decoded.blockPositionData?.entries?.size ?: 0}\n")
                        append("\nPrototype notes:\n")
                        append("• Blocks preserve the full palette compounds, including states and version.\n")
                        append("• Structure entities are copied to world actor storage with fresh IDs.\n")
                        append("• Existing terrain chunks are edited; entirely new terrain chunks are not created.\n")
                        append("• Block-position-data/block entities are not written yet.\n")
                    }
                    refreshPlacementPreview(forceTerrainReload = true)
                }
            } catch (e: Throwable) {
                runOnUiThread { summary.text = "Couldn't load structure: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun loadPlayerPositionIntoFields() {
        Thread {
            try {
                val bridge = LevelDbSession.bridge ?: throw IllegalStateException("World database isn't open.")
                val raw = bridge.read("~local_player".toByteArray(Charsets.US_ASCII))
                    ?: throw IllegalStateException("No ~local_player data exists.")
                val root = NbtIO.readRootCompoundNoHeader(raw)
                val pos = root.entries["Pos"] as? NbtTag.ListTag
                    ?: throw IllegalStateException("Player position was not found in ~local_player.")
                val x = numeric(pos.items.getOrNull(0)) ?: throw IllegalStateException("Invalid player X in ~local_player.")
                val y = numeric(pos.items.getOrNull(1)) ?: throw IllegalStateException("Invalid player Y in ~local_player.")
                val z = numeric(pos.items.getOrNull(2)) ?: throw IllegalStateException("Invalid player Z in ~local_player.")
                val dim = when (val d = root.entries["Dimension"] ?: root.entries["DimensionId"] ?: root.entries["dimension"]) {
                    is NbtTag.Byte -> d.value.toInt()
                    is NbtTag.Short -> d.value.toInt()
                    is NbtTag.Int -> d.value
                    is NbtTag.Long -> d.value.toInt()
                    else -> 0
                }.coerceIn(0, 2)
                val player = Triple(Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt())
                runOnUiThread {
                    currentPlayerPosition = player
                    xField.setText(player.first.toString())
                    yField.setText(player.second.toString())
                    zField.setText(player.third.toString())
                    dimensionSpinner.setSelection(dim)
                    coordinateLabel.text = "Placement coordinates: X=${player.first}  Y=${player.second}  Z=${player.third}"
                    summary.text = "Current player position loaded from ~local_player: (${player.first}, ${player.second}, ${player.third})"
                    refreshPlacementPreview(forceTerrainReload = true)
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, e.message ?: "Couldn't read player position.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun movePlacement(dx: Int, dy: Int, dz: Int) {
        val x = xField.text.toString().trim().toIntOrNull() ?: return
        val y = yField.text.toString().trim().toIntOrNull() ?: return
        val z = zField.text.toString().trim().toIntOrNull() ?: return
        xField.setText((x + dx).toString())
        yField.setText((y + dy).toString())
        zField.setText((z + dz).toString())
        coordinateLabel.text = "Placement coordinates: X=${x + dx}  Y=${y + dy}  Z=${z + dz}"
        refreshPlacementPreview(forceTerrainReload = dx != 0 || dz != 0)
    }

    private fun refreshPlacementPreview(forceTerrainReload: Boolean) {
        if (!::placementPreview.isInitialized || !::xField.isInitialized || !::yField.isInitialized || !::zField.isInitialized || !::dimensionSpinner.isInitialized) return
        val structure = analysis ?: return
        val x = xField.text.toString().trim().toIntOrNull() ?: return
        val y = yField.text.toString().trim().toIntOrNull() ?: return
        val z = zField.text.toString().trim().toIntOrNull() ?: return
        val dim = dimensionSpinner.selectedItemPosition
        placementPreview.setStructure(structure)
        placementPreview.setPlacement(x, y, z, dim)
        coordinateLabel.text = "Placement coordinates: X=$x  Y=$y  Z=$z"

        val minCx = Math.floorDiv(x, 16)
        val maxCx = Math.floorDiv(x + structure.sizeX - 1, 16)
        val minCz = Math.floorDiv(z, 16)
        val maxCz = Math.floorDiv(z + structure.sizeZ - 1, 16)
        val margin = 1
        val wantedMinCx = minCx - margin
        val wantedMaxCx = maxCx + margin
        val wantedMinCz = minCz - margin
        val wantedMaxCz = maxCz + margin
        if (!forceTerrainReload && previewRange == PreviewRange(wantedMinCx, wantedMaxCx, wantedMinCz, wantedMaxCz, dim)) return

        previewRange = PreviewRange(wantedMinCx, wantedMaxCx, wantedMinCz, wantedMaxCz, dim)
        val generation = ++previewLoadGeneration
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                val terrain = LinkedHashMap<Pair<Int, Int>, Array<Array<com.spaimic.nbttool.chunk.ColumnInfo>>>()
                for (cx in wantedMinCx..wantedMaxCx) {
                    for (cz in wantedMinCz..wantedMaxCz) {
                        if (generation != previewLoadGeneration) return@Thread
                        val subchunks = bridge.readSubchunks(cx, cz, dim)
                        val grid = ChunkScanner.loadChunkFromRawDataFast(subchunks, null, dim)
                        if (grid != null) terrain[cx to cz] = grid
                    }
                }
                runOnUiThread {
                    if (generation == previewLoadGeneration && !isFinishing && !isDestroyed) placementPreview.setColumns(terrain)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (generation == previewLoadGeneration && !isFinishing && !isDestroyed) Toast.makeText(this, "3D terrain preview failed: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private data class PreviewRange(val minCx: Int, val maxCx: Int, val minCz: Int, val maxCz: Int, val dimension: Int)
    private var previewRange: PreviewRange? = null

    private fun confirmPlacement() {
        val decoded = analysis ?: run {
            Toast.makeText(this, "MCStructure is still loading.", Toast.LENGTH_LONG).show(); return
        }
        val x = xField.text.toString().trim().toIntOrNull()
        val y = yField.text.toString().trim().toIntOrNull()
        val z = zField.text.toString().trim().toIntOrNull()
        if (x == null || y == null || z == null) {
            Toast.makeText(this, "Enter valid integer coordinates.", Toast.LENGTH_LONG).show(); return
        }
        val dim = dimensionSpinner.selectedItemPosition
        AlertDialog.Builder(this)
            .setTitle("Place MCStructure?")
            .setMessage("Place $displayName at ($x, $y, $z) in ${dimensionName(dim)}?\n\n${decoded.blocks.size} blocks will be written. ${decoded.entities?.items?.size ?: 0} structure entities will be copied with fresh actor IDs. A full world backup will be created first.\n\nPlacement uses the selected preview coordinates. Block entities are not written yet.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create Backup & Place") { _, _ -> performPlacement(decoded, x, y, z, dim) }
            .show()
    }

    private fun performPlacement(decoded: McStructureAnalysis, x: Int, y: Int, z: Int, dim: Int) {
        val bridge = LevelDbSession.bridge
        val world = LevelDbSession.worldDir
        if (bridge == null || world == null) {
            Toast.makeText(this, "World database isn't open.", Toast.LENGTH_LONG).show(); return
        }
        val dialog = ProgressDialog(this).apply {
            setTitle("Placing MCStructure")
            setMessage("Creating world backup…")
            setCancelable(false)
            show()
        }
        Thread {
            try {
                val backup = LocalWorldStore.createBackup(this, world)
                runOnUiThread { dialog.setMessage("Writing structure blocks and entities…") }
                val result = McStructurePlacer.place(bridge, decoded, x, y, z, dim, backup.absolutePath)
                runOnUiThread {
                    dialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("MCStructure Placed")
                        .setMessage(buildString {
                            append("Blocks placed: ${result.blocksPlaced}\n")
                            append("Chunks changed: ${result.chunksChanged}\n")
                            append("Subchunks changed: ${result.subchunksChanged}\n")
                            append("Entities placed: ${result.entitiesPlaced}\n")
                            append("Block entities pending: ${result.blockEntitiesPending}\n\n")
                            append("Backup:\n${result.backupPath}")
                        })
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dialog.dismiss()
                    AlertDialog.Builder(this).setTitle("Placement Failed").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun numeric(tag: NbtTag?): Double? = when (tag) {
        is NbtTag.Byte -> tag.value.toDouble()
        is NbtTag.Short -> tag.value.toDouble()
        is NbtTag.Int -> tag.value.toDouble()
        is NbtTag.Long -> tag.value.toDouble()
        is NbtTag.Float -> tag.value.toDouble()
        is NbtTag.Double -> tag.value
        else -> null
    }

    private fun dimensionName(d: Int) = when (d) { 1 -> "Nether"; 2 -> "The End"; else -> "Overworld" }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_SOURCE_URI = "source_uri"
    }
}
