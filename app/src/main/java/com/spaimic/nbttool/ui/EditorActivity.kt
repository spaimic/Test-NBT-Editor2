package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.nbt.NbtIO
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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        }
        val openDbButton = Button(this).apply {
            text = "Player / Map / Village data…"
            setOnClickListener { openLevelDb() }
        }
        root.addView(recycler)
        root.addView(openDbButton)
        root.addView(saveButton)
        setContentView(root)

        load()
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
    }
}
