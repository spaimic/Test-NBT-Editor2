package com.spaimic.nbttool.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession

/**
 * Browses the world's LevelDB `db/` folder — player, map, and village keys.
 * Opening pulls the whole folder via Shizuku (LevelDB needs random file access,
 * not a single byte stream); "Save" re-serializes any entries you opened/edited
 * and pushes the folder back, after backing up the original.
 */
class PlayerDbActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var displayNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val worldsPath = intent.getStringExtra(EXTRA_WORLDS_PATH) ?: return finish()
        val worldDir = intent.getStringExtra(EXTRA_WORLD_DIR) ?: return finish()
        title = "Player / Map / Village data"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup_MATCH, 0, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Save all changes to world"
            setOnClickListener { saveAll() }
        }
        root.addView(listView)
        root.addView(saveButton)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ -> openEntry(displayNames[position]) }

        openDbAndListKeys(worldsPath, worldDir)
    }

    private fun openDbAndListKeys(worldsPath: String, worldDir: String) {
        Thread {
            try {
                LevelDbSession.reset()
                val bridge = LevelDbBridge.open(worldsPath, worldDir, getExternalFilesDir(null)!!)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldsPath = worldsPath
                LevelDbSession.worldDir = worldDir
                val keys = bridge.listKeys()
                for (k in keys) LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                runOnUiThread {
                    displayNames = keys.map { it.displayName }
                    listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to open world DB: ${e.message}", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Couldn't parse \"$displayName\": ${e.message}", Toast.LENGTH_LONG).show()
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
                LevelDbBridge.pushBack(LevelDbSession.worldsPath, LevelDbSession.worldDir, localDir)
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
                // reopen so the list/session stay usable if the user keeps editing
                openDbAndListKeys(LevelDbSession.worldsPath, LevelDbSession.worldDir)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
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
