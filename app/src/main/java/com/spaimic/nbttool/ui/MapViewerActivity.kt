package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.ChunkScanner
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

    private fun loadAndScan() {
        Thread {
            try {
                LevelDbSession.reset()
                LevelDbBridge.backup(worldDir, LocalWorldStore.backupsDir(this))
                val bridge = LevelDbBridge.open(worldDir)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldDir = worldDir
                val keys = bridge.listKeys()
                for (k in keys) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey

                val playerPos = LevelDbSession.keyBytesByName["~local_player"]?.let { rawKey ->
                    try {
                        val bytes = bridge.read(rawKey)
                        val compound = bytes?.let { NbtIO.readRootCompoundNoHeader(it) }
                        val pos = compound?.entries?.get("Pos") as? NbtTag.ListTag
                        val x = (pos?.items?.getOrNull(0) as? NbtTag.Float)?.value
                        val z = (pos?.items?.getOrNull(2) as? NbtTag.Float)?.value
                        if (x != null && z != null) x.toInt() to z.toInt() else null
                    } catch (e: Exception) {
                        null // fine — the map just falls back to bounding-box centering
                    }
                }

                val scanResult = ChunkScanner.scanOverworld(bridge)
                runOnUiThread {
                    mapView.preferredCenterBlock = playerPos
                    mapView.chunkData = scanResult.grid
                    reportScanStats(scanResult.stats)
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    showErrorDialog(this, "Ran out of memory scanning this world",
                        Exception("The map view couldn't hold this world's chunk data in memory. Very large/heavily-explored worlds may be too big for this first version of the map viewer — the rest of the app (level.dat, player data, structures) still works fine via the ☰ menu options.", e))
                }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to load world map", e) }
            }
        }.start()
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
        AlertDialog.Builder(this)
            .setTitle("Chunk ($chunkX, $chunkZ)")
            .setItems(arrayOf("Show Entities", "Show Block Entities")) { _, which ->
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
                }
            }
            .show()
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
            "Save changes",
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
                    3 -> saveChanges()
                    4 -> openLevelDatEditor()
                    5 -> openAllKeysBrowser()
                }
            }
            .show()
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
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                for ((name, compound) in LevelDbSession.loadedEntries) {
                    val rawKey = LevelDbSession.keyBytesByName[name] ?: continue
                    bridge.write(rawKey, NbtIO.writeRootCompoundNoHeader(compound))
                }
                bridge.close()
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Save failed (your edits are still kept — safe to retry)", e) }
            } finally {
                // the handle used above may now be closed — get a fresh one, keeping edits,
                // and re-scan the map in case a pasted structure changed the terrain
                runOnUiThread { reopenPreservingEdits() }
            }
        }.start()
    }

    private fun reopenPreservingEdits() {
        Thread {
            try {
                try { LevelDbSession.bridge?.close() } catch (_: Exception) {}
                LevelDbSession.keyBytesByName.clear()
                val bridge = LevelDbBridge.open(worldDir)
                LevelDbSession.bridge = bridge
                val keys = bridge.listKeys()
                for (k in keys) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                val scanResult = ChunkScanner.scanOverworld(bridge)
                runOnUiThread { mapView.chunkData = scanResult.grid }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Failed to reopen world DB", e) }
            }
        }.start()
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
    }
}
