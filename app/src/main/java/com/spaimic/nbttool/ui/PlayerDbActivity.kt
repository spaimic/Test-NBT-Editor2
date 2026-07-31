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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val worldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        title = "Player / Map / Village data"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filterToggle = CheckBox(this).apply {
            text = "Show everything (maps, villages, raw chunk data)"
            isChecked = false
            setOnCheckedChangeListener { _, _ -> applyFilter() }
        }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { saveAll() }
        }
        root.addView(filterToggle)
        root.addView(listView)
        root.addView(saveButton)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ -> openEntry(shownDisplayNames[position]) }

        openDbAndListKeys(worldDir)
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
                    // one backup per fresh editing session, before anything gets touched
                    LevelDbBridge.backup(worldDir, LocalWorldStore.backupsDir(this))
                }
                val bridge = LevelDbBridge.open(worldDir)
                LevelDbSession.bridge = bridge
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
                runOnUiThread { showErrorDialog(this, "Failed to open world DB", e) }
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
                runOnUiThread { showErrorDialog(this, "Couldn't parse \"$displayName\"", e) }
            }
        }.start()
    }

    private fun saveAll() {
        val bridge = LevelDbSession.bridge ?: return
        val worldDir = LevelDbSession.worldDir ?: return
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
                openDbAndListKeys(worldDir, preserveEdits = true)
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
    }
}
