package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.shizuku.LevelDbBridge
import com.spaimic.nbttool.shizuku.LevelDbSession
import java.io.File
import java.io.BufferedWriter
import java.io.OutputStreamWriter

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
    private lateinit var exportKeysButton: Button
    private var shownDisplayNames: List<String> = emptyList()
    private var isLoading = false
    private var worldDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialWorldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        worldDir = initialWorldDir
        title = "Advanced: Browse Extra DB Keys"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val info = TextView(this).apply {
            text = "Extra LevelDB records are loaded only when you choose a category. The full database is never scanned just to open this screen."
            setPadding(20, 18, 20, 12)
        }

        browseButton = Button(this).apply {
            text = "Browse Extra DB Keys"
            setOnClickListener { showAdvancedMenu() }
        }

        exportKeysButton = Button(this).apply {
            text = "DEBUG: Export ALL LevelDB key names (.txt)"
            setOnClickListener { exportAllKeyNames() }
        }

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(info)
        root.addView(browseButton)
        root.addView(exportKeysButton)
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
     * It lists keys that are not recognized Bedrock chunk-storage records. Binary
     * keys from other LevelDB namespaces are intentionally retained; only the
     * coordinate/tag chunk formats are filtered out.
     */
    private fun showAdvancedMenu() {
        val options = arrayOf(
            "~local_player",
            "player_*",
            "player_server_*",
            "structuretemplate_*",
            "map_*",
            "VILLAGE_Overworld_*",
            "Nether",
            "Overworld",
            "TheEnd",
            "AutonomousEntities",
            "LevelChunkMetaDataDictionary",
            "WorldClocks",
            "mobevents",
            "portals",
            "schedulerWT",
            "scoreboard",
            "tickingarea_*"
        )

        AlertDialog.Builder(this)
            .setTitle("Advanced: Browse Extra DB Keys")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDirectKey("~local_player")
                    1 -> showKeyPickerLazily("Player Data", { it.startsWith("player_") && !it.startsWith("player_server_") })
                    2 -> showKeyPickerLazily("Server Player Data", { it.startsWith("player_server_") })
                    3 -> showKeyPickerLazily("MCStructure Data", { it.startsWith("structuretemplate_") })
                    4 -> showMapDataMenu()
                    5 -> showKeyPickerLazily("Village Data (VILLAGE_Overworld_*)", { it.startsWith("VILLAGE_Overworld_") })
                    6 -> openDirectKey("Nether")
                    7 -> openDirectKey("Overworld")
                    8 -> openDirectKey("TheEnd")
                    9 -> openDirectKey("AutonomousEntities")
                    10 -> openDirectKey("LevelChunkMetaDataDictionary")
                    11 -> openDirectKey("WorldClocks")
                    12 -> openDirectKey("mobevents")
                    13 -> openDirectKey("portals")
                    14 -> openDirectKey("schedulerWT")
                    15 -> openDirectKey("scoreboard")
                    16 -> showKeyPickerLazily("Ticking Areas (tickingarea_*)", { it.startsWith("tickingarea_") })
                }
            }
            .show()
    }

    private fun showMapDataMenu() {
        AlertDialog.Builder(this)
            .setTitle("map_*")
            .setItems(arrayOf("Show map_* keys", "Hide map_* keys")) { _, which ->
                if (which == 0) {
                    showKeyPickerLazily("Map Data (map_*)", { it.startsWith("map_") })
                } else {
                    Toast.makeText(this, "map_* keys are hidden.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun openDirectKey(keyName: String) {
        if (isLoading) return
        val bridge = LevelDbSession.bridge ?: run {
            Toast.makeText(this, "LevelDB is not open yet.", Toast.LENGTH_SHORT).show()
            return
        }
        isLoading = true
        Thread {
            try {
                val rawKey = keyName.toByteArray(Charsets.UTF_8)
                val bytes = bridge.read(rawKey)
                    ?: throw IllegalStateException("No $keyName data exists in this world")
                LevelDbSession.keyBytesByName[keyName] = rawKey
                LevelDbSession.loadedEntries[keyName] = NbtIO.readRootCompoundNoHeader(bytes)
                runOnUiThread { openEntry(keyName) }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Couldn't load $keyName", e) }
            } finally {
                isLoading = false
            }
        }.start()
    }

    /**
     * Temporary debugging feature. Creates a text document containing every
     * LevelDB key name, one per line. Values are NEVER read, decoded, or exported.
     */
    private fun exportAllKeyNames() {
        if (isLoading) return
        if (LevelDbSession.bridge == null) {
            Toast.makeText(this, "LevelDB is not open yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "leveldb_key_names.txt")
        }
        startActivityForResult(intent, REQUEST_EXPORT_KEY_NAMES)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_KEY_NAMES || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        exportKeyNamesToUri(uri)
    }

    private fun exportKeyNamesToUri(uri: Uri) {
        val bridge = LevelDbSession.bridge ?: return
        if (isLoading) return

        isLoading = true
        exportKeysButton.isEnabled = false
        exportKeysButton.text = "DEBUG: Exporting key names…"

        Thread {
            var count = 0
            try {
                val resolver = contentResolver
                val output = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Couldn't open the selected file")

                output.use { stream ->
                    BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024).use { writer ->
                        // Key-only scan: entry.value is never accessed.
                        bridge.forEachRawKey { rawKey ->
                            writer.write(bridge.decodeKeyName(rawKey))
                            writer.newLine()
                            count++
                        }
                    }
                }

                runOnUiThread {
                    isLoading = false
                    exportKeysButton.isEnabled = true
                    exportKeysButton.text = "DEBUG: Export ALL LevelDB key names (.txt)"
                    Toast.makeText(
                        this,
                        "Exported $count key names.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread {
                    exportKeysButton.isEnabled = true
                    exportKeysButton.text = "DEBUG: Export ALL LevelDB key names (.txt)"
                    showErrorDialog(this, "Couldn't export LevelDB key names", e)
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
        private const val REQUEST_EXPORT_KEY_NAMES = 2001
    }
}
