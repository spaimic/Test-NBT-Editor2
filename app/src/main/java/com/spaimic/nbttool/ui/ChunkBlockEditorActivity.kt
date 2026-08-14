package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.ChunkKeys
import com.spaimic.nbttool.chunk.SubChunkEditor
import com.spaimic.nbttool.chunk.SubChunkParser
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import java.io.File
import java.util.ArrayDeque

class ChunkBlockEditorActivity : AppCompatActivity() {
    private lateinit var worldDir: File
    private var chunkX = 0
    private var chunkZ = 0
    private var dimension = 0
    private var bridge: LevelDbBridge? = null
    private var ownsBridge = false

    // Build 138: keep the editor lazy. Do not materialize 16*16*385 block
    // objects just to open the 256-column grid.
    private val original = HashMap<Int, ByteArray>()
    private val highest = IntArray(256) { Int.MIN_VALUE }
    private val changes = HashMap<Triple<Int, Int, Int>, BlockChange>()

    private var selectedX = -1
    private var selectedZ = -1
    private var selectedY = Int.MIN_VALUE
    private var dirty = false
    private var hideAir = false
    private var columnScrollY = 0
    private val undoStack = ArrayDeque<EditAction>()
    private val redoStack = ArrayDeque<EditAction>()

    private data class BlockChange(val pos: Triple<Int, Int, Int>, val before: String, val after: String)
    private data class EditAction(val changes: List<BlockChange>)

    // Only the currently opened BlockChunk is decoded into this map.
    private val columnBlocks = HashMap<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "Chunk Block Editor"
        chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        loadChunkMetadata()
    }

    private fun loadChunkMetadata() {
        Thread {
            try {
                val sessionBridge = LevelDbSession.bridge
                val b = if (sessionBridge != null && LevelDbSession.worldDir?.canonicalFile == worldDir.canonicalFile) {
                    ownsBridge = false
                    sessionBridge
                } else {
                    ownsBridge = true
                    LevelDbBridge.open(worldDir)
                }
                bridge = b

                val subs = b.readSubchunks(chunkX, chunkZ, dimension)
                original.clear()
                java.util.Arrays.fill(highest, Int.MIN_VALUE)

                // Parse one subchunk at a time, calculate only the highest block
                // for each X/Z column, then release its 16^3 temporary array.
                for ((sy, bytes) in subs) {
                    original[sy] = bytes.copyOf()
                    val parsed = SubChunkParser.parse(bytes)
                    if (parsed is SubChunkParser.ParseResult.Success) {
                        for (x in 0..15) for (z in 0..15) {
                            var top = Int.MIN_VALUE
                            for (y in 15 downTo 0) {
                                val name = parsed.blocks[x][z][y]
                                if (!isAir(name)) {
                                    top = sy * 16 + y
                                    break
                                }
                            }
                            if (top != Int.MIN_VALUE) {
                                val idx = z * 16 + x
                                if (top > highest[idx]) highest[idx] = top
                            }
                        }
                    }
                }
                runOnUiThread { showGrid() }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to load chunk", e) }
            }
        }.start()
    }

    private fun showGrid() {
        val outer = HorizontalScrollView(this).apply { isFillViewport = false }
        val vertical = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 16
            rowCount = 16
            setPadding(16, 16, 16, 16)
        }
        for (z in 0..15) for (x in 0..15) {
            val top = highest[z * 16 + x]
            val button = Button(this).apply {
                text = "$x,$z\n${if (top == Int.MIN_VALUE) "Air" else "Y $top"}"
                textSize = 11f
                setPadding(2, 2, 2, 2)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 150
                    height = 90
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener { openColumn(x, z) }
            }
            grid.addView(button)
        }
        vertical.addView(grid)
        vertical.addView(Button(this).apply {
            text = "View Entire Chunk in 3D"
            setOnClickListener { open3DViewer(true) }
        })
        outer.addView(vertical)
        setContentView(outer)
    }

    private fun isAir(name: String): Boolean =
        name == "minecraft:air" || name == "air"

    private fun loadColumn(x: Int, z: Int) {
        columnBlocks.clear()
        for ((sy, bytes) in original) {
            val parsed = SubChunkParser.parse(bytes)
            if (parsed is SubChunkParser.ParseResult.Success) {
                for (y in 0..15) {
                    val worldY = sy * 16 + y
                    if (worldY in -64..320) {
                        columnBlocks[worldY] = parsed.blocks[x][z][y]
                    }
                }
            }
        }
        // Apply unsaved edits belonging to this BlockChunk.
        for ((pos, change) in changes) {
            if (pos.first == x && pos.second == z) columnBlocks[pos.third] = change.after
        }
    }

    private fun currentBlock(x: Int, z: Int, y: Int): String {
        if (x == selectedX && z == selectedZ && columnBlocks.containsKey(y)) {
            return columnBlocks[y] ?: "minecraft:air"
        }
        val change = changes[Triple(x, z, y)]
        return change?.after ?: "minecraft:air"
    }

    private fun openColumn(x: Int, z: Int, restoreY: Int = columnScrollY) {
        selectedX = x
        selectedZ = z
        loadColumn(x, z)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
        }
        root.addView(TextView(this).apply {
            text = "Chunk Coordinates: ($chunkX, $chunkZ)\n" +
                    "BlockChunk Coordinates: ($x, $z)\n" +
                    "Exact Coordinates: (${chunkX * 16 + x}, ${chunkZ * 16 + z})"
            textSize = 18f
            setPadding(8, 8, 8, 12)
        })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(CheckBox(this).apply {
            text = "Hide Air"
            isChecked = hideAir
            setOnCheckedChangeListener { _, checked ->
                hideAir = checked
                val old = columnScrollY
                openColumn(selectedX, selectedZ, old)
            }
        })
        controls.addView(Button(this).apply { text = "Search"; setOnClickListener { showSearchDialog() } })
        controls.addView(Button(this).apply { text = "Replace"; setOnClickListener { showReplaceDialog() } })
        controls.addView(Button(this).apply { text = "View Chunk 3D"; setOnClickListener { open3DViewer(false) } })
        controls.addView(Button(this).apply {
            text = "Undo"; isEnabled = undoStack.isNotEmpty()
            setOnClickListener { undo() }
        })
        controls.addView(Button(this).apply {
            text = "Redo"; isEnabled = redoStack.isNotEmpty()
            setOnClickListener { redo() }
        })
        root.addView(controls)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (y in 320 downTo -64) {
            val name = columnBlocks[y] ?: "minecraft:air"
            if (hideAir && isAir(name)) continue
            list.addView(TextView(this).apply {
                text = "Y $y   $name"
                textSize = 15f
                setPadding(16, 14, 16, 14)
                setOnClickListener {
                    columnScrollY = scroll.scrollY
                    selectedY = y
                    editBlock(y, name)
                }
            })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        buttons.addView(Button(this).apply { text = "Back"; setOnClickListener { showGrid() } })
        buttons.addView(Button(this).apply { text = "Save"; setOnClickListener { saveChanges() } })
        root.addView(buttons)
        setContentView(root)
        scroll.post { scroll.scrollTo(0, restoreY.coerceAtLeast(0)) }
    }

    private fun editBlock(y: Int, current: String) {
        val input = EditText(this).apply {
            setText(current); hint = "minecraft:block_name"; setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit block • Y=$y")
            .setMessage("Enter a Minecraft block identifier.")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || !name.startsWith("minecraft:")) {
                    Toast.makeText(this, "Use a full minecraft:* block identifier.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyAction(listOf(BlockChange(
                    Triple(selectedX, selectedZ, y), current, name
                )))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyAction(actionChanges: List<BlockChange>) {
        val real = actionChanges.filter { it.before != it.after }
        if (real.isEmpty()) return
        for (c in real) {
            changes[c.pos] = c
            if (c.pos.first == selectedX && c.pos.second == selectedZ) columnBlocks[c.pos.third] = c.after
        }
        undoStack.addLast(EditAction(real))
        redoStack.clear()
        dirty = true
        openColumn(selectedX, selectedZ, columnScrollY)
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        val action = undoStack.removeLast()
        for (c in action.changes) {
            if (c.before == "minecraft:air") changes.remove(c.pos)
            else changes[c.pos] = c.copy(after = c.before)
        }
        redoStack.addLast(action)
        dirty = true
        openColumn(selectedX, selectedZ, columnScrollY)
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val action = redoStack.removeLast()
        for (c in action.changes) changes[c.pos] = c
        undoStack.addLast(action)
        dirty = true
        openColumn(selectedX, selectedZ, columnScrollY)
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply { hint = "minecraft:stone"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Search blocks")
            .setMessage("Searches this BlockChunk only (Y=320 to Y=-64).")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                showSearchResults(input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchResults(query: String) {
        if (query.isBlank()) return
        val matches = ArrayList<Int>()
        for (y in 320 downTo -64) {
            val name = currentBlock(selectedX, selectedZ, y)
            if (name.contains(query, ignoreCase = true)) matches += y
        }
        val message = if (matches.isEmpty())
            "No blocks found for \"$query\"."
        else "Found ${matches.size} block(s) in BlockChunk X=$selectedX Z=$selectedZ."
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        matches.forEach { y ->
            list.addView(TextView(this).apply {
                text = "Y=$y   ${currentBlock(selectedX, selectedZ, y)}"
                setPadding(8, 10, 8, 10)
                setOnClickListener {
                    selectedY = y
                    columnScrollY = if (hideAir) 0 else ((320 - y) * 44).coerceAtLeast(0)
                    openColumn(selectedX, selectedZ, columnScrollY)
                }
            })
        }
        AlertDialog.Builder(this).setTitle("Search BlockChunk").setMessage(message)
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Close", null).show()
    }

    private fun showReplaceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8)
        }
        val from = EditText(this).apply { hint = "Replace block (minecraft:stone)"; setSingleLine(true) }
        val to = EditText(this).apply { hint = "With block (minecraft:deepslate)"; setSingleLine(true) }
        container.addView(from); container.addView(to)
        AlertDialog.Builder(this)
            .setTitle("Replace blocks")
            .setMessage("Replace a block in this BlockChunk, or replace every matching block in this BlockChunk.")
            .setView(container)
            .setPositiveButton("Replace Selected") { _, _ ->
                replaceSelected(from.text.toString().trim(), to.text.toString().trim())
            }
            .setNeutralButton("Replace All") { _, _ ->
                replaceAll(from.text.toString().trim(), to.text.toString().trim())
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun replaceSelected(from: String, to: String) {
        if (!from.startsWith("minecraft:") || !to.startsWith("minecraft:")) {
            Toast.makeText(this, "Use full minecraft:* identifiers.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedY !in -64..320) {
            Toast.makeText(this, "Select a block first.", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Triple(selectedX, selectedZ, selectedY)
        val current = currentBlock(selectedX, selectedZ, selectedY)
        if (!current.equals(from, ignoreCase = true)) {
            Toast.makeText(this, "Selected block is $current, not $from.", Toast.LENGTH_SHORT).show()
            return
        }
        applyAction(listOf(BlockChange(p, current, to)))
        Toast.makeText(this, "Replaced selected block.", Toast.LENGTH_SHORT).show()
    }

    private fun replaceAll(from: String, to: String) {
        if (!from.startsWith("minecraft:") || !to.startsWith("minecraft:")) {
            Toast.makeText(this, "Use full minecraft:* identifiers.", Toast.LENGTH_SHORT).show()
            return
        }
        val changesNow = ArrayList<BlockChange>()
        for (y in 320 downTo -64) {
            val current = currentBlock(selectedX, selectedZ, y)
            if (current.equals(from, ignoreCase = true)) {
                changesNow += BlockChange(Triple(selectedX, selectedZ, y), current, to)
            }
        }
        if (changesNow.isEmpty()) {
            Toast.makeText(this, "No matching blocks found.", Toast.LENGTH_SHORT).show()
            return
        }
        applyAction(changesNow)
        Toast.makeText(this, "Replaced ${changesNow.size} block(s).", Toast.LENGTH_LONG).show()
    }

    private fun open3DViewer(selectionEnabled: Boolean) {
        val i = android.content.Intent(this, Chunk3DViewerActivity::class.java)
        i.putExtra(Chunk3DViewerActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        i.putExtra(Chunk3DViewerActivity.EXTRA_CHUNK_X, chunkX)
        i.putExtra(Chunk3DViewerActivity.EXTRA_CHUNK_Z, chunkZ)
        i.putExtra(Chunk3DViewerActivity.EXTRA_DIMENSION, dimension)
        i.putExtra(Chunk3DViewerActivity.EXTRA_SELECTION_ENABLED, selectionEnabled)
        if (!selectionEnabled) {
            i.putExtra(Chunk3DViewerActivity.EXTRA_SELECTED_X, selectedX)
            i.putExtra(Chunk3DViewerActivity.EXTRA_SELECTED_Z, selectedZ)
        }
        startActivity(i)
    }

    private fun saveChanges() {
        val b = bridge ?: return
        val pending = changes.values.toList()
        if (pending.isEmpty()) {
            Toast.makeText(this, "No changes to save.", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val writesBySub = HashMap<Int, ByteArray>()
                for (c in pending) {
                    val x = c.pos.first; val z = c.pos.second; val y = c.pos.third
                    val sy = Math.floorDiv(y, 16)
                    var bytes = writesBySub[sy] ?: original[sy]?.copyOf()
                        ?: throw IllegalStateException("Missing subchunk Y=$sy")
                    val localY = Math.floorMod(y, 16)
                    bytes = SubChunkEditor.replaceBlock(bytes, x, z, localY, c.after)
                    writesBySub[sy] = bytes
                }
                val writes = writesBySub.map { (sy, bytes) ->
                    ChunkKeys.subChunkKey(chunkX, chunkZ, sy, dimension) to bytes
                }
                b.writeBatch(writes)
                changes.clear()
                dirty = false
                runOnUiThread {
                    Toast.makeText(this, "Saved ${writes.size} changed subchunk(s).", Toast.LENGTH_LONG).show()
                    showGrid()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    showErrorDialog(this, "Save failed — changes were kept in memory", e)
                }
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SEARCH, 0, "Search blocks")
        menu.add(0, MENU_REPLACE, 1, "Replace blocks")
        menu.add(0, MENU_HIDE_AIR, 2, if (hideAir) "Show Air" else "Hide Air")
        menu.add(0, MENU_UNDO, 3, "Undo")
        menu.add(0, MENU_REDO, 4, "Redo")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SEARCH -> { if (selectedX >= 0) showSearchDialog() else Toast.makeText(this, "Open a BlockChunk first.", Toast.LENGTH_SHORT).show(); return true }
            MENU_REPLACE -> { if (selectedX >= 0) showReplaceDialog() else Toast.makeText(this, "Open a BlockChunk first.", Toast.LENGTH_SHORT).show(); return true }
            MENU_HIDE_AIR -> { hideAir = !hideAir; if (selectedX >= 0) openColumn(selectedX, selectedZ, columnScrollY) else showGrid(); invalidateOptionsMenu(); return true }
            MENU_UNDO -> { undo(); return true }
            MENU_REDO -> { redo(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (dirty) AlertDialog.Builder(this).setTitle("Unsaved changes")
            .setMessage("Discard block edits?")
            .setPositiveButton("Discard") { _, _ -> super.onBackPressed() }
            .setNegativeButton("Cancel", null).show()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (ownsBridge) try { bridge?.close() } catch (_: Exception) {}
        bridge = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        const val EXTRA_CHUNK_X = "chunk_x"
        const val EXTRA_CHUNK_Z = "chunk_z"
        const val EXTRA_DIMENSION = "dimension"
        private const val MENU_SEARCH = 1
        private const val MENU_REPLACE = 2
        private const val MENU_HIDE_AIR = 3
        private const val MENU_UNDO = 4
        private const val MENU_REDO = 5
    }
}
