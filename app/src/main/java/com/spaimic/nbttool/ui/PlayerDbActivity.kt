package com.spaimic.nbttool.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession

/**
 * Browses the world's LevelDB `db/` folder. The db/ folder actually holds ALL
 * world data — not just player/map/village records but a key per explored chunk
 * of terrain, which is usually the vast majority of entries. By default this
 * screen filters down to what's normally worth hand-editing: the local player,
 * remote/offline player records, and saved structures — with a toggle to show
 * everything (maps, villages, raw chunk data) if you actually need it.
 */
class PlayerDbActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var filterToggle: CheckBox
    private var allDisplayNames: List<String> = emptyList()
    private var shownDisplayNames: List<String> = emptyList()
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val worldsPath = intent.getStringExtra(EXTRA_WORLDS_PATH) ?: return finish()
        val worldDir = intent.getStringExtra(EXTRA_WORLD_DIR) ?: return finish()
        title = "Player / Map / Village data"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterToggle = CheckBox(this).apply {
            text = "Show everything (maps, villages, raw chunk data)"
            isChecked = false
            setOnCheckedChangeListener { _, _ -> applyFilter() }
        }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup_MATCH, 0, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Save all changes to world"
            setOnClickListener { saveAll() }
        }
        root.addView(filterToggle)
        root.addView(listView)
        root.addView(saveButton)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ -> openEntry(shownDisplayNames[position]) }

        openDbAndListKeys(worldsPath, worldDir)
    }

    /** ~local_player, player_*, and saved structures — the things worth hand-editing most often. */
    private fun isInterestingKey(name: String): Boolean =
        name == "~local_player" ||
            name.startsWith("player_") ||
            name.startsWith("structuretemplate_")

    private fun applyFilter() {
        shownDisplayNames = if (filterToggle.isChecked) allDisplayNames else allDisplayNames.filter { isInterestingKey(it) }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shownDisplayNames)
    }

    private fun openDbAndListKeys(worldsPath: String, worldDir: String, preserveEdits: Boolean = false) {
        if (isLoading) return // avoid opening the same DB twice concurrently
        isLoading = true
        Thread {
            try {
                if (preserveEdits) {
                    // a save attempt just closed (or half-closed) the previous handle — replace it
                    // with a fresh one, but keep loadedEntries: those are your in-memory edits,
                    // independent of whether the old DB handle is still alive.
                    try { LevelDbSession.bridge?.close() } catch (_: Exception) {}
                    LevelDbSession.keyBytesByName.clear()
                } else {
                    LevelDbSession.reset()
                }
                val bridge = LevelDbBridge.open(worldsPath, worldDir, getExternalFilesDir(null)!!)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldsPath = worldsPath
                LevelDbSession.worldDir = worldDir
                val keys = bridge.listKeys()
                for (k in keys) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                runOnUiThread {
                    allDisplayNames = keys.map { it.displayName }
                    applyFilter()
                    isLoading = false
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread {
                    showErrorDialog(this, "Failed to open world DB", e)
                }
            }
        }.start()
    }

    private fun openEntry(displayName: String) {
        Thread {
            try {
                if (!LevelDbSession.loadedEntries.containsKey(displayName)) {
                    val rawKey = LevelDbSession.keyBytesByName[displayName]!!
                    val bytes = LevelDbSession.bridge!!.read(rawKey)
                        ?: throw IllegalStateException("key vanished from DB")
                    LevelDbSession.loadedEntries[displayName] = NbtIO.readRootCompoundNoHeader(bytes)
                }
                runOnUiThread {
                    val i = Intent(this, DbEntryEditorActivity::class.java)
                    i.putExtra(DbEntryEditorActivity.EXTRA_DISPLAY_NAME, displayName)
                    startActivity(i)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showErrorDialog(this, "Couldn't parse \"$displayName\"", e)
                }
            }
        }.start()
    }

    private fun saveAll() {
        val bridge = LevelDbSession.bridge ?: return
        Thread {
            try {
                for ((name, compound) in LevelDbSession.loadedEntries) {
                    val rawKey = LevelDbSession.keyBytesByName[name] ?: continue
                    bridge.write(rawKey, NbtIO.writeRootCompoundNoHeader(compound))
                }
                val localDir = bridge.localDbDir
                bridge.close()
                LevelDbBridge.pushBack(LevelDbSession.worldsPath, LevelDbSession.worldDir, localDir, getExternalFilesDir(null)!!)
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Save failed (your edits are still kept — safe to retry)", e) }
            } finally {
                // whether that succeeded or failed, the handle used above may already be closed —
                // always get a fresh one so a retry never reuses a dead bridge (keeps your edits)
                openDbAndListKeys(LevelDbSession.worldsPath, LevelDbSession.worldDir, preserveEdits = true)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            try { LevelDbSession.bridge?.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val EXTRA_WORLDS_PATH = "worlds_path"
        const val EXTRA_WORLD_DIR = "world_dir"
        private const val ViewGroup_MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
}
