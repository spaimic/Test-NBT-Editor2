package com.spaimic.nbttool.ui

import com.spaimic.nbttool.R

import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.spaimic.nbttool.chunk.ChunkKeys
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.chunk.TileEntityIcons
import com.spaimic.nbttool.shizuku.BlockEntitySession
import com.spaimic.nbttool.shizuku.LevelDbSession

/**
 * Bedrock stores every block entity (chest, shulker box, sign, furnace, etc.)
 * in one chunk as several NBT compounds concatenated under a SINGLE LevelDB
 * key — not one key each like regular entities get. So this screen loads and
 * saves the whole chunk's list together (see BlockEntitySession), rather than
 * reusing the per-entry save flow the rest of the app uses.
 */
class BlockEntityListActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chunkX = intent.getIntExtra(EXTRA_CHUNK_X, 0)
        val chunkZ = intent.getIntExtra(EXTRA_CHUNK_Z, 0)
        val dimension = intent.getIntExtra(EXTRA_DIMENSION, 0)
        title = "Block entities in chunk ($chunkX, $chunkZ) — ${dimensionName(dimension)}"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(listView)
        setContentView(root)

        listView.setOnItemClickListener { _, _, position, _ ->
            val i = Intent(this, BlockEntityEditorActivity::class.java)
            i.putExtra(BlockEntityEditorActivity.EXTRA_INDEX, position)
            startActivity(i)
        }

        loadBlockEntities(chunkX, chunkZ, dimension)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save").apply {
            setIcon(R.drawable.save)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_DELETE_KEY, 0, "Delete Direct LevelDB Key")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) {
            save()
            return true
        }
        if (item.itemId == MENU_DELETE_KEY) {
            confirmDeleteKey()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadBlockEntities(chunkX: Int, chunkZ: Int, dimension: Int) {
        val bridge = LevelDbSession.bridge
        if (bridge == null) {
            Toast.makeText(this, "World database isn't open", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val key = ChunkKeys.blockEntityKey(chunkX, chunkZ, dimension)
                val bytes = bridge.read(key)
                BlockEntitySession.reset()
                BlockEntitySession.rawKey = key
                if (bytes != null && bytes.isNotEmpty()) {
                    BlockEntitySession.compounds.addAll(NbtIO.readConcatenatedCompounds(bytes))
                }
                runOnUiThread {
                    if (BlockEntitySession.compounds.isEmpty()) {
                        showNoBlockEntitiesDialog()
                    } else {
                        refresh()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Failed to load block entities", e) }
            }
        }.start()
    }

    private fun refresh() {
        val rows = BlockEntitySession.compounds.map { c ->
            val id = (c.entries["id"] as? NbtTag.Str)?.value ?: "Unknown"
            val x = (c.entries["x"] as? NbtTag.Int)?.value
            val y = (c.entries["y"] as? NbtTag.Int)?.value
            val z = (c.entries["z"] as? NbtTag.Int)?.value
            val label = if (x != null && y != null && z != null) "$id ($x, $y, $z)" else id
            label to TileEntityIcons.iconFor(this, id)
        }
        listView.adapter = object : ArrayAdapter<Pair<String, Int>>(this, 0, rows) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val item = getItem(position)!!
                val icon = ImageView(context).apply {
                    setImageResource(item.second)
                    layoutParams = LinearLayout.LayoutParams(72, 72).apply { rightMargin = 24 }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                val text = TextView(context).apply {
                    text = item.first
                    textSize = 16f
                }
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(32, 24, 32, 24)
                    addView(icon)
                    addView(text)
                }
            }
        }
    }

    private fun showNoBlockEntitiesDialog() {
        android.app.AlertDialog.Builder(this)
            .setMessage("No block entities found in this chunk")
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    private fun confirmDeleteKey() {
        val bridge = LevelDbSession.bridge ?: return
        val key = BlockEntitySession.rawKey ?: return
        val hex = key.joinToString(" ") { "%02x".format(it) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete block-entity LevelDB key?")
            .setMessage("This deletes the complete BlockEntity key for chunk (${intent.getIntExtra(EXTRA_CHUNK_X, 0)}, ${intent.getIntExtra(EXTRA_CHUNK_Z, 0)}). All tile/block entities stored under this direct key will be removed.\n\n$hex")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    try {
                        bridge.delete(key)
                        BlockEntitySession.reset()
                        runOnUiThread {
                            Toast.makeText(this, "BlockEntity LevelDB key deleted.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    } catch (e: Throwable) {
                        runOnUiThread { showErrorDialog(this, "Delete failed", e) }
                    }
                }.start()
            }
            .show()
    }

    private fun save() {
        val bridge = LevelDbSession.bridge ?: return
        val key = BlockEntitySession.rawKey ?: return
        Thread {
            try {
                val bytes = NbtIO.writeConcatenatedCompounds(BlockEntitySession.compounds)
                bridge.write(key, bytes)
                runOnUiThread { Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { showErrorDialog(this, "Save failed", e) }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (BlockEntitySession.compounds.isNotEmpty()) refresh() // pick up edits made in the sub-editor
    }

    companion object {
        const val EXTRA_CHUNK_X = "chunk_x"
        const val EXTRA_CHUNK_Z = "chunk_z"
        const val EXTRA_DIMENSION = "dimension"
        private const val MENU_SAVE = 1
        private const val MENU_DELETE_KEY = 2

        private fun dimensionName(dimension: Int) = when (dimension) {
            1 -> "Nether"
            2 -> "The End"
            else -> "Overworld"
        }
    }
}
