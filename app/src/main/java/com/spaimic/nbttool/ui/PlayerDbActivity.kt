package com.spaimic.nbttool.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * Browses a local world's LevelDB `db/` folder — player, map, structure, etc.
 * data. Since the world already lives in this app's own sandbox, writes go
 * straight to the real file; no Shizuku, no push-back step. The db/ folder
 * actually holds ALL world data — a key per explored chunk of terrain being
 * the usual bulk of it — so this defaults to showing just what's normally
 * worth hand-editing: the local player, other player records, and structures.
 */
class PlayerDbActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var filterToggle: CheckBox
    private var allDisplayNames: List<String> = emptyList()
    private var shownDisplayNames: List<String> = emptyList()
    private var isLoading = false
    private var worldDir: File? = null
    private var allKeysLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "Player / Map / Village data"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterToggle = CheckBox(this).apply {
            text = "Show everything (maps, villages, raw chunk data)"
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (checked && !allKeysLoaded) loadAllKeysInBackground() else applyFilter()
            }
        }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(filterToggle)
        root.addView(listView)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ -> openEntry(shownDisplayNames[position]) }

        openDbAndListKeys(worldDir)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) {
            saveAll()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun isInterestingKey(name: String): Boolean =
        name == "~local_player" ||
            name.startsWith("player_") ||
            name.startsWith("structuretemplate_")

    private fun applyFilter() {
        shownDisplayNames = if (filterToggle.isChecked) allDisplayNames else allDisplayNames.filter { isInterestingKey(it) }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shownDisplayNames)
    }

    private fun openDbAndListKeys(worldDir: File, preserveEdits: Boolean = false) {
        if (isLoading) return
        isLoading = true
        Thread {
            try {
                if (preserveEdits) {
                    try { LevelDbSession.bridge?.close() } catch (_: Exception) {}
                    LevelDbSession.keyBytesByName.clear()
                } else {
                    LevelDbSession.reset()
                }
                val bridge = LevelDbBridge.open(worldDir)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldDir = worldDir

                // Fast startup: only index the small set of entries that the normal
                // screen displays. Raw chunk/village/map keys are deferred until the
                // user explicitly asks for "Show everything". No 50 MB DB backup is
                // made just by opening this screen; backup happens on Save.
                val interesting = ArrayList<LevelDbBridge.KeyEntry>()
                bridge.forEachRawKey { raw ->
                    val name = bridge.decodeKeyName(raw)
                    if (isInterestingKey(name)) interesting.add(LevelDbBridge.KeyEntry(name, raw))
                }
                interesting.sortBy { it.displayName }
                for (k in interesting) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                runOnUiThread {
                    allDisplayNames = interesting.map { it.displayName }
                    allKeysLoaded = false
                    applyFilter()
                    isLoading = false
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread { showErrorDialog(this, "Failed to open world DB", e) }
            }
        }.start()
    }

    private fun loadAllKeysInBackground() {
        val bridge = LevelDbSession.bridge ?: return
        isLoading = true
        Thread {
            try {
                val keys = bridge.listKeys()
                for (k in keys) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                allDisplayNames = keys.map { it.displayName }
                allKeysLoaded = true
                runOnUiThread {
                    applyFilter()
                    isLoading = false
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread {
                    filterToggle.isChecked = false
                    showErrorDialog(this, "Couldn't load all DB keys", e)
                }
            }
        }.start()
    }

    private fun ensureBackup() {
        val dir = worldDir ?: return
        try {
            LevelDbBridge.backup(dir, LocalWorldStore.backupsDir(this))
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't create DB backup before saving", e)
        }
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
                runOnUiThread { showErrorDialog(this, "Couldn't parse \"$displayName\"", e) }
            }
        }.start()
    }

    private fun saveAll() {
        if (LevelDbSession.bridge == null) return
        Thread {
            try {
                ensureBackup()
                LevelDbSession.saveAllLoadedEntries()
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Save failed (your edits are still kept — safe to retry)", e) }
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
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
    }
}
