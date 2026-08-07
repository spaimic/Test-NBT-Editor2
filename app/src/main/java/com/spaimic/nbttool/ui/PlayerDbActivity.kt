package com.spaimic.nbttool.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import java.io.File

/**
 * Lazy LevelDB browser.
 *
 * IMPORTANT: opening this screen must NOT enumerate the world's LevelDB.
 * Bedrock's db/ contains chunk records, map_* records, village data, entities,
 * structures, and many other keys. Enumerating all of them just to open the
 * screen can take many seconds on a large world.
 *
 * Startup therefore only opens the DB and performs a direct lookup of
 * ~local_player. The browser is an explicit user action and excludes binary
 * chunk-storage keys, which are handled by the Map Viewer / chunk tools.
 */
class PlayerDbActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var browseButton: Button
    private var shownDisplayNames: List<String> = emptyList()
    private var isLoading = false
    private var worldDir: File? = null
    private var allKeysLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialWorldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        worldDir = initialWorldDir
        title = "LevelDB — lazy access"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val info = TextView(this).apply {
            text = "LevelDB data loads only when you open it. The full database is not scanned at startup."
            setPadding(20, 18, 20, 12)
        }

        browseButton = Button(this).apply {
            text = "Browse LevelDB keys (without chunk data)"
            setOnClickListener {
                if (!allKeysLoaded) loadAllKeysInBackground()
            }
        }

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(info)
        root.addView(browseButton)
        root.addView(listView)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ ->
            openEntry(shownDisplayNames[position])
        }

        openDbLazily(initialWorldDir)
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

    /**
     * Opens LevelDB but does not enumerate it. The only startup lookup is the
     * well-known ~local_player key, which is a single O(1)-style DB get.
     */
    private fun openDbLazily(dir: File) {
        if (isLoading) return
        isLoading = true

        Thread {
            try {
                LevelDbSession.reset()
                val bridge = LevelDbBridge.open(dir)
                LevelDbSession.bridge = bridge
                LevelDbSession.worldDir = dir

                val names = ArrayList<String>()
                if (bridge.containsName("~local_player")) {
                    LevelDbSession.keyBytesByName["~local_player"] =
                        "~local_player".toByteArray(Charsets.UTF_8)
                    names += "~local_player"
                }

                runOnUiThread {
                    shownDisplayNames = names
                    listView.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        shownDisplayNames
                    )
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

    /**
     * Explicit non-chunk browse. This is intentionally NOT called during startup.
     * It lists non-chunk keys but does not read/parse their values until the user
     * opens an individual entry.
     */
    private fun loadAllKeysInBackground() {
        val bridge = LevelDbSession.bridge ?: return
        if (isLoading) return

        isLoading = true
        browseButton.isEnabled = false
        browseButton.text = "Loading non-chunk LevelDB keys…"

        Thread {
            try {
                val keys = bridge.listNonChunkKeys()
                for (k in keys) {
                    LevelDbSession.keyBytesByName[k.displayName] = k.rawKey
                }
                val names = keys.map { it.displayName }
                allKeysLoaded = true

                runOnUiThread {
                    shownDisplayNames = names
                    listView.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        shownDisplayNames
                    )
                    browseButton.text = "Non-chunk keys loaded (${names.size})"
                    isLoading = false
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread {
                    browseButton.isEnabled = true
                    browseButton.text = "Browse LevelDB keys (without chunk data)"
                    showErrorDialog(this, "Couldn't load LevelDB keys", e)
                }
            }
        }.start()
    }

    /**
     * Values are loaded only after the user taps a key.
     */
    private fun openEntry(displayName: String) {
        if (isLoading) return
        isLoading = true

        Thread {
            try {
                val bridge = LevelDbSession.bridge
                    ?: throw IllegalStateException("LevelDB is not open")

                if (!LevelDbSession.loadedEntries.containsKey(displayName)) {
                    val rawKey = LevelDbSession.keyBytesByName[displayName]
                        ?: displayName.toByteArray(Charsets.UTF_8)

                    val bytes = bridge.read(rawKey)
                        ?: throw IllegalStateException("Key \"$displayName\" was not found")

                    LevelDbSession.loadedEntries[displayName] =
                        NbtIO.readRootCompoundNoHeader(bytes)
                }

                runOnUiThread {
                    isLoading = false
                    val i = Intent(this, DbEntryEditorActivity::class.java)
                    i.putExtra(DbEntryEditorActivity.EXTRA_DISPLAY_NAME, displayName)
                    startActivity(i)
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread {
                    showErrorDialog(this, "Couldn't parse \"$displayName\"", e)
                }
            }
        }.start()
    }

    /**
     * Save only entries the user actually opened/edited. No full DB backup or
     * full DB read is performed here.
     */
    private fun saveAll() {
        if (LevelDbSession.bridge == null) return

        Thread {
            try {
                LevelDbSession.saveAllLoadedEntries()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Saved ${LevelDbSession.loadedEntries.size} loaded entr" +
                            if (LevelDbSession.loadedEntries.size == 1) "y." else "ies.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showErrorDialog(this, "Save failed", e)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            try { LevelDbSession.bridge?.close() } catch (_: Exception) {}
            LevelDbSession.bridge = null
        }
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
    }
}
