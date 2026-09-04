package com.spaimic.nbttool.ui

import com.spaimic.nbttool.R

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtJson
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.nbt.NbtHistory
import com.spaimic.nbttool.worlds.LocalWorldStore
import java.io.File

/**
 * Opens a local (already-imported) world's level.dat. Since the whole world
 * lives in this app's own sandbox, this is just plain java.io.File access —
 * no Shizuku involved, and none of the write-permission issues that come with
 * touching Minecraft's own Android/data folder directly.
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NbtTreeAdapter
    private lateinit var worldDir: File
    private lateinit var levelDatFile: File
    private var levelDat: NbtIO.LevelDat? = null
    private var hasUnsavedChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val worldPath = intent.getStringExtra(EXTRA_WORLD_PATH) ?: run { finish(); return }
        worldDir = File(worldPath)
        levelDatFile = File(worldDir, "level.dat")
        title = "level.dat"

        val root = FrameLayout(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        hScroll.addView(recycler)
        content.addView(hScroll)
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(createAddFab(this) { adapter.promptAddChildToRoot() })
        setContentView(root)

        load()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save").apply {
            setIcon(R.drawable.save)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_EXPORT_JSON, 0, "Export JSON")
        menu.add(0, MENU_IMPORT_JSON, 0, "Import JSON")
        menu.add(0, MENU_UNDO, 0, "Undo")
        menu.add(0, MENU_REDO, 0, "Redo")
        menu.add(0, MENU_SEARCH, 0, "Global NBT Search").apply {
            setIcon(R.drawable.search)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE -> { save(); return true }
            MENU_EXPORT_JSON -> {
                NbtJsonFileOps.launchExport(this, NbtJsonFileOps.sanitizeFileName(worldDir.name + "_level"))
                return true
            }
            MENU_UNDO -> { if (NbtHistory.undo(levelDat?.root ?: return true)) { hasUnsavedChanges = true; adapter.refresh() }; return true }
            MENU_REDO -> { if (NbtHistory.redo(levelDat?.root ?: return true)) { hasUnsavedChanges = true; adapter.refresh() }; return true }
            MENU_SEARCH -> { adapter.showGlobalSearch(); return true }
            MENU_IMPORT_JSON -> {
                NbtJsonFileOps.launchImportPicker(this)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val dat = levelDat ?: return
        when (requestCode) {
            NbtJsonFileOps.REQUEST_EXPORT_JSON -> {
                try {
                    NbtJsonFileOps.writeText(this, uri, NbtJson.exportCompoundToJsonString(dat.root))
                    Toast.makeText(this, "Exported.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showErrorDialog(this, "Export failed", e)
                }
            }
            NbtJsonFileOps.REQUEST_IMPORT_JSON -> {
                Toast.makeText(this, "Importing JSON…", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val imported = NbtJsonFileOps.openReader(this, uri).use { reader ->
                            NbtJson.importCompoundFromReader(reader)
                        }
                        runOnUiThread {
                            try {
                                val undoRecorded = NbtHistory.recordBeforeIfSafe(dat.root)
                                dat.root.entries.clear()
                                dat.root.entries.putAll(imported.entries)
                                adapter.refresh()
                                hasUnsavedChanges = true
                                Toast.makeText(this, if (undoRecorded) "Imported. Tap Save to write it to level.dat." else "Imported. Very large import: undo snapshot skipped. Tap Save to write it to level.dat.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                showErrorDialog(this, "Import failed — nothing was changed", e)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { showErrorDialog(this, "Import failed — nothing was changed", e) }
                    }
                }.start()
            }
        }
    }

    private fun load() {
        Thread {
            try {
                val parsed = NbtIO.readLevelDat(levelDatFile.readBytes())
                runOnUiThread {
                    levelDat = parsed
                    showTree()
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to load level.dat", e) }
            }
        }.start()
    }

    private fun showTree() {
        val dat = levelDat ?: return
        adapter = NbtTreeAdapter(this, dat.root, { row -> onLeafTapped(row) }) { hasUnsavedChanges = true }
        recycler.adapter = adapter
    }

    private fun onLeafTapped(row: NbtRow) {
        val tag = row.tag
        val current = when (tag) {
            is NbtTag.Byte -> tag.value.toString()
            is NbtTag.Short -> tag.value.toString()
            is NbtTag.Int -> tag.value.toString()
            is NbtTag.Long -> tag.value.toString()
            is NbtTag.Float -> tag.value.toString()
            is NbtTag.Double -> tag.value.toString()
            is NbtTag.Str -> tag.value
            else -> return
        }

        val input = EditText(this).apply { setText(current) }
        AlertDialog.Builder(this)
            .setTitle(row.label)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                try {
                    val newTag: NbtTag = when (tag) {
                        is NbtTag.Byte -> NbtTag.Byte(input.text.toString().toByte())
                        is NbtTag.Short -> NbtTag.Short(input.text.toString().toShort())
                        is NbtTag.Int -> NbtTag.Int(input.text.toString().toInt())
                        is NbtTag.Long -> NbtTag.Long(input.text.toString().toLong())
                        is NbtTag.Float -> NbtTag.Float(input.text.toString().toFloat())
                        is NbtTag.Double -> NbtTag.Double(input.text.toString().toDouble())
                        is NbtTag.Str -> NbtTag.Str(input.text.toString())
                        else -> tag
                    }
                    NbtHistory.recordBefore(levelDat?.root ?: return@setPositiveButton)
                    row.setValue(newTag)
                    hasUnsavedChanges = true
                    adapter.refresh()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid value for ${NbtTag.typeName(tag.id)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val dat = levelDat ?: return
        try {
            val backupFile = File(LocalWorldStore.backupsDir(this), "level_${worldDir.name}_${System.currentTimeMillis()}.dat.bak")
            levelDatFile.copyTo(backupFile, overwrite = true)

            val bytes = NbtIO.writeLevelDat(dat)
            levelDatFile.writeBytes(bytes)
            hasUnsavedChanges = false
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showErrorDialog(this, "Save failed", e)
        }
    }


    override fun onBackPressed() {
        if (!hasUnsavedChanges) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("You have unsaved changes. What would you like to do?")
            .setPositiveButton("Save") { _, _ ->
                saveAndFinishSafely()
            }
            .setNegativeButton("Discard") { _, _ ->
                super.onBackPressed()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun saveAndFinishSafely() {
        Thread {
            try {
                val dat = levelDat ?: throw IllegalStateException("Nothing is loaded")
                val backupFile = File(LocalWorldStore.backupsDir(this), "level_${worldDir.name}_${System.currentTimeMillis()}.dat.bak")
                levelDatFile.copyTo(backupFile, overwrite = true)
                levelDatFile.writeBytes(NbtIO.writeLevelDat(dat))
                runOnUiThread {
                    hasUnsavedChanges = false
                    super@EditorActivity.onBackPressed()
                }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Save failed — changes were not discarded", e) }
            }
        }.start()
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
        private const val MENU_EXPORT_JSON = 2
        private const val MENU_IMPORT_JSON = 3
        private const val MENU_UNDO = 4
        private const val MENU_REDO = 5
        private const val MENU_SEARCH = 6
    }
}
