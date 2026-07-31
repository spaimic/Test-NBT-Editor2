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

        mapView.onTap = { x, y, z -> coordText.text = "X: $x\nY: $y\nZ: $z" }

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
                val chunkData = ChunkScanner.scanOverworld(bridge)
                runOnUiThread { mapView.chunkData = chunkData }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to load world map", e) }
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
            } catch (e: Exception) {
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
                val chunkData = ChunkScanner.scanOverworld(bridge)
                runOnUiThread { mapView.chunkData = chunkData }
            } catch (e: Exception) {
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
