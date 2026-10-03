package com.spaimic.nbttool.ui

import com.spaimic.nbttool.R

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.nbt.NbtJson
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.nbt.NbtHistory
import com.spaimic.nbttool.shizuku.LevelDbSession

/**
 * Tree editor for a single LevelDB entry (e.g. ~local_player, a map_ item, a
 * village record). Edits mutate the same in-memory NbtTag.Compound the parent
 * screen holds; the top-right Save writes every currently-loaded entry back
 * to the world (not just this one), matching what Save does everywhere else.
 */
class DbEntryEditorActivity : AppCompatActivity() {

    private lateinit var adapter: NbtTreeAdapter
    private lateinit var compound: NbtTag.Compound
    private var displayName: String = ""
    private var hasUnsavedChanges = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: return finish()
        compound = LevelDbSession.loadedEntries[displayName] ?: return finish()
        title = displayName

        val root = FrameLayout(this)
        val hScroll = android.widget.HorizontalScrollView(this).apply { isFillViewport = true }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DbEntryEditorActivity)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        adapter = NbtTreeAdapter(this, compound, { row -> onLeafTapped(row) { adapter.refresh() } }) { hasUnsavedChanges = true }
        recycler.adapter = adapter
        hScroll.addView(recycler)

        root.addView(hScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(createAddFab(this) { adapter.promptAddChildToRoot() })
        setContentView(root)
    }

    private fun onLeafTapped(row: NbtRow, onSaved: () -> Unit) {
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
                    NbtHistory.recordBefore(compound)
                    row.setValue(newTag)
                    LevelDbSession.markDirty(displayName)
                    hasUnsavedChanges = true
                    onSaved()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid value for ${NbtTag.typeName(tag.id)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save").apply {
            setIcon(R.drawable.save)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_EXPORT_JSON, 0, "Export JSON")
        if (displayName.startsWith("structuretemplate_")) {
            menu.add(0, MENU_EXPORT_MCSTRUCTURE, 0, "Export .mcstructure")
        }
        menu.add(0, MENU_IMPORT_JSON, 0, "Import JSON")
        menu.add(0, MENU_DELETE_KEY, 0, "Delete Direct LevelDB Key")
        menu.add(0, MENU_UNDO, 0, "Undo")
        menu.add(0, MENU_REDO, 0, "Redo")
        menu.add(0, MENU_SEARCH, 0, "Global NBT Search").apply {
            setIcon(R.drawable.search)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        if (displayName.startsWith("map_")) {
            menu.add(0, MENU_IMPORT_IMAGE, 0, "Import Image to Map")
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE -> {
                Thread {
                    try {
                        val names = if (LevelDbSession.dirtyEntries.isNotEmpty()) {
                            LevelDbSession.dirtyEntries.toList()
                        } else {
                            listOf(displayName)
                        }
                        LevelDbSession.saveEntries(names)
                        runOnUiThread {
                            hasUnsavedChanges = false
                            Toast.makeText(this, "Saved ${names.size} changed entr${if (names.size == 1) "y" else "ies"}.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Throwable) {
                        runOnUiThread { showErrorDialog(this, "Save failed — no changes were discarded", e) }
                    }
                }.start()
                return true
            }
            MENU_EXPORT_JSON -> {
                NbtJsonFileOps.launchExport(this, NbtJsonFileOps.sanitizeFileName(displayName))
                return true
            }
            MENU_EXPORT_MCSTRUCTURE -> {
                NbtJsonFileOps.launchMcStructureExport(this, NbtJsonFileOps.sanitizeFileName(displayName.removePrefix("structuretemplate_")))
                return true
            }
            MENU_DELETE_KEY -> {
                confirmDeleteDirectKey()
                return true
            }
            MENU_UNDO -> { if (NbtHistory.undo(compound)) { hasUnsavedChanges = true; adapter.refresh() }; return true }
            MENU_REDO -> { if (NbtHistory.redo(compound)) { hasUnsavedChanges = true; adapter.refresh() }; return true }
            MENU_SEARCH -> { adapter.showGlobalSearch(); return true }
            MENU_IMPORT_JSON -> {
                NbtJsonFileOps.launchImportPicker(this)
                return true
            }
            MENU_IMPORT_IMAGE -> {
                launchMapImagePicker()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            NbtJsonFileOps.REQUEST_EXPORT_JSON -> {
                try {
                    NbtJsonFileOps.writeText(this, uri, NbtJson.exportCompoundToJsonString(compound))
                    Toast.makeText(this, "Exported.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showErrorDialog(this, "Export failed", e)
                }
            }
            NbtJsonFileOps.REQUEST_EXPORT_MCSTRUCTURE -> {
                try {
                    NbtJsonFileOps.writeBytes(this, uri, NbtIO.writeRootCompoundNoHeader(compound))
                    Toast.makeText(this, "Exported .mcstructure.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    showErrorDialog(this, "MCStructure export failed", e)
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
                                val undoRecorded = NbtHistory.recordBeforeIfSafe(compound)
                                compound.entries.clear()
                                compound.entries.putAll(imported.entries)
                                adapter.refresh()
                                LevelDbSession.markDirty(displayName)
                                hasUnsavedChanges = true
                                Toast.makeText(this, if (undoRecorded) "Imported. Tap Save to write it to the world." else "Imported. Very large import: undo snapshot skipped. Tap Save to write it to the world.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                showErrorDialog(this, "Import failed — nothing was changed", e)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { showErrorDialog(this, "Import failed — nothing was changed", e) }
                    }
                }.start()
            }
            REQUEST_IMPORT_MAP_IMAGE -> {
                Toast.makeText(this, "Generating map image…", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        NbtHistory.recordBefore(compound)
                        val result = MapImageImporter.importIntoMap(this, uri, compound)
                        runOnUiThread {
                            adapter.refresh()
                            hasUnsavedChanges = true
                            Toast.makeText(
                                this,
                                "Generated map_${result.mapId}. Filled map added to inventory slot ${result.inventorySlot}. Tap Save to write it to the world.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Throwable) {
                        runOnUiThread {
                            showErrorDialog(this, "Map image import failed — nothing was saved", e)
                        }
                    }
                }.start()
            }
        }
    }

    private fun launchMapImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, REQUEST_IMPORT_MAP_IMAGE)
    }

    private fun confirmDeleteDirectKey() {
        val bridge = LevelDbSession.bridge ?: return
        val rawKey = LevelDbSession.keyBytesByName[displayName] ?: return
        val hex = rawKey.joinToString(" ") { "%02x".format(it) }
        AlertDialog.Builder(this)
            .setTitle("Delete LevelDB key?")
            .setMessage("This permanently deletes this direct LevelDB key from the open world:\n\n$displayName\n$hex\n\nFor an entity, the actorprefix record is deleted directly; its chunk digest entry is not automatically rewritten.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    try {
                        bridge.delete(rawKey)
                        LevelDbSession.keyBytesByName.remove(displayName)
                        LevelDbSession.loadedEntries.remove(displayName)
                        LevelDbSession.dirtyEntries.remove(displayName)
                        runOnUiThread {
                            Toast.makeText(this, "LevelDB key deleted.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } catch (e: Throwable) {
                        runOnUiThread { showErrorDialog(this, "Delete failed", e) }
                    }
                }.start()
            }
            .show()
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
                val names = if (LevelDbSession.dirtyEntries.isNotEmpty()) {
                    LevelDbSession.dirtyEntries.toList()
                } else {
                    listOf(displayName)
                }
                LevelDbSession.saveEntries(names)
                runOnUiThread {
                    hasUnsavedChanges = false
                    super@DbEntryEditorActivity.onBackPressed()
                }
            } catch (e: Throwable) {
                runOnUiThread { showErrorDialog(this, "Save failed — changes were not discarded", e) }
            }
        }.start()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        private const val MENU_SAVE = 1
        private const val MENU_EXPORT_JSON = 2
        private const val MENU_IMPORT_JSON = 3
        private const val MENU_EXPORT_MCSTRUCTURE = 8
        private const val MENU_DELETE_KEY = 9
        private const val MENU_UNDO = 4
        private const val MENU_REDO = 5
        private const val MENU_SEARCH = 6
        private const val MENU_IMPORT_IMAGE = 7
        private const val REQUEST_IMPORT_MAP_IMAGE = 12501
    }
}
