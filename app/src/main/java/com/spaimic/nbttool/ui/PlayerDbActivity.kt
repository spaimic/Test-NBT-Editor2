package com.spaimic.nbttool.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var allKeysLoaded = false
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val dir = worldDir
            if (dir != null) {
                Thread {
                    try { LevelDbSession.ensureOpen(dir).containsName("~local_player") }
                    catch (_: Throwable) { /* next tick will reopen if necessary */ }
                }.start()
            }
            if (!isFinishing) keepAliveHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialWorldDir = File(intent.getStringExtra(EXTRA_WORLD_PATH) ?: return finish())
        worldDir = initialWorldDir
        title = "Advanced: Browse Extra DB Keys"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val info = TextView(this).apply {
            text = "Extra LevelDB records only. Dedicated world data, players, structures, maps, villages, and chunk/entity records are handled elsewhere."
            setPadding(20, 18, 20, 12)
        }

        browseButton = Button(this).apply {
            text = "Browse Extra DB Keys"
            setOnClickListener {
                if (!allKeysLoaded) loadAllKeysInBackground()
            }
        }

        // Temporary debugging tool: exports EVERY LevelDB key name, including
        // chunk-storage keys. It never reads the corresponding values.
        exportKeysButton = Button(this).apply {
            text = "DEBUG: Export ALL LevelDB key names (.txt)"
            setOnClickListener {
                exportAllKeyNames()
            }
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
                val bridge = LevelDbSession.ensureOpen(dir)

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
    private fun loadAllKeysInBackground() {
        val bridge = LevelDbSession.ensureOpen(worldDir ?: return)
        if (isLoading) return

        isLoading = true
        browseButton.isEnabled = false
        browseButton.text = "Loading Extra DB Keys…"

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
                    browseButton.text = "Extra DB keys loaded (${names.size})"
                    isLoading = false
                }
            } catch (e: Exception) {
                isLoading = false
                runOnUiThread {
                    browseButton.isEnabled = true
                    browseButton.text = "Browse Extra DB Keys"
                    showErrorDialog(this, "Couldn't load LevelDB keys", e)
                }
            }
        }.start()
    }


    /**
     * Temporary debugging feature. Creates a text document containing every
     * LevelDB key name, one per line. Values are NEVER read, decoded, or exported.
     */
    private fun exportAllKeyNames() {
        if (isLoading) return
        val dir = worldDir
        if (dir == null) {
            Toast.makeText(this, "World path is missing.", Toast.LENGTH_SHORT).show()
            return
        }
        try { LevelDbSession.ensureOpen(dir) } catch (e: Exception) {
            showErrorDialog(this, "LevelDB isn't available", e)
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
        val bridge = LevelDbSession.ensureOpen(worldDir ?: return)
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
                val bridge = LevelDbSession.ensureOpen(worldDir ?: throw IllegalStateException("World path is missing"))

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

    override fun onResume() {
        super.onResume()
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        keepAliveHandler.postDelayed(keepAliveRunnable, 10_000L)
    }

    override fun onPause() {
        keepAliveHandler.removeCallbacks(keepAliveRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not close the shared LevelDB here. The map viewer and other editors
        // may still be using the same session, and ensureOpen() can recover a stale handle.

    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
        private const val REQUEST_EXPORT_KEY_NAMES = 2001
    }
}
