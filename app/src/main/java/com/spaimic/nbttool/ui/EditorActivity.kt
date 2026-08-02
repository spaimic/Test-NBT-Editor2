package com.spaimic.nbttool.ui

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
        val openDbButton = Button(this).apply {
            text = "Player / Map / Village data…"
            setOnClickListener { openLevelDb() }
        }
        content.addView(hScroll)
        content.addView(openDbButton)
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(createAddFab(this) { adapter.promptAddChildToRoot() })
        setContentView(root)

        load()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save")
        menu.add(0, MENU_EXPORT_JSON, 0, "Export JSON")
        menu.add(0, MENU_IMPORT_JSON, 0, "Import JSON")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE -> { save(); return true }
            MENU_EXPORT_JSON -> {
                NbtJsonFileOps.launchExport(this, NbtJsonFileOps.sanitizeFileName(worldDir.name + "_level"))
                return true
            }
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
                try {
                    val json = NbtJsonFileOps.readText(this, uri)
                    val imported = NbtJson.importCompoundFromJsonString(json) // throws before anything is touched
                    dat.root.entries.clear()
                    dat.root.entries.putAll(imported.entries)
                    adapter.refresh()
                    Toast.makeText(this, "Imported. Tap Save to write it to level.dat.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    showErrorDialog(this, "Import failed — nothing was changed", e)
                }
            }
        }
    }

    private fun load() {
        try {
            levelDat = NbtIO.readLevelDat(levelDatFile.readBytes())
            showTree()
        } catch (e: Exception) {
            showErrorDialog(this, "Failed to load level.dat", e)
        }
    }

    private fun showTree() {
        val dat = levelDat ?: return
        adapter = NbtTreeAdapter(this, dat.root) { row -> onLeafTapped(row) }
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
                    row.setValue(newTag)
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
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showErrorDialog(this, "Save failed", e)
        }
    }

    private fun openLevelDb() {
        val intent = android.content.Intent(this, PlayerDbActivity::class.java)
        intent.putExtra(PlayerDbActivity.EXTRA_WORLD_PATH, worldDir.absolutePath)
        startActivity(intent)
    }

    companion object {
        const val EXTRA_WORLD_PATH = "world_path"
        private const val MENU_SAVE = 1
        private const val MENU_EXPORT_JSON = 2
        private const val MENU_IMPORT_JSON = 3
    }
}
