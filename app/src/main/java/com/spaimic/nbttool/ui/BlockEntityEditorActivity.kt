package com.spaimic.nbttool.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.BlockEntitySession
import com.spaimic.nbttool.shizuku.LevelDbSession

class BlockEntityEditorActivity : AppCompatActivity() {

    private lateinit var adapter: NbtTreeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val compound = BlockEntitySession.compounds.getOrNull(index) ?: return finish()
        title = (compound.entries["id"] as? NbtTag.Str)?.value ?: "Block entity"

        val root = FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BlockEntityEditorActivity)
        }
        adapter = NbtTreeAdapter(this, compound) { row -> onLeafTapped(row) { adapter.refresh() } }
        recycler.adapter = adapter

        root.addView(recycler, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
                    row.setValue(newTag)
                    onSaved()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid value for ${NbtTag.typeName(tag.id)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "Save")
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == MENU_SAVE) {
            save()
            return true
        }
        return super.onOptionsItemSelected(item)
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

    companion object {
        const val EXTRA_INDEX = "index"
        private const val MENU_SAVE = 1
    }
}
