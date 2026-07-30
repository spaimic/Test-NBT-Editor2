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
import com.spaimic.nbttool.shizuku.ShizukuShell
import java.io.File

/**
 * Opens a single world's level.dat:
 *   1. Shizuku copies it from the sandboxed Mojang folder into our own app-private storage
 *   2. We parse + display it as an editable tree
 *   3. On save, we re-serialize and Shizuku copies it back
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NbtTreeAdapter
    private lateinit var remoteLevelDatPath: String
    private lateinit var localLevelDatPath: String
    private var levelDat: NbtIO.LevelDat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val worldsPath = intent.getStringExtra(EXTRA_WORLDS_PATH) ?: ShizukuShell.WORLDS_PATH_STANDARD
        val worldDir = intent.getStringExtra(EXTRA_WORLD_DIR) ?: run { finish(); return }
        remoteLevelDatPath = "$worldsPath/$worldDir/level.dat"
        localLevelDatPath = File(getExternalFilesDir(null), "staging_level.dat").absolutePath
        title = "level.dat"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val saveButton = Button(this).apply {
            text = "Save back to world"
            setOnClickListener { save() }
        }
        val openDbButton = Button(this).apply {
            text = "Player / Map / Village data…"
            setOnClickListener { openLevelDb(worldsPath, worldDir) }
        }
        root.addView(recycler)
        root.addView(openDbButton)
        root.addView(saveButton)
        setContentView(root)

        loadFromDevice()
    }

    private fun loadFromDevice() {
        Thread {
            try {
                ShizukuShell.pullFile(remoteLevelDatPath, localLevelDatPath)
                val bytes = File(localLevelDatPath).readBytes()
                levelDat = NbtIO.readLevelDat(bytes)
                runOnUiThread { showTree() }
            } catch (e: Exception) {
                runOnUiThread {
                    showErrorDialog(this, "Failed to load level.dat", e)
                }
            }
        }.start()
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
            .setTitle(row.key)
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
                    row.parent.entries[row.key] = newTag
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
        Thread {
            try {
                // back up the current on-device file before overwriting it
                ShizukuShell.run("cp '$remoteLevelDatPath' '$remoteLevelDatPath.bak' 2>/dev/null")
                val bytes = NbtIO.writeLevelDat(dat)
                File(localLevelDatPath).writeBytes(bytes)
                ShizukuShell.pushFile(localLevelDatPath, remoteLevelDatPath)
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Save failed", e) }
            }
        }.start()
    }

    private fun openLevelDb(worldsPath: String, worldDir: String) {
        val intent = android.content.Intent(this, PlayerDbActivity::class.java)
        intent.putExtra(PlayerDbActivity.EXTRA_WORLDS_PATH, worldsPath)
        intent.putExtra(PlayerDbActivity.EXTRA_WORLD_DIR, worldDir)
        startActivity(intent)
    }

    companion object {
        const val EXTRA_WORLDS_PATH = "worlds_path"
        const val EXTRA_WORLD_DIR = "world_dir"
    }
}
