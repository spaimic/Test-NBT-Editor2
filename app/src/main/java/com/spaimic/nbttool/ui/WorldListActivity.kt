package com.spaimic.nbttool.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * Shows worlds imported into this app's own sandbox (not Minecraft's — see
 * LocalWorldStore for why). Tap a world to edit it; long-press for
 * "Open in Minecraft" (exports back to .mcworld and hands it to Minecraft) or
 * Delete. The + button imports a world either straight from Minecraft (via
 * Shizuku, read-only) or from a picked .mcworld/.mctemplate file.
 */
class WorldListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private var worldDirs: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "My Worlds"

        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        listView = ListView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val importButton = android.widget.Button(this).apply {
            text = "Import a world"
            setOnClickListener { showImportMenu() }
        }
        root.addView(listView)
        root.addView(importButton)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ -> openWorld(worldDirs[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ -> showWorldMenu(worldDirs[position]); true }
    }

    override fun onResume() {
        super.onResume()
        refreshWorldList()
    }

    private fun refreshWorldList() {
        worldDirs = LocalWorldStore.listLocalWorlds(this)
        val rows = worldDirs.map { dir ->
            val info = LocalWorldStore.worldInfo(dir)
            WorldRow(info.name, info.dateLine, info.timeLine)
        }
        listView.adapter = WorldRowAdapter(this, rows)
    }

    private fun openWorld(worldDir: File) {
        val intent = Intent(this, MapViewerActivity::class.java)
        intent.putExtra(MapViewerActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        startActivity(intent)
    }

    private fun showWorldMenu(worldDir: File) {
        android.app.AlertDialog.Builder(this)
            .setTitle(LocalWorldStore.levelName(worldDir))
            .setItems(arrayOf("Open in Minecraft", "Delete")) { _, which ->
                when (which) {
                    0 -> openInMinecraft(worldDir)
                    1 -> confirmDelete(worldDir)
                }
            }
            .show()
    }

    private fun confirmDelete(worldDir: File) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete this local copy?")
            .setMessage("This only deletes the copy inside this app — it does not touch anything in Minecraft.")
            .setPositiveButton("Delete") { _, _ ->
                LocalWorldStore.deleteLocalWorld(worldDir)
                refreshWorldList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInMinecraft(worldDir: File) {
        Thread {
            try {
                val mcworldFile = LocalWorldStore.exportToMcworld(this, worldDir)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", mcworldFile)
                runOnUiThread {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/mcworld")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        // no app registered for that exact MIME type — let the user pick from a
                        // generic chooser instead (Minecraft still shows up for .mcworld files)
                        val chooser = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(Intent.createChooser(chooser, "Open with…"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Couldn't export to .mcworld", e) }
            }
        }.start()
    }

    private fun showImportMenu() {
        android.app.AlertDialog.Builder(this)
            .setItems(arrayOf("Copy from Minecraft", "Import .mcworld / .mctemplate file")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ImportFromMinecraftActivity::class.java))
                    1 -> pickFileToImport()
                }
            }
            .show()
    }

    private fun pickFileToImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            promptImportName(uri)
        }
    }

    private fun promptImportName(uri: Uri) {
        val guessed = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "world"
        val input = android.widget.EditText(this).apply { setText(guessed) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name this imported world")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val localName = LocalWorldStore.uniqueLocalName(this, input.text.toString())
                Thread {
                    try {
                        LocalWorldStore.importFromZip(this, uri, localName)
                        runOnUiThread {
                            Toast.makeText(this, "Imported \"$localName\"", Toast.LENGTH_SHORT).show()
                            refreshWorldList()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { showErrorDialog(this, "Import failed", e) }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_ITEM_CLIPBOARD, 0, "Clipboard")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_ITEM_CLIPBOARD) {
            startActivity(Intent(this, ClipboardActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val REQUEST_CODE_PICK_FILE = 2001
        private const val MENU_ITEM_CLIPBOARD = 1
    }
}
