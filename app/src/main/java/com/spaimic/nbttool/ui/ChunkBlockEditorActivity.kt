package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.content.Intent
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
    private val blocks = HashMap<Triple<Int, Int, Int>, String>()
    private val original = HashMap<Int, ByteArray>()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "Chunk Block Editor"
        chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        loadChunk()
    }

    private fun loadChunk() {
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
                for ((sy, bytes) in subs) {
                    original[sy] = bytes.copyOf()
                    when (val parsed = SubChunkParser.parse(bytes)) {
                        is SubChunkParser.ParseResult.Success -> {
                            for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                                blocks[Triple(x, z, sy * 16 + y)] = parsed.blocks[x][z][y]
                            }
                        }
                        else -> Unit
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
            val top = highestY(x, z)
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
        val view3d = Button(this).apply {
            text = "View Entire Chunk in 3D"
            setOnClickListener { open3DViewer(true) }
        }
        vertical.addView(view3d)
        outer.addView(vertical)
        setContentView(outer)
    }

    private fun highestY(x: Int, z: Int): Int {
        for (y in 320 downTo -64) {
            val n = blocks[Triple(x, z, y)] ?: "minecraft:air"
            if (!isAir(n)) return y
        }
        return Int.MIN_VALUE
    }

    private fun isAir(name: String): Boolean = name == "minecraft:air" || name == "air"

    private fun openColumn(x: Int, z: Int, restoreY: Int = columnScrollY) {
        selectedX = x
        selectedZ = z
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
        }
        val header = TextView(this).apply {
            text = "Chunk Coordinates: ($chunkX, $chunkZ)\nBlockChunk Coordinates: ($x, $z)\nExact Coordinates: (${chunkX * 16 + x}, ${chunkZ * 16 + z})"
            textSize = 18f
            setPadding(8, 8, 8, 12)
        }
        root.addView(header)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val hide = CheckBox(this).apply {
            text = "Hide Air"
            isChecked = hideAir
            setOnCheckedChangeListener { _, checked ->
                hideAir = checked
                columnScrollY = 0
                openColumn(selectedX, selectedZ)
            }
        }
        val search = Button(this).apply { text = "Search"; setOnClickListener { showSearchDialog() } }
        val replace = Button(this).apply { text = "Replace"; setOnClickListener { showReplaceDialog() } }
        val view3d = Button(this).apply { text = "View Chunk 3D"; setOnClickListener { open3DViewer(false) } }
        val undo = Button(this).apply { text = "Undo"; isEnabled = undoStack.isNotEmpty(); setOnClickListener { undo() } }
        val redo = Button(this).apply { text = "Redo"; isEnabled = redoStack.isNotEmpty(); setOnClickListener { redo() } }
        controls.addView(hide)
        controls.addView(search)
        controls.addView(replace)
        controls.addView(view3d)
        controls.addView(undo)
        controls.addView(redo)
        root.addView(controls)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (y in 320 downTo -64) {
            val name = blocks[Triple(x, z, y)] ?: "minecraft:air"
            if (hideAir && isAir(name)) continue
            val row = TextView(this).apply {
                text = "Y $y   $name"
                textSize = 15f
                setPadding(16, 14, 16, 14)
                setOnClickListener {
                    columnScrollY = scroll.scrollY
                    selectedY = y
                    editBlock(y, name)
                }
            }
            list.addView(row)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val back = Button(this).apply { text = "Back"; setOnClickListener { showGrid() } }
        val save = Button(this).apply { text = "Save"; setOnClickListener { saveChanges() } }
        buttons.addView(back)
        buttons.addView(save)
        root.addView(buttons)
        setContentView(root)
        scroll.post { scroll.scrollTo(0, restoreY.coerceAtLeast(0)) }
    }

    private fun editBlock(y: Int, current: String) {
        val input = EditText(this).apply { setText(current); hint = "minecraft:block_name"; setSingleLine(true) }
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
                applyAction(listOf(BlockChange(Triple(selectedX, selectedZ, y), current, name)))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyAction(changes: List<BlockChange>) {
        val real = changes.filter { it.before != it.after }
        if (real.isEmpty()) return
        for (c in real) blocks[c.pos] = c.after
        undoStack.addLast(EditAction(real))
        redoStack.clear()
        dirty = true
        openColumn(selectedX, selectedZ, columnScrollY)
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        val action = undoStack.removeLast()
        for (c in action.changes) blocks[c.pos] = c.before
        redoStack.addLast(action)
        dirty = true
        openColumn(selectedX, selectedZ, columnScrollY)
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val action = redoStack.removeLast()
        for (c in action.changes) blocks[c.pos] = c.after
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
            .setPositiveButton("Search") { _, _ -> showSearchResults(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSearchResults(query: String) {
        if (query.isBlank()) return
        val matches = ArrayList<Int>()
        for (y in 320 downTo -64) {
            val name = blocks[Triple(selectedX, selectedZ, y)] ?: "minecraft:air"
            if (name.contains(query, ignoreCase = true)) matches += y
        }
        val message = if (matches.isEmpty()) "No blocks found in BlockChunk X=$selectedX Z=$selectedZ for \"$query\"." else "Found ${matches.size} block(s) in BlockChunk X=$selectedX Z=$selectedZ."
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        matches.forEach { y ->
            val name = blocks[Triple(selectedX, selectedZ, y)] ?: "minecraft:air"
            list.addView(TextView(this).apply {
                text = "Y=$y   $name"
                setPadding(8, 10, 8, 10)
                setOnClickListener { selectedY = y; columnScrollY = 0; scrollToY(y) }
            })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        AlertDialog.Builder(this).setTitle("Search BlockChunk").setMessage(message).setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun scrollToY(y: Int) {
        val offset = if (hideAir) 0 else (320 - y) * 44
        columnScrollY = offset.coerceAtLeast(0)
        openColumn(selectedX, selectedZ, columnScrollY)
    }

    private fun showReplaceDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val from = EditText(this).apply { hint = "Replace block (minecraft:stone)"; setSingleLine(true) }
        val to = EditText(this).apply { hint = "With block (minecraft:deepslate)"; setSingleLine(true) }
        container.addView(from)
        container.addView(to)
        AlertDialog.Builder(this)
            .setTitle("Replace blocks")
            .setMessage("Replace a block in this BlockChunk, or replace every matching block in this BlockChunk.")
            .setView(container)
            .setPositiveButton("Replace Selected") { _, _ -> replaceSelected(from.text.toString().trim(), to.text.toString().trim()) }
            .setNeutralButton("Replace All") { _, _ -> replaceAll(from.text.toString().trim(), to.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun replaceSelected(from: String, to: String) {
        if (!from.startsWith("minecraft:") || !to.startsWith("minecraft:")) {
            Toast.makeText(this, "Use full minecraft:* identifiers.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedX !in 0..15 || selectedZ !in 0..15 || selectedY !in -64..320) {
            Toast.makeText(this, "Select a block first.", Toast.LENGTH_SHORT).show()
            return
        }
        val p = Triple(selectedX, selectedZ, selectedY)
        val current = blocks[p] ?: "minecraft:air"
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
        val changes = ArrayList<BlockChange>()
        for (y in 320 downTo -64) {
            val p = Triple(selectedX, selectedZ, y)
            val current = blocks[p] ?: "minecraft:air"
            if (current.equals(from, ignoreCase = true)) changes += BlockChange(p, current, to)
        }
        if (changes.isEmpty()) {
            Toast.makeText(this, "No matching blocks found.", Toast.LENGTH_SHORT).show()
            return
        }
        applyAction(changes)
        Toast.makeText(this, "Replaced ${changes.size} block(s).", Toast.LENGTH_LONG).show()
    }

    private fun open3DViewer(selectionEnabled: Boolean) {
        val i = Intent(this, Chunk3DViewerActivity::class.java)
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
        Thread {
            try {
                val writes = ArrayList<Pair<ByteArray, ByteArray>>()
                for ((sy, old) in original) {
                    var changed = false
                    val parsed = SubChunkParser.parse(old)
                    if (parsed is SubChunkParser.ParseResult.Success) {
                        for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                            val before = parsed.blocks[x][z][y]
                            val after = blocks[Triple(x, z, sy * 16 + y)] ?: before
                            if (before != after) { changed = true; break }
                        }
                    }
                    if (!changed) continue
                    var bytes = old
                    for (x in 0..15) for (z in 0..15) for (y in 0..15) {
                        val before = (SubChunkParser.parse(bytes) as? SubChunkParser.ParseResult.Success)?.blocks?.get(x)?.get(z)?.get(y) ?: continue
                        val after = blocks[Triple(x, z, sy * 16 + y)] ?: before
                        if (before != after) bytes = SubChunkEditor.replaceBlock(bytes, x, z, y, after)
                    }
                    writes += ChunkKeys.subChunkKey(chunkX, chunkZ, sy, dimension) to bytes
                }
                b.writeBatch(writes)
                dirty = false
                runOnUiThread {
                    Toast.makeText(this, "Saved ${writes.size} changed subchunk(s).", Toast.LENGTH_LONG).show()
                    showGrid()
                }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Save failed — no changes were intentionally discarded", e) }
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
            MENU_SEARCH -> { showSearchDialog(); return true }
            MENU_REPLACE -> { showReplaceDialog(); return true }
            MENU_HIDE_AIR -> { hideAir = !hideAir; if (selectedX >= 0) openColumn(selectedX, selectedZ, columnScrollY) else showGrid(); invalidateOptionsMenu(); return true }
            MENU_UNDO -> { undo(); return true }
            MENU_REDO -> { redo(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (dirty) AlertDialog.Builder(this).setTitle("Unsaved changes").setMessage("Discard block edits?")
            .setPositiveButton("Discard") { _, _ -> super.onBackPressed() }
            .setNegativeButton("Cancel", null).show()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        if (ownsBridge) try { bridge?.close() } catch (_: Exception) { }
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
